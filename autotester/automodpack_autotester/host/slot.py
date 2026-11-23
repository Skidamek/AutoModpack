"""One case on this kernel: a directory, a reserved port bundle, and a process tree."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .ports import Reservation, reserve
from .process import Process


@dataclass
class Slot:
    id: str
    root: Path
    reservation: Reservation
    processes: dict[str, Process] = field(default_factory=dict)

    @property
    def game_port(self) -> int:
        return self.reservation.ports[0]

    @property
    def endpoint_port(self) -> int:
        return self.reservation.ports[1]

    @property
    def rcon_port(self) -> int:
        return self.reservation.ports[2]

    @property
    def game_dir(self) -> Path:
        return self.root / "client" / "game"

    @property
    def server_dir(self) -> Path:
        return self.root / "server"

    def release_ports(self) -> None:
        """Hand the ports to the server that is about to bind them."""
        self.reservation.release()

    def spawn(self, name: str, argv: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None, log: Path | None = None) -> Process:
        child = Process()
        child.spawn(argv, cwd=cwd, env=env, log=log if log is not None else self.root / f"{name}.log")
        self.processes[name] = child
        return child

    def kill_all(self) -> None:
        for child in list(self.processes.values()):
            child.kill()
        self.processes.clear()
        self.release_ports()


def open_slot(root: Path, slot_id: str) -> Slot:
    slot = Slot(id=slot_id, root=root, reservation=reserve(3))
    slot.game_dir.mkdir(parents=True, exist_ok=True)
    slot.server_dir.mkdir(parents=True, exist_ok=True)
    return slot
