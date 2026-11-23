"""Ephemeral loopback ports, bound until the child that owns them is about to start."""
from __future__ import annotations

import socket
from dataclasses import dataclass, field


@dataclass
class Reservation:
    """Ports this process holds so no other case can be handed the same numbers.

    A case keeps its reservation until the server is about to bind them. Releasing at
    allocation time let a second concurrent case receive the same ports, because nothing
    owned them during the seed-tree copy that happens before the server starts.
    """

    ports: tuple[int, ...]
    _sockets: tuple[socket.socket, ...] = field(repr=False)

    def release(self) -> None:
        for sock in self._sockets:
            sock.close()
        self._sockets = ()


def reserve(count: int = 1) -> Reservation:
    """Bind ``count`` free ``127.0.0.1`` ports and keep them bound. ``count`` < 1 is a caller bug."""
    if count < 1:
        raise ValueError(f"reserve() needs at least one port, got {count}")
    sockets: list[socket.socket] = []
    try:
        for _ in range(count):
            sock = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
            # No SO_REUSEADDR: a closed, never-connected listener has no TIME_WAIT, and on Windows
            # SO_REUSEADDR would let another binder share the port, which defeats the reservation.
            sock.bind(("127.0.0.1", 0))
            sockets.append(sock)
        return Reservation(tuple(sock.getsockname()[1] for sock in sockets), tuple(sockets))
    except BaseException:
        for sock in sockets:
            sock.close()
        raise
