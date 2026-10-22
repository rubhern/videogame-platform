"""Isolated synthetic metrics and Telegram fixture; never installed on a host."""
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import runpy
import threading

class Metrics(BaseHTTPRequestHandler):
    def log_message(self, *_): pass
    def do_GET(self):
        if self.path == '/health/ready':
            body=b'{"status":"UP"}'
        else:
            body=(Path('/fixture') / 'metrics.prom').read_bytes()
        self.send_response(200);self.send_header('Content-Type','text/plain; version=0.0.4');self.end_headers();self.wfile.write(body)

threading.Thread(target=ThreadingHTTPServer(('0.0.0.0',9000),Metrics).serve_forever,daemon=True).start()
runpy.run_path('/telegram-mock.py',run_name='__main__')
