"""Loopback Minecraft RCON. Native java servers have no docker exec."""
from __future__ import annotations

import select
import socket
import struct

from ..runtime.base import ExecResult

_LOGIN = 3
_COMMAND = 2
# Vanilla splits a response into 4096-byte packets, sends them back to back and never sends a
# terminator (measured against a live 26.2 server: `help` arrived as 4096 + 1609). The gap is a
# tripwire: a follow-up packet of the same response is local and immediate, so this only fires
# once the response is complete.
_RESPONSE_GAP_SECONDS = 0.25


def _packet(request_id: int, kind: int, body: str) -> bytes:
    payload = struct.pack("<ii", request_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def _read_packet(sock: socket.socket) -> tuple[int, int, str]:
    header = _read_exact(sock, 4)
    length = struct.unpack("<i", header)[0]
    body = _read_exact(sock, length)
    request_id, kind = struct.unpack("<ii", body[:8])
    payload = body[8:-2].decode("utf-8", errors="replace")
    return request_id, kind, payload


def _read_exact(sock: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        piece = sock.recv(size - len(chunks))
        if not piece:
            raise RuntimeError("rcon connection closed")
        chunks.extend(piece)
    return bytes(chunks)


def run(host: str, port: int, password: str, command: str, timeout: float = 15) -> ExecResult:
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.sendall(_packet(1, _LOGIN, password))
        request_id, _kind, _payload = _read_packet(sock)
        if request_id == -1:
            return ExecResult(1, "rcon auth failed")
        sock.sendall(_packet(2, _COMMAND, command))
        pieces = []
        while True:
            pieces.append(_read_packet(sock)[2])
            readable, _, _ = select.select([sock], [], [], _RESPONSE_GAP_SECONDS)
            if not readable or sock.recv(1, socket.MSG_PEEK) == b"":
                # Nothing follows: either the response is complete or the server closed right
                # after replying, which is still a complete response.
                return ExecResult(0, "".join(pieces))
