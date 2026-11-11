"""명시적으로 요청한 S4 실제 클라이언트 시험의 loopback 전용 팩 제공기."""
import isolated_paths
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
import threading


class ClientPackServer:
    def __init__(self, root, port=25587):
        root = Path(root)
        assert not root.is_symlink()
        root = isolated_paths.workspace(root,'hearth-s4-11.')
        assert root.parent == isolated_paths.BASE and root.name.startswith('hearth-s4-11.')
        assert (root / '.hearth-lifecycle-isolated').read_text().strip() == 'S4-11 isolated test only'
        pack = root / 'server/plugins/BetterHud/build.zip'
        self.downloads = 0
        service = self

        class Handler(BaseHTTPRequestHandler):
            def do_HEAD(self):
                self.serve(False)

            def do_GET(self):
                self.serve(True)

            def serve(self, body):
                if self.path != '/hearth-s4.zip':
                    self.send_error(404)
                    return
                try:
                    if not pack.resolve().is_relative_to(root) or pack.is_symlink():
                        raise ValueError('pack symlink')
                    if not 0 < pack.stat().st_size <= 64 * 1024**2:
                        raise ValueError('pack size')
                    with pack.open('rb') as source:
                        data = source.read(64 * 1024**2 + 1)
                    if len(data) > 64 * 1024**2:
                        raise ValueError('pack size')
                except (OSError, ValueError):
                    self.send_error(503)
                    return
                self.send_response(200)
                self.send_header('Content-Type', 'application/zip')
                self.send_header('Content-Length', str(len(data)))
                self.send_header('Cache-Control', 'no-store')
                self.end_headers()
                if body:
                    try:
                        self.wfile.write(data)
                        service.downloads += 1
                    except (BrokenPipeError, ConnectionResetError):
                        pass

            def log_message(self, *_):
                pass

        self.server = ThreadingHTTPServer(('127.0.0.1', port), Handler)
        self.server.daemon_threads = True
        self.thread = threading.Thread(target=self.server.serve_forever, daemon=True)
        self.thread.start()

    def close(self):
        self.server.shutdown()
        self.server.server_close()
        self.thread.join(timeout=5)
        if self.thread.is_alive():
            raise RuntimeError('client pack server still running')
