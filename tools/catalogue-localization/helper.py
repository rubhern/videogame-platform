"""Private acquisition helper: one loaded OPUS-MT EN -> ES model, CPU inference only."""
import argparse
import hashlib
import json
import logging
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import re
import time

MAX_SOURCE = 20000
MAX_BODY = 100000
MAX_TOKENS = 256
MAX_DECODE = 511


class Translator:
    def __init__(self, model_dir, threads=2):
        manifest = json.loads((Path(model_dir) / "manifest.json").read_text())
        for name, expected in manifest["files"].items():
            with (Path(model_dir) / name).open("rb") as stream:
                if hashlib.file_digest(stream, "sha256").hexdigest() != expected:
                    raise ValueError("Model artifact checksum mismatch")
        import ctranslate2
        import sentencepiece
        from sacremoses import MosesPunctNormalizer
        self.source = sentencepiece.SentencePieceProcessor(model_file=str(Path(model_dir) / "source.spm"))
        self.target = sentencepiece.SentencePieceProcessor(model_file=str(Path(model_dir) / "target.spm"))
        self.normalize = MosesPunctNormalizer(lang="en").normalize
        self.engine = ctranslate2.Translator(str(model_dir), device="cpu", compute_type="int8",
                                             inter_threads=1, intra_threads=threads)
        self.add_eos = not json.loads((Path(model_dir) / "config.json").read_text())["add_source_eos"]
        self.revision = (Path(model_dir) / "revision.txt").read_text().strip()
        if not self.revision or len(self.revision) > 200:
            raise ValueError("Missing immutable model revision")

    def translate(self, text):
        if not isinstance(text, str) or not text.strip() or len(text) > MAX_SOURCE or "\0" in text:
            raise ValueError("Invalid source")
        # Translate whole sentences where possible. A long sentence is split without dropping tokens.
        sentences = re.split(r"(?<=[.!?])\s+", self.normalize(text))
        chunks = []
        for sentence in sentences:
            tokens = self.source.encode(sentence, out_type=str)
            chunks.extend(tokens[i:i + MAX_TOKENS] for i in range(0, len(tokens), MAX_TOKENS))
        if not chunks or len(chunks) > 128:
            raise ValueError("Source token bound exceeded")
        if self.add_eos:
            chunks = [chunk + ["</s>"] for chunk in chunks]
        results = self.engine.translate_batch(chunks, beam_size=2, max_batch_size=8,
                                               max_input_length=0, max_decoding_length=MAX_DECODE)
        pieces = []
        for result in results:
            tokens = result.hypotheses[0]
            if len(tokens) >= MAX_DECODE:
                raise ValueError("Incomplete output")
            pieces.append(self.target.decode(tokens).strip())
        output = " ".join(pieces)
        if not output.strip() or len(output) > MAX_SOURCE or "\0" in output:
            raise ValueError("Invalid output")
        return {"text": output, "revision": self.revision}


class PrivateServer(HTTPServer):
    request_queue_size = 1
    def get_request(self):
        connection, address = super().get_request()
        connection.settimeout(10)
        return connection, address


class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_args):
        pass  # Never log source payloads, headers or raw exception messages.

    def reply(self, code, payload):
        body = json.dumps(payload, ensure_ascii=False).encode("utf-8")
        self.send_response(code)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        try:
            self.wfile.write(body)
        except (BrokenPipeError, ConnectionResetError):
            pass  # A timed-out caller retains source and retries through its PostgreSQL lease.

    def do_GET(self):
        self.reply(200 if self.path == "/ready" else 404, {"ready": self.path == "/ready"})

    def do_POST(self):
        started = time.monotonic()
        if self.path != "/translate":
            self.reply(404, {"code": "NOT_FOUND"})
            return
        try:
            size = int(self.headers.get("Content-Length", "0"))
            if size < 1 or size > MAX_BODY:
                raise ValueError("Invalid body bound")
            data = json.loads(self.rfile.read(size))
            if not isinstance(data, dict) or set(data) != {"text"}:
                raise ValueError("Invalid request")
            result = self.server.translator.translate(data["text"])
            self.reply(200, result)
            logging.info("translation outcome=completed seconds=%.3f", time.monotonic() - started)
        except (ValueError, KeyError):
            self.reply(422, {"code": "INVALID_TRANSLATION"})
            logging.warning("translation outcome=invalid")
        except Exception:
            self.reply(503, {"code": "TRANSLATION_UNAVAILABLE"})
            logging.warning("translation outcome=failed")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--model", required=True)
    parser.add_argument("--bind", default="127.0.0.1")
    parser.add_argument("--port", type=int, default=8092)
    parser.add_argument("--threads", type=int, choices=range(1, 9), default=2)
    args = parser.parse_args()
    logging.basicConfig(level=logging.INFO, format="%(asctime)s %(message)s")
    server = PrivateServer((args.bind, args.port), Handler)
    server.translator = Translator(args.model, args.threads)
    logging.info("catalogue translation helper ready")
    server.serve_forever()


if __name__ == "__main__":
    main()
