"""HTTPS test fixture. Never logs request paths, chat IDs or tokens."""
from email import policy
from email.parser import BytesParser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import json
from pathlib import Path
import ssl
import time

fixture=Path('/fixture')
class Mock(BaseHTTPRequestHandler):
    def log_message(self, *args):
        pass

    def do_POST(self):
        expected='/bot'+(fixture/'bot-token').read_text()+'/sendMessage'
        assert self.path==expected
        content=self.rfile.read(int(self.headers['Content-Length']))
        parsed=BytesParser(policy=policy.default).parsebytes(('Content-Type: '+self.headers['Content-Type']+'\r\n\r\n').encode()+content)
        fields={part.get_param('name',header='content-disposition'):part.get_payload(decode=True).decode("utf-8") for part in parsed.iter_parts()}
        assert fields['chat_id']==(fixture/'chat-id').read_text()
        assert 'parse_mode' not in fields, 'None parsing should be omitted by native integration'
        mode=(fixture/'mode').read_text()
        with (fixture/'messages').open('a') as stream:
            stream.write(json.dumps({'text':fields['text'],'mode':mode})+'\n')
        if mode=='timeout':
            time.sleep(45)
        try:
            self.send_response(503 if mode=='fail' else 200)
            self.send_header('Content-Type','application/json')
            self.end_headers()
            self.wfile.write(b'{"ok":false,"description":"fixture unavailable"}' if mode=='fail' else b'{"ok":true,"result":{"message_id":1}}')
        except (BrokenPipeError,ssl.SSLError):
            pass
server=ThreadingHTTPServer(('0.0.0.0',443),Mock)
context=ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain('/fixture/mock.crt','/fixture/mock.key')
server.socket=context.wrap_socket(server.socket,server_side=True)
server.serve_forever()
