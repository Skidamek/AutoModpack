from __future__ import annotations

from copy import deepcopy
from dataclasses import dataclass, field
from functools import lru_cache
from pathlib import Path
import hashlib
import tomllib

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


@lru_cache(maxsize=1)
def checkout_tag() -> str:
    """Short stable tag of this checkout, so concurrent checkouts never share named docker resources."""
    return hashlib.sha256(str(REPO_ROOT.resolve()).encode()).hexdigest()[:8]


def server_cache_volume(target_id: str, prefix: str) -> str:
    """Per-checkout server cache volume: reuses the modpack cache within one checkout, never across two."""
    return f"{prefix}-{checkout_tag()}-{target_id}"

# Paths owned by a client generation reset. Connection/trust data and ordinary
# game files are deliberately outside this set.
CLIENT_GENERATION_STATE_PATHS = (
    "history",
    "overlays",
    "baselines",
    "generated-copies",
    "active",
    "incoming",
    "backup",
    "active-state.json",
    "update-transaction.json",
    "repair.json",
    "preservation",
    "selections.json",
    "restart-state.json",
    "incoming-manifest.json.temp",
    "helper",
)


def load_yaml(path: Path) -> dict:
    with path.open() as f:
        return yaml.safe_load(f) or {}


def load_settings() -> dict:
    return load_yaml(ROOT / "settings.yaml")


@lru_cache(maxsize=1)
def load_stonecutter_properties() -> dict:
    with (REPO_ROOT / "stonecutter.properties.toml").open("rb") as f:
        return tomllib.load(f)


@dataclass
class Target:
    id: str
    minecraft: str
    loader: str
    java: int
    fabric_loader: str | None = None
    forge_version: str | None = None
    neoforge_version: str | None = None
    artifact_pattern: str = "automodpack-*.jar"


def load_targets() -> dict[str, Target]:
    raw = load_yaml(ROOT / "targets.yaml")
    defaults = raw.get("defaults", {})
    stonecutter = load_stonecutter_properties()
    targets = []
    for item in raw.get("targets", []):
        target_id = item["id"]
        version, default_loader = target_id.rsplit("-", 1)
        loader = item.get("loader", default_loader)
        minecraft = item.get("minecraft", stonecutter[version]["deps"]["minecraft"])
        loader_dependencies = stonecutter.get(target_id, {}).get("deps", {})
        forge_coordinate = loader_dependencies.get("forge")
        forge_version = forge_coordinate.removeprefix(f"{minecraft}-") if forge_coordinate else None

        targets.append(
            Target(
                id=target_id,
                minecraft=minecraft,
                loader=loader,
                java=item.get("java", defaults.get("java", 21)),
                fabric_loader=item.get(
                    "fabricLoader",
                    defaults.get("fabricLoader", stonecutter["fabric"]["deps"]["fabric-loader"]),
                ),
                forge_version=item.get("forgeVersion", defaults.get("forgeVersion", forge_version)),
                neoforge_version=item.get(
                    "neoforgeVersion",
                    defaults.get("neoforgeVersion", loader_dependencies.get("neoforge")),
                ),
                artifact_pattern=item.get(
                    "artifactPattern",
                    defaults.get("artifactPattern", "automodpack-*.jar"),
                ),
            )
        )
    return {t.id: t for t in targets}


def load_scenarios() -> dict[str, dict]:
    return {
        f.stem: load_yaml(f)
        for f in sorted((ROOT / "scenarios").glob("*.yaml"))
        if not f.name.startswith("_")
    }


def connection_path_variants(scenario: dict) -> list[dict]:
    """Expand a scenario's declared connection-path matrix into case variants."""
    paths = scenario.get("connectionPaths")
    if paths is None:
        return [scenario]
    variants = []
    for path in paths:
        variant = deepcopy(scenario)
        mode = str(path["mode"]).upper()
        variant["id"] = f"{scenario['id']}-{mode.lower()}"
        variant["connectionPath"] = deepcopy(path)
        variants.append(variant)
    return variants


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


def _fill_unit(name: str, size_bytes: int) -> str:
    """The one fill shape both the streamed writer and the in-memory builder spell: ``name:size_bytes\n`` repeated."""
    return f"{name.encode('ascii', 'backslashreplace').decode('ascii')}:{size_bytes}\n"


def generated_content(path: str, size_bytes: int) -> str:
    """Deterministic ASCII fill of exactly ``size_bytes`` bytes for a hosted fixture file."""
    if not isinstance(size_bytes, int) or isinstance(size_bytes, bool) or size_bytes < 0:
        raise ValueError(f"sizeBytes must be a non-negative integer, got {size_bytes!r}")
    unit = _fill_unit(path, size_bytes)
    return (unit * (size_bytes // len(unit) + 1))[:size_bytes]


def write_generated(path: Path, name: str, size_bytes: int) -> None:
    """Streams ``generated_content`` to disk in chunks: gigabyte fixtures never sit in RAM."""
    unit = _fill_unit(name, size_bytes)
    with open(path, "wb") as handle:
        written = 0
        while written < size_bytes:
            chunk = unit * min(len(unit), (size_bytes - written) // len(unit) + 1)
            chunk = chunk[: size_bytes - written]
            handle.write(chunk.encode("utf-8"))
            written += len(chunk)


@dataclass(frozen=True)
class HostedFile:
    """One hosted fixture: literal content, or a deterministic fill of ``size_bytes`` bytes."""

    path: Path
    content: str | None = None
    size_bytes: int | None = None


@dataclass(frozen=True)
class ServerFiles:
    """The modpack a scenario hosts on the server, parsed from ``serverFiles``."""

    modpack_name: str
    marker: Path
    files: list[HostedFile] = field(default_factory=list)


def parse_server_files(scenario: dict) -> ServerFiles:
    sf = scenario.get("serverFiles", {}) or {}
    return ServerFiles(
        modpack_name=str(sf.get("modpackName", "amp-autotest")),
        marker=Path(str(sf.get("marker", "config/amp-autotest-marker.json"))),
        files=[HostedFile(
            Path(str(f["path"])),
            content=None if "sizeBytes" in f else str(f.get("content", "")),
            size_bytes=f["sizeBytes"] if "sizeBytes" in f else None,
        ) for f in (sf.get("files") or [])] + expand_generated(sf.get("generated")),
    )


def expand_generated(declarations) -> list[HostedFile]:
    """Expands ``generated`` declarations into their hosted files: ``pattern`` numbered ``{n}`` from ``first``,
    each file a constant ``sizeBytes`` or the arithmetic ``sizeFrom + sizeStep * k`` wrapped at ``sizeModulus``
    when given - the wrap is how an edge run spells one repeating ladder of near-chunk sizes."""
    expanded = []
    for index, declaration in enumerate(declarations or []):
        where = f"serverFiles.generated[{index}]"
        if not isinstance(declaration, dict):
            raise ValueError(f"{where}: expected a mapping")
        try:
            pattern, count = str(declaration["pattern"]), declaration["count"]
        except KeyError as missing:
            raise ValueError(f"{where}: missing {missing.args[0]}") from None
        if "{n" not in pattern:
            raise ValueError(f"{where}.pattern: expected a numbering field like {{n}}, got {pattern!r}")
        first = declaration.get("first", 1)
        if not isinstance(first, int) or isinstance(first, bool) or first < 0:
            raise ValueError(f"{where}.first: expected a non-negative integer, got {first!r}")
        if not isinstance(count, int) or isinstance(count, bool) or count <= 0:
            raise ValueError(f"{where}.count: expected a positive integer, got {count!r}")
        size_bytes, size_from = declaration.get("sizeBytes"), declaration.get("sizeFrom")
        if (size_bytes is None) == (size_from is None):
            raise ValueError(f"{where}: exactly one of sizeBytes or sizeFrom is required")
        size_step, modulus = declaration.get("sizeStep", 0), declaration.get("sizeModulus")
        for field, value in (("sizeBytes", size_bytes), ("sizeFrom", size_from), ("sizeStep", size_step), ("sizeModulus", modulus)):
            if value is not None and (not isinstance(value, int) or isinstance(value, bool) or value < 0):
                raise ValueError(f"{where}.{field}: expected a non-negative integer, got {value!r}")
        for k in range(count):
            size = size_bytes if size_bytes is not None else size_from + size_step * k
            if modulus:
                size %= modulus
            expanded.append(HostedFile(Path(pattern.format(n=first + k)), content=None, size_bytes=size))
    return expanded
