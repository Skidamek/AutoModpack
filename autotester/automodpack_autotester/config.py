from __future__ import annotations

from dataclasses import dataclass
from pathlib import Path

import yaml


def _find_root() -> Path:
    cwd = Path.cwd().resolve()
    for p in (cwd, cwd / "autotester", cwd.parent / "autotester"):
        if (p / "settings.yaml").is_file():
            return p
    raise FileNotFoundError(
        "settings.yaml not found — run from project root or autotester/"
    )


ROOT = _find_root()
REPO_ROOT = ROOT.parent


def load_yaml(path: Path) -> dict:
    with path.open() as f:
        return yaml.safe_load(f) or {}


def load_settings() -> dict:
    return load_yaml(ROOT / "settings.yaml")


@dataclass
class Target:
    id: str
    minecraft: str
    loader: str
    java: int
    fabric_loader: str | None = None
    forge_version: str | None = None
    neoforge_version: str | None = None


def load_targets() -> dict[str, Target]:
    raw = load_yaml(ROOT / "targets.yaml")
    d = raw.get("defaults", {})
    targets = []
    for item in raw.get("targets", []):
        targets.append(
            Target(
                id=item["id"],
                minecraft=item["minecraft"],
                loader=item["loader"],
                java=item.get("java", d.get("java", 21)),
                fabric_loader=item.get("fabricLoader", d.get("fabricLoader")),
                forge_version=item.get("forgeVersion", d.get("forgeVersion")),
                neoforge_version=item.get("neoforgeVersion", d.get("neoforgeVersion")),
            )
        )
    return {t.id: t for t in targets}


def load_scenarios() -> dict[str, dict]:
    return {
        f.stem: load_yaml(f)
        for f in sorted((ROOT / "scenarios").glob("*.yaml"))
        if not f.name.startswith("_")
    }


def load_macros() -> dict:
    """Shared reusable step sequences from ``scenarios/_lib.yaml`` (if present)."""
    lib = ROOT / "scenarios" / "_lib.yaml"
    return load_yaml(lib) if lib.is_file() else {}
