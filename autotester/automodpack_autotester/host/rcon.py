"""Loopback Minecraft RCON. Native java servers have no docker exec."""
from __future__ import annotations

import struct
import socket

from ..runtime.base import ExecResult

_LOGIN = 3
_COMMAND = 2


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
        _request_id, _kind, payload = _read_packet(sock)
        return ExecResult(0, payload)
