"""Convert one pinned model to an immutable runtime directory; PyTorch is conversion-only."""
import argparse
import hashlib
import json
from pathlib import Path
import shutil
import subprocess
import tempfile
import urllib.request
import zipfile


def sha256(path):
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha256").hexdigest()


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--variant", choices=("standard", "tcbig"), default="tcbig")
    parser.add_argument("--output", type=Path, required=True)
    args = parser.parse_args()
    if args.output.exists():
        parser.error("Output must be a new immutable directory")
    models = json.loads(Path(__file__).with_name("models.json").read_text())
    model = models[args.variant]
    with tempfile.TemporaryDirectory(prefix="catalogue-model-") as work:
        work = Path(work)
        converted = work / "converted"
        if args.variant == "standard":
            subprocess.run(["ct2-transformers-converter", "--model", model["repository"],
                            "--revision", model["revision"], "--output_dir", str(converted),
                            "--quantization", "int8", "--copy_files", "source.spm", "target.spm"], check=True)
        else:
            archive = work / "model.zip"
            with urllib.request.urlopen(model["url"], timeout=60) as response, archive.open("wb") as output:
                size = 0
                while chunk := response.read(1024 * 1024):
                    size += len(chunk)
                    if size > 1024 * 1024 * 1024:
                        raise ValueError("Model download bound exceeded")
                    output.write(chunk)
            if sha256(archive) != model["sha256"]:
                raise ValueError("Model archive checksum mismatch")
            original = work / "original"
            with zipfile.ZipFile(archive) as zipped:
                # Only the known flat model files required by conversion, bounded to 2 GiB.
                members = [x for x in zipped.infolist() if "/" not in x.filename and not x.is_dir()]
                if sum(x.file_size for x in members) > 2 * 1024 * 1024 * 1024:
                    raise ValueError("Model extraction bound exceeded")
                zipped.extractall(original, members)
            subprocess.run(["ct2-opus-mt-converter", "--model_dir", str(original), "--output_dir",
                            str(converted), "--quantization", "int8"], check=True)
            for name in ("source.spm", "target.spm", "LICENSE", "README.md"):
                shutil.copy(original / name, converted / name)
        hashes = {name: sha256(converted / name) for name in
                  ("model.bin", "config.json", "shared_vocabulary.json", "source.spm", "target.spm")}
        digest = hashlib.sha256(json.dumps(hashes, sort_keys=True).encode()).hexdigest()
        (converted / "manifest.json").write_text(json.dumps({"model": model, "files": hashes}, indent=2))
        (converted / "revision.txt").write_text(f"opus-{args.variant}:{digest};ct2-4.8.2;int8;beam2;chunk256;decode511\n")
        args.output.parent.mkdir(parents=True, exist_ok=True)
        shutil.copytree(converted, args.output)
    print(f"Installed {args.variant} ({model['license']}) into {args.output}")


if __name__ == "__main__":
    main()
