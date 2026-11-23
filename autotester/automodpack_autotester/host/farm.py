"""Farm vs box: how many slots this OS runs, and which spawn strategy it uses."""
from __future__ import annotations

from dataclasses import dataclass


MODES = frozenset({"farm", "box"})
PROVIDERS = frozenset({"native", "docker"})


@dataclass(frozen=True)
class FarmConfig:
    mode: str
    slots: int
    provide: str

    def describe(self) -> str:
        return f"mode={self.mode} slots={self.slots} provide={self.provide}"


def load_farm(settings: dict | None, *, provide: str | None = None) -> FarmConfig:
    raw = (settings or {}).get("farm") or {}
    mode = str(raw.get("mode", "farm")).lower()
    if mode not in MODES:
        raise ValueError(f"farm.mode must be farm or box, got {mode!r}")
    chosen = str(provide or raw.get("provide", "native")).lower()
    if chosen not in PROVIDERS:
        raise ValueError(f"farm.provide must be native or docker, got {chosen!r}")
    slots = 1 if mode == "box" else int(raw.get("slots", 2))
    if slots < 1:
        raise ValueError("farm.slots must be >= 1")
    return FarmConfig(mode=mode, slots=slots, provide=chosen)
