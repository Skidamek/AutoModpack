"""Execution context shared by every step: data, variables, templating, bridge access."""
from __future__ import annotations

import re
from collections.abc import Callable
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

from ..bridge import BridgeClient
from .util import ClientExited

_VAR = re.compile(r"\$\{([^}]+)\}")


@dataclass
class Context:
    """Everything a step needs. Built once per test case by the runner."""

    target: Any
    scenario: dict
    settings: dict
    game_dir: Path
    server_dir: Path
    out_dir: Path
    client_image: str
    srv_name: str
    cli_name: str
    net_name: str
    token: str
    artifact: Path
    modpack_name: str
    marker_rel: Path
    scenario_files: list  # list[(Path, str)]
    expected_mods: list
    vars: dict = field(default_factory=dict)
    bridge: BridgeClient | None = None
    # Injected by the runner so the engine stays decoupled from Docker.
    logs_provider: Callable[[str, int | None], str] | None = None
    running_provider: Callable[[], None] | None = None

    # --- variables / templating -------------------------------------------

    def namespace(self) -> dict:
        return {
            "target": self.target,
            "server": {"host": self.srv_name, "port": 25565},
            "client": {"game_dir": str(self.game_dir)},
            "modpack": self.modpack_name,
            "modpack_dir": f"automodpack/modpacks/{self.modpack_name}",
            "marker": str(self.marker_rel),
            **self.vars,
        }

    def resolve(self, value: Any) -> Any:
        """Recursively expand ``${var}`` / ``${var.attr}`` in strings, lists, dicts."""
        if isinstance(value, str):
            ns = self.namespace()
            return _VAR.sub(lambda m: str(_lookup(ns, m.group(1).strip())), value)
        if isinstance(value, list):
            return [self.resolve(v) for v in value]
        if isinstance(value, dict):
            return {k: self.resolve(v) for k, v in value.items()}
        return value

    # --- bridge / containers ---------------------------------------------

    def gui(self, timeout: float = 30) -> dict:
        self.assert_client_running()
        if self.bridge is None:
            raise RuntimeError("bridge not ready (run wait_bridge first)")
        return self.bridge.gui(timeout=timeout)

    def assert_client_running(self) -> None:
        if self.running_provider is not None:
            self.running_provider()

    def container_logs(self, which: str = "client", tail: int | None = None) -> str:
        if self.logs_provider is None:
            return ""
        return self.logs_provider(which, tail)

    def path(self, rel: str) -> Path:
        return self.game_dir / self.resolve(str(rel))


def _lookup(ns: dict, dotted: str) -> Any:
    parts = dotted.split(".")
    if parts[0] not in ns:
        raise KeyError(f"unknown template variable: ${{{dotted}}}")
    cur: Any = ns[parts[0]]
    for part in parts[1:]:
        cur = cur.get(part) if isinstance(cur, dict) else getattr(cur, part, None)
    return "" if cur is None else cur


__all__ = ["Context", "ClientExited"]
