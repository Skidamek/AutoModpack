"""Ephemeral loopback ports. The host never publishes a fixed game port."""
from __future__ import annotations

import socket


def allocate(count: int = 1) -> tuple[int, ...]:
    """Bind ``count`` free ``127.0.0.1`` ports, then release them so a child can take them.

    The window after this returns is a TOCTOU race against other binders. Callers that need a held port keep the sockets themselves. ``count`` < 1 is a caller bug.
    """
    if count < 1:
        raise ValueError(f"allocate() needs at least one port, got {count}")
    sockets: list[socket.socket] = []
    try:
        for _ in range(count):
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            sock.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
            sock.bind(("127.0.0.1", 0))
            sockets.append(sock)
        return tuple(sock.getsockname()[1] for sock in sockets)
    finally:
        for sock in sockets:
            sock.close()
