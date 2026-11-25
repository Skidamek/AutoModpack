"""Native HeadlessMC 3 binary manager, profile cache, and client launcher helpers.

Runs natively on Linux, macOS, and Windows — zero Docker required for local runs.
"""
from __future__ import annotations

import hashlib
import json
import os
import platform
import shutil
import stat
import subprocess
import sys
import urllib.request
from pathlib import Path

from filelock import FileLock

from .config import Target, shared_cache_root

DEFAULT_REPO = "https://github.com/headlesshq/headlessmc"
DEFAULT_REF = "3.0.0-RC9"

_KNOWN_SHA256 = {
    ("3.0.0-RC9", "headlessmc-launcher-linux-x64"): "1f262b3e2f35569e437635bb958ab766da70e4619530b46f23cbc69dea85c318",
    ("3.0.0-RC9", "headlessmc-launcher-linux-arm64"): "7e83c6555a072820592f312476a45a35369391df0e63092fc6b5a41f8d06fecf",
    ("3.0.0-RC9", "headlessmc-launcher-macos-arm64"): "419cc239a4313b7f8751206f1046e266f61e6b1bfb8bd195e972e710dd5ff377",
    ("3.0.0-RC9", "headlessmc-launcher-macos-x64"): "43303c41536997d0d718c81028b2c2220bc39a15e2d3de27e12d6d9c3eb3b323",
    ("3.0.0-RC9", "headlessmc-launcher-windows-x64.exe"): "2fd8cbc407c70fc32c4737affbda7578806d83a82e27b1e35da5792aabc3dbb7",
}


def hmc_asset_name(system: str | None = None, machine: str | None = None) -> str:
    sys_name = (system or sys.platform).lower()
    arch = (machine or platform.machine()).lower()
    hmc_arch = "arm64" if arch in ("aarch64", "arm64") else "x64"
    if sys_name.startswith("win"):
        return f"headlessmc-launcher-windows-{hmc_arch}.exe"
    if sys_name == "darwin":
        return f"headlessmc-launcher-macos-{hmc_arch}"
    return f"headlessmc-launcher-linux-{hmc_arch}"


def ensure_hmc_binary(settings: dict) -> Path:
    """Return path to the native HeadlessMC 3 binary, downloading it once into the shared cache."""
    hmc_cfg = settings.get("headlessmc") or {}
    if sys.platform.startswith("win"):
        override = (settings.get("windows") or {}).get("headlessmc", {}).get("path")
        if override and Path(str(override)).is_file():
            return Path(str(override))
    repo = str(hmc_cfg.get("repo", DEFAULT_REPO)).rstrip("/").removesuffix(".git")
    ref = str(hmc_cfg.get("ref", DEFAULT_REF))
    asset = hmc_asset_name()
    cache_dir = shared_cache_root() / "hmc-bin" / ref
    cache_dir.mkdir(parents=True, exist_ok=True)
    binary = cache_dir / asset
    with FileLock(str(binary) + ".lock"):
        if binary.is_file() and binary.stat().st_size > 0:
            return binary
        url = f"{repo}/releases/download/{ref}/{asset}"
        tmp = binary.with_suffix(".tmp")
        with urllib.request.urlopen(url, timeout=180) as resp, tmp.open("wb") as out:
            shutil.copyfileobj(resp, out)
        expected = _KNOWN_SHA256.get((ref, asset))
        if expected:
            got = hashlib.file_digest(tmp.open("rb"), "sha256").hexdigest()
            if got != expected:
                tmp.unlink(missing_ok=True)
                raise ValueError(f"SHA-256 mismatch for {url}: expected {expected}, got {got}")
        if not sys.platform.startswith("win"):
            tmp.chmod(tmp.stat().st_mode | stat.S_IXUSR | stat.S_IXGRP | stat.S_IXOTH)
        tmp.replace(binary)
        return binary


def hmc_build(target: Target) -> str | None:
    if target.loader == "fabric":
        return target.fabric_loader
    if target.loader == "forge":
        return target.forge_version
    if target.loader == "neoforge":
        version = target.neoforge_version
        if not version:
            return None
        mc = target.minecraft
        if version.startswith(mc + "."):
            return version[len(mc) + 1 :]
        if f"1.{version}".startswith(mc + "."):
            return version[len(mc) - 1 :]
        return version
    return None


def profile_args(target: Target) -> list[str]:
    args = [target.loader, target.minecraft]
    build = hmc_build(target)
    if build:
        args.append(build)
    return args


def target_hmc_cache(target: Target) -> Path:
    return shared_cache_root() / "hmc" / target.id


def write_hmc_config(config_root: Path, mc_cache: Path, game_dir: Path, java_home: Path, *, username: str | None = None, user_uuid: str | None = None) -> None:
    cfg_dir = config_root / "headlessmc"
    cfg_dir.mkdir(parents=True, exist_ok=True)
    props = {
        "hmc.files.mc": mc_cache.as_posix(),
        "hmc.files.game": game_dir.as_posix(),
        "hmc.files.game-for-each-version": "false",
        "hmc.java.download": "false",
        "hmc.java.versions": java_home.as_posix(),
        "hmc.launcher.crash-report-watcher": "true",
        "hmc.jline.enabled": "false",
        "hmc.xvfb.check": "false",
    }
    if username:
        props["hmc.offline.username"] = username
    if user_uuid:
        props["hmc.offline.uuid"] = user_uuid
    (cfg_dir / "config.properties").write_text("".join(f"{k}={v}\n" for k, v in props.items()), encoding="utf-8")


def seed_fml_toml(game_dir: Path, target: Target) -> None:
    """Prevent Forge/NeoForge earlyWindowControl crashes in headless runs on all OSes."""
    if target.loader not in ("forge", "neoforge"):
        return
    cfg = game_dir / "config" / "fml.toml"
    if cfg.is_file():
        return
    cfg.parent.mkdir(parents=True, exist_ok=True)
    cfg.write_text("earlyWindowControl = false\n", encoding="utf-8")


def hmc_env(mc_cache: Path, java_home: Path, config_root: Path) -> dict[str, str]:
    env = dict(os.environ)
    env.pop("JAVA_TOOL_OPTIONS", None)
    env["HMC_HOME"] = str(mc_cache)
    env["JAVA_HOME"] = str(java_home)
    env["AUTOMODPACK_DATA_ROOT"] = "automodpack"
    if sys.platform.startswith("win"):
        env["APPDATA"] = str(config_root)
        env["PATH"] = f"{java_home / 'bin'};{env.get('PATH', '')}"
    else:
        env["XDG_CONFIG_HOME"] = str(config_root)
        env["HOME"] = str(config_root)
        env["LANG"] = "C.UTF-8"
        env["LC_ALL"] = "C.UTF-8"
        env["PATH"] = f"{java_home / 'bin'}:{env.get('PATH', '')}"
    return env


def ensure_profile_prepared(target: Target, settings: dict, java_home: Path, timeout: float = 300.0) -> Path:
    """Install the HeadlessMC profile for ``target`` once under a per-target FileLock."""
    mc_cache = target_hmc_cache(target)
    mc_cache.mkdir(parents=True, exist_ok=True)
    receipt = mc_cache / "prepared-profile.json"
    identity = {"target": target.id, "loader": target.loader, "minecraft": target.minecraft, "build": hmc_build(target)}
    with FileLock(str(mc_cache) + ".lock"):
        if receipt.is_file():
            try:
                if json.loads(receipt.read_text(encoding="utf-8")) == identity and (mc_cache / "versions").is_dir():
                    return mc_cache
            except (OSError, json.JSONDecodeError):
                pass
        hmc_bin = ensure_hmc_binary(settings)
        prep_cfg = mc_cache / ".prep-config"
        write_hmc_config(prep_cfg, mc_cache, mc_cache / ".prep-game", java_home)
        env = hmc_env(mc_cache, java_home, prep_cfg)
        argv = [str(hmc_bin), "version", "install", *profile_args(target)]
        res = subprocess.run(argv, cwd=str(mc_cache), env=env, capture_output=True, text=True, timeout=timeout)
        if res.returncode != 0:
            raise RuntimeError(f"HeadlessMC install failed for {target.id} (code={res.returncode}):\n{res.stdout}\n{res.stderr}")
        receipt.write_text(json.dumps(identity, indent=2), encoding="utf-8")
        return mc_cache
