"""Fast loopback Minecraft RCON client."""
from __future__ import annotations

import select
import socket
import struct
from dataclasses import dataclass

_LOGIN = 3
_COMMAND = 2
_RESPONSE_GAP_SECONDS = 0.05


@dataclass(frozen=True)
class RconResult:
    returncode: int
    output: str


def _packet(request_id: int, kind: int, body: str) -> bytes:
    payload = struct.pack("<ii", request_id, kind) + body.encode("utf-8") + b"\x00\x00"
    return struct.pack("<i", len(payload)) + payload


def _read_exact(sock: socket.socket, size: int) -> bytes:
    chunks = bytearray()
    while len(chunks) < size:
        piece = sock.recv(size - len(chunks))
        if not piece:
            raise RuntimeError("rcon connection closed")
        chunks.extend(piece)
    return bytes(chunks)


def _read_packet(sock: socket.socket) -> tuple[int, int, str]:
    length = struct.unpack("<i", _read_exact(sock, 4))[0]
    body = _read_exact(sock, length)
    request_id, kind = struct.unpack("<ii", body[:8])
    return request_id, kind, body[8:-2].decode("utf-8", errors="replace")


def rcon_command(host: str, port: int, password: str, command: str, timeout: float = 15.0) -> RconResult:
    with socket.create_connection((host, port), timeout=timeout) as sock:
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.sendall(_packet(1, _LOGIN, password))
        request_id, _kind, _payload = _read_packet(sock)
        if request_id == -1:
            return RconResult(1, "rcon auth failed")
        sock.sendall(_packet(2, _COMMAND, command))
        pieces = []
        while True:
            pieces.append(_read_packet(sock)[2])
            readable, _, _ = select.select([sock], [], [], _RESPONSE_GAP_SECONDS)
            if not readable or sock.recv(1, socket.MSG_PEEK) == b"":
                return RconResult(0, "".join(pieces))
