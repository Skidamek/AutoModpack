"""In-process HTTPS static file server (with cached self-signed TLS cert) — zero Docker required."""
from __future__ import annotations

import functools
import hashlib
import http.server
import ssl
import subprocess
import threading
from pathlib import Path

from filelock import FileLock

from .config import shared_cache_root


def ensure_self_signed_cert() -> tuple[Path, Path, str]:
    """Generate and cache a self-signed loopback TLS cert once in the shared cache; returns (cert, key, sha256_hex)."""
    cert_dir = shared_cache_root() / "tls"
    cert_dir.mkdir(parents=True, exist_ok=True)
    cert = cert_dir / "localhost.crt"
    key = cert_dir / "localhost.key"
    fp_file = cert_dir / "localhost.sha256"
    with FileLock(str(cert_dir / ".lock")):
        if not (cert.is_file() and key.is_file() and fp_file.is_file()):
            subprocess.run(
                [
                    "openssl",
                    "req",
                    "-x509",
                    "-newkey",
                    "rsa:2048",
                    "-nodes",
                    "-keyout",
                    str(key),
                    "-out",
                    str(cert),
                    "-days",
                    "365",
                    "-subj",
                    "/CN=127.0.0.1",
                    "-addext",
                    "subjectAltName=IP:127.0.0.1,DNS:localhost",
                ],
                check=True,
                capture_output=True,
            )
            der = subprocess.check_output(["openssl", "x509", "-in", str(cert), "-outform", "DER"])
            fp_file.write_text(hashlib.sha256(der).hexdigest(), encoding="utf-8")
        return cert, key, fp_file.read_text(encoding="utf-8").strip()


class _QuietHandler(http.server.SimpleHTTPRequestHandler):
    def log_message(self, format: str, *args) -> None:
        return


class StaticHttpsHost:
    """In-process HTTPS server serving a local directory on 127.0.0.1:<ephemeral_port>."""

    def __init__(self, root: Path, *, use_tls: bool = True):
        self.root = root
        self.use_tls = use_tls
        self.port = 0
        self.fingerprint_sha256 = ""
        self._httpd: http.server.ThreadingHTTPServer | None = None
        self._thread: threading.Thread | None = None

    def start(self) -> int:
        handler = functools.partial(_QuietHandler, directory=str(self.root))
        httpd = http.server.ThreadingHTTPServer(("127.0.0.1", 0), handler)
        if self.use_tls:
            cert, key, fp = ensure_self_signed_cert()
            self.fingerprint_sha256 = fp
            ctx = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
            ctx.load_cert_chain(certfile=str(cert), keyfile=str(key))
            httpd.socket = ctx.wrap_socket(httpd.socket, server_side=True)
        self._httpd = httpd
        self.port = int(httpd.server_address[1])
        self._thread = threading.Thread(target=httpd.serve_forever, name="amp-static-https", daemon=True)
        self._thread.start()
        return self.port

    def stop(self) -> None:
        if self._httpd is not None:
            self._httpd.shutdown()
            self._httpd.server_close()
            self._httpd = None
        if self._thread is not None:
            self._thread.join(timeout=5.0)
            self._thread = None

    @property
    def base_url(self) -> str:
        scheme = "https" if self.use_tls else "http"
        return f"{scheme}://127.0.0.1:{self.port}"
