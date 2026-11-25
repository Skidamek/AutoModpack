"""Configuration, target resolution, shared cache paths, and memoized artifact inspection."""
from __future__ import annotations

import hashlib
import io
import json
import os
import tomllib
import zipfile
from dataclasses import dataclass
from functools import lru_cache
from pathlib import Path

import yaml


def _find_root() -> Path:
    cwd = Path.cwd().resolve()
    for p in (cwd, cwd / "autotester", cwd.parent / "autotester", Path(__file__).resolve().parent.parent):
        if (p / "settings.yaml").is_file():
            return p
    raise FileNotFoundError("settings.yaml not found - run from project root or autotester/")


ROOT = _find_root()
REPO_ROOT = ROOT.parent


def shared_cache_root() -> Path:
    """Checkout-independent cache shared across worktrees (~/.cache/automodpack-autotest)."""
    override = os.environ.get("AUTOMODPACK_AUTOTEST_CACHE")
    if override:
        return Path(override).expanduser().resolve()
    xdg = os.environ.get("XDG_CACHE_HOME")
    base = Path(xdg).expanduser() if xdg else Path.home() / ".cache"
    return (base / "automodpack-autotest").resolve()


def resolve_settings_path(settings: dict, key: str, default: str) -> Path:
    path = Path(str((settings.get("paths") or {}).get(key, default))).expanduser()
    return (REPO_ROOT / path).resolve() if not path.is_absolute() else path.resolve()


@lru_cache(maxsize=1)
def checkout_tag() -> str:
    return hashlib.sha256(str(REPO_ROOT.resolve()).encode("utf-8")).hexdigest()[:8]


def load_yaml(path: Path) -> dict:
    with path.open("r", encoding="utf-8") as f:
        return yaml.safe_load(f) or {}


def load_settings() -> dict:
    return load_yaml(ROOT / "settings.yaml")


@lru_cache(maxsize=1)
def load_stonecutter_properties() -> dict:
    with (REPO_ROOT / "stonecutter.properties.toml").open("rb") as f:
        return tomllib.load(f)


@dataclass(frozen=True)
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
    targets: dict[str, Target] = {}
    for item in raw.get("targets", []):
        target_id = item["id"]
        version, default_loader = target_id.rsplit("-", 1)
        loader = item.get("loader", default_loader)
        minecraft = item.get("minecraft", stonecutter[version]["deps"]["minecraft"])
        loader_deps = stonecutter.get(target_id, {}).get("deps", {})
        forge_coord = loader_deps.get("forge")
        forge_version = forge_coord.removeprefix(f"{minecraft}-") if forge_coord else None
        targets[target_id] = Target(
            id=target_id,
            minecraft=minecraft,
            loader=loader,
            java=int(item.get("java", defaults.get("java", 21))),
            fabric_loader=item.get("fabricLoader", defaults.get("fabricLoader", stonecutter["fabric"]["deps"]["fabric-loader"])),
            forge_version=item.get("forgeVersion", defaults.get("forgeVersion", forge_version)),
            neoforge_version=item.get("neoforgeVersion", defaults.get("neoforgeVersion", loader_deps.get("neoforge"))),
            artifact_pattern=str(item.get("artifactPattern", defaults.get("artifactPattern", "automodpack-*.jar"))),
        )
    return targets


@lru_cache(maxsize=16)
def _verify_autotest_jar_cached(path_str: str, mtime_ns: int, size: int) -> bool:
    """Memoized check that an artifact jar contains AutoTestBridge (decompresses zstd solid once per build)."""
    import zstandard

    with zipfile.ZipFile(path_str) as jar:
        names = set(jar.namelist())
        if any(n.startswith("pl/skidam/automodpack/client/autotest/") and n.endswith(".class") for n in names):
            return True
        if "impl/manifest.json" not in names or "impl/all.zst" not in names:
            return False
        manifest = json.loads(jar.read("impl/manifest.json"))
        solid = zstandard.decompress(jar.read("impl/all.zst"))
    for entry in manifest["impls"]:
        chunk = solid[entry["offset"] : entry["offset"] + entry["length"]]
        with zipfile.ZipFile(io.BytesIO(chunk)) as impl:
            if any(n.startswith("pl/skidam/automodpack/client/autotest/") and n.endswith(".class") for n in impl.namelist()):
                return True
    return False


def resolve_artifact(target: Target, artifact_dir: Path) -> Path:
    pattern = target.artifact_pattern.format(id=target.id, minecraft=target.minecraft, loader=target.loader)
    matches = sorted(artifact_dir.glob(pattern))
    if not matches:
        raise FileNotFoundError(f"No artifact for {target.id} matching {pattern!r} in {artifact_dir}")
    if len(matches) != 1:
        raise RuntimeError(f"Ambiguous artifacts for {target.id} matching {pattern!r}: {', '.join(p.name for p in matches)}")
    artifact = matches[0].resolve()
    st = artifact.stat()
    if not _verify_autotest_jar_cached(str(artifact), st.st_mtime_ns, st.st_size):
        raise RuntimeError(f"Artifact {artifact} has no AutoTestBridge classes; rebuild with -Pautomodpack.autotest")
    return artifact


def default_jobs(settings: dict) -> int:
    configured = int((settings.get("run") or {}).get("jobs", 0) or 0)
    if configured > 0:
        return configured
    cpus = os.cpu_count() or 2
    return max(1, min(cpus // 2, 4))
