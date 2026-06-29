from __future__ import annotations

from dataclasses import dataclass, field
from functools import lru_cache
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


@lru_cache(maxsize=1)
def load_macros() -> dict:
    """Shared reusable step sequences from ``scenarios/_lib.yaml`` (if present).

    Cached: the library is static for a run and read once per process.
    """
    lib = ROOT / "scenarios" / "_lib.yaml"
    return load_yaml(lib) if lib.is_file() else {}


def scenario_matches_target(scenario: dict, target: "Target") -> bool:
    """Whether ``scenario`` is in scope for ``target``.

    A scenario can scope itself with any of these header keys; a target must pass
    every one that is present (globs allowed where noted):

      targets:   [ "1.21.1-neoforge", "neoforge-*" ]   # glob on target id
      loaders:   [ neoforge ]                            # exact loader
      minecraft: [ "1.21.1", "1.21.*" ]                  # glob on mc version

    With no scoping keys, the scenario applies to every target (current behavior).
    """
    from fnmatch import fnmatch

    def _ok(key, value) -> bool:
        raw = scenario.get(key)
        if raw is None:
            return True
        patterns = [raw] if isinstance(raw, str) else list(raw)
        return any(fnmatch(str(value), str(p)) for p in patterns)

    loaders = scenario.get("loaders")
    if loaders is not None:
        allowed = [loaders] if isinstance(loaders, str) else list(loaders)
        if target.loader not in {str(x) for x in allowed}:
            return False
    return _ok("targets", target.id) and _ok("minecraft", target.minecraft)


@dataclass(frozen=True)
class ServerFiles:
    """The modpack a scenario hosts on the server, parsed from ``serverFiles``."""

    modpack_name: str
    marker: Path
    files: list[tuple[Path, str]] = field(default_factory=list)
    expected_mods: list[str] = field(default_factory=list)


def parse_server_files(scenario: dict) -> ServerFiles:
    sf = scenario.get("serverFiles", {}) or {}
    return ServerFiles(
        modpack_name=str(sf.get("modpackName", "amp-autotest")),
        marker=Path(str(sf.get("marker", "config/amp-autotest-marker.json"))),
        files=[(Path(str(f["path"])), str(f.get("content", ""))) for f in sf.get("files", [])],
        expected_mods=[str(m) for m in sf.get("expectedMods", [])],
    )
