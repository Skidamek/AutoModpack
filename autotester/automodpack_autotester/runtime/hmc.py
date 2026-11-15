"""HeadlessMC profile identity, cache paths and receipts.

Platform-neutral: the paths are host paths under the case's ``out_dir`` and the
game directory. Only the image identity (Linux) or launcher identity (Windows)
differs, and the runtime supplies that.
"""
from __future__ import annotations

import json
import os
from pathlib import Path

from ..config import Target


CLIENT_DATA_ROOT = Path("automodpack/client/data")


def load_version(target: Target) -> str:
    if target.loader == "fabric":
        return getattr(target, "fabric_loader", None) or ""
    if target.loader == "forge":
        return getattr(target, "forge_version", None) or ""
    if target.loader == "neoforge":
        return getattr(target, "neoforge_version", None) or ""
    return ""


def hmc_build(target: Target) -> str:
    # HeadlessMC 3 IDs are platform/minecraft/build. Fabric's loader version is already that
    # build; Forge is stripped at Target load; NeoForge Maven versions still carry the MC
    # prefix that HMC's NeoForgeVersionService peels (21.1.259 → 259, 26.2.0.90 → 0.90).
    version = load_version(target)
    if target.loader != "neoforge" or not version:
        return version
    mc = target.minecraft
    if version.startswith(mc):
        return version[len(mc) + 1 :]
    if f"1.{version}".startswith(mc):
        return version[len(mc) - 1 :]
    return version


def cache_paths(ctx) -> tuple[Path, Path]:
    """Per-target HMC cache and the persistent shared-versions directory."""
    tid = ctx.target.id.replace(".", "_")
    hmc_cache_root = (ctx.out_dir.parent / ".hmc-cache" / tid).resolve()
    hmc_cache_root.mkdir(parents=True, exist_ok=True)
    # The client image symlinks <cache>/versions -> /work/hmc-shared-versions. Mount
    # a persistent host dir there so downloaded version jars survive between runs
    # instead of being re-fetched every time. Kept per-target - sharing one dir
    # across parallel targets would reintroduce the installer-corruption race.
    shared_versions = (ctx.out_dir.parent / ".hmc-cache" / "shared-versions" / tid).resolve()
    shared_versions.mkdir(parents=True, exist_ok=True)
    return hmc_cache_root, shared_versions


def profile_name(target: Target) -> str:
    loader_version = load_version(target)
    if target.loader == "fabric" and loader_version:
        return f"fabric-loader-{loader_version}-{target.minecraft}"
    if target.loader == "forge" and loader_version:
        return f"{target.minecraft}-forge-{loader_version}"
    if target.loader == "neoforge" and loader_version:
        return f"neoforge-{loader_version}"
    return f"{target.loader}:{target.minecraft}"


def profile_identity(ctx, image_identity: str) -> dict:
    return {
        "minecraft": ctx.target.minecraft,
        "loader": ctx.target.loader,
        "loaderVersion": load_version(ctx.target),
        "java": ctx.target.java,
        "clientImage": image_identity,
        "profile": profile_name(ctx.target),
    }


def profile_receipt(hmc_cache_root: Path) -> Path:
    return hmc_cache_root / "prepared-profile.json"


def profile_is_prepared(ctx, hmc_cache_root: Path, shared_versions: Path, identity: dict) -> bool:
    profile = profile_name(ctx.target)
    profile_json = shared_versions / profile / f"{profile}.json"
    try:
        return profile_json.is_file() and json.loads(profile_receipt(hmc_cache_root).read_text(encoding="utf-8")) == identity
    except (OSError, TypeError, ValueError, json.JSONDecodeError):
        return False


def record_prepared_profile(hmc_cache_root: Path, identity: dict) -> None:
    receipt = profile_receipt(hmc_cache_root)
    temporary = receipt.with_suffix(".tmp")
    temporary.write_text(json.dumps(identity, sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, receipt)


def launcher_meta_dir(ctx) -> Path:
    """Host dir of instance-parent files (mmc-pack.json) bind-mounted one file
    at a time onto /work/<name>, which is the game directory's parent inside
    the client. Plant writes here; the switch rewrites the same host file."""
    directory = ctx.game_dir.parent / "launcher-meta"
    directory.mkdir(parents=True, exist_ok=True)
    return directory


__all__ = [
    "CLIENT_DATA_ROOT",
    "cache_paths",
    "hmc_build",
    "launcher_meta_dir",
    "load_version",
    "profile_identity",
    "profile_is_prepared",
    "profile_name",
    "profile_receipt",
    "record_prepared_profile",
]
