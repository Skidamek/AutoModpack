"""A deliberately barebones static HTTPS file server for the modpack contract.

Python's SimpleHTTPRequestHandler on purpose: HTTP/1.0 (the connection closes
after every response), no Range handling (every GET answers 200 with the full
body), no conditional requests, no Content-Encoding. The static-host scenario
stands on these defaults: the client must degrade to close-delimited parsing,
per-response lane death, and one open-ended take per object on a host that
offers none of the full contract's conveniences.

The self-signed certificate is generated at container start; the harness reads
its fingerprint from the log to answer the client's first-contact prompt.
"""
import functools
import hashlib
import http.server
import ssl
import subprocess
import sys

root, port = sys.argv[1], int(sys.argv[2])
cert, key = "/tmp/static-host-cert.pem", "/tmp/static-host-key.pem"
subprocess.run(
    ["openssl", "req", "-x509", "-newkey", "rsa:2048", "-nodes", "-keyout", key, "-out", cert, "-days", "2", "-subj", "/CN=automodpack-static-host"],
    check=True, capture_output=True,
)
der = subprocess.run(["openssl", "x509", "-in", cert, "-outform", "DER"], check=True, capture_output=True).stdout
print(f"static host serving {root} on :{port}", flush=True)
print("certificate fingerprint: " + hashlib.sha256(der).hexdigest(), flush=True)

context = ssl.SSLContext(ssl.PROTOCOL_TLS_SERVER)
context.load_cert_chain(cert, key)
server = http.server.ThreadingHTTPServer(("0.0.0.0", port), functools.partial(http.server.SimpleHTTPRequestHandler, directory=root))
server.socket = context.wrap_socket(server.socket, server_side=True)
server.serve_forever()
