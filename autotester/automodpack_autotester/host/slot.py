"""One case on this kernel: a directory, a port bundle, and a process tree."""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path

from .ports import allocate
from .process import Process


@dataclass
class Slot:
    id: str
    root: Path
    game_port: int
    endpoint_port: int
    rcon_port: int
    processes: dict[str, Process] = field(default_factory=dict)

    @property
    def game_dir(self) -> Path:
        return self.root / "client" / "game"

    @property
    def server_dir(self) -> Path:
        return self.root / "server"

    def spawn(self, name: str, argv: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None, log: Path | None = None) -> Process:
        child = Process()
        child.spawn(argv, cwd=cwd, env=env, log=log if log is not None else self.root / f"{name}.log")
        self.processes[name] = child
        return child

    def kill_all(self) -> None:
        for child in list(self.processes.values()):
            child.kill()
        self.processes.clear()


def open_slot(root: Path, slot_id: str) -> Slot:
    game_port, endpoint_port, rcon_port = allocate(3)
    slot = Slot(id=slot_id, root=root, game_port=game_port, endpoint_port=endpoint_port, rcon_port=rcon_port)
    slot.game_dir.mkdir(parents=True, exist_ok=True)
    slot.server_dir.mkdir(parents=True, exist_ok=True)
    return slot
