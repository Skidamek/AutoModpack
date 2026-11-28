"""Direct native Minecraft server seeder with cached clean flat world and hardlink materialization.

Downloads Fabric, NeoForge, or Forge server jars directly into the shared cache — zero Docker needed.
"""
from __future__ import annotations

import json
import os
import shutil
import subprocess
import sys
import urllib.request
from pathlib import Path

from filelock import FileLock

from .cache import materialize_tree
from .config import Target, shared_cache_root
from .java import fast_jvm_flags, java_bin
from .process import ManagedProcess

_SEED_MARKER = ".amp-v2-seed-complete"
_FABRIC_META = "https://meta.fabricmc.net/v2"
_NEOFORGE_MAVEN = "https://maven.neoforged.net/releases/net/neoforged/neoforge"
_FORGE_MAVEN = "https://maven.minecraftforge.net/net/minecraftforge/forge"
_SEED_SKIP_ON_COPY = frozenset({"logs", "crash-reports", "mods", "automodpack", _SEED_MARKER})
_USER_AGENT = "automodpack-autotester"


def server_cache_dir(target: Target) -> Path:
    return shared_cache_root() / "servers" / target.id


def _urlopen(url: str, *, timeout: float):
    req = urllib.request.Request(url, headers={"User-Agent": _USER_AGENT})
    return urllib.request.urlopen(req, timeout=timeout)


def _download(url: str, dest: Path, timeout: float = 180.0) -> None:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(".tmp")
    with _urlopen(url, timeout=timeout) as resp, tmp.open("wb") as out:
        shutil.copyfileobj(resp, out)
    tmp.replace(dest)


def _latest_fabric_installer() -> str:
    with _urlopen(f"{_FABRIC_META}/versions/installer", timeout=30) as resp:
        items = json.loads(resp.read().decode("utf-8"))
    stable = [i["version"] for i in items if i.get("stable")]
    return stable[0] if stable else items[0]["version"]


def _install_server(cache: Path, target: Target, java_exe: str) -> None:
    if target.loader == "fabric":
        installer = _latest_fabric_installer()
        url = f"{_FABRIC_META}/versions/loader/{target.minecraft}/{target.fabric_loader}/{installer}/server/jar"
        _download(url, cache / "fabric-server-launch.jar")
    elif target.loader == "neoforge":
        ver = target.neoforge_version
        url = f"{_NEOFORGE_MAVEN}/{ver}/neoforge-{ver}-installer.jar"
        inst = cache / f"neoforge-{ver}-installer.jar"
        _download(url, inst)
        subprocess.run([java_exe, "-jar", str(inst), "--installServer"], cwd=str(cache), check=True, timeout=300)
        inst.unlink(missing_ok=True)
    elif target.loader == "forge":
        coord = f"{target.minecraft}-{target.forge_version}"
        url = f"{_FORGE_MAVEN}/{coord}/forge-{coord}-installer.jar"
        inst = cache / f"forge-{coord}-installer.jar"
        _download(url, inst)
        subprocess.run([java_exe, "-jar", str(inst), "--installServer"], cwd=str(cache), check=True, timeout=300)
        inst.unlink(missing_ok=True)
    else:
        raise ValueError(f"Unsupported server loader: {target.loader}")


def launch_argv(root: Path, target: Target) -> list[str]:
    """Relative JVM arguments to launch the server under ``root``."""
    args_name = "win_args.txt" if sys.platform.startswith("win") else "unix_args.txt"
    matches = sorted(root.glob(f"libraries/**/{args_name}"))
    if matches:
        wanted = str(target.neoforge_version or target.forge_version or "")
        filtered = [m for m in matches if wanted and wanted in m.as_posix()]
        chosen = filtered[0] if filtered else matches[0]
        return [f"@{chosen.relative_to(root).as_posix()}", "nogui"]
    for name in ("fabric-server-launch.jar", "run.jar", "server.jar"):
        if (root / name).is_file():
            return ["-jar", name, "nogui"]
    raise RuntimeError(f"No launchable server jar or args file in {root}")


def write_server_properties(server_dir: Path, settings: dict, *, game_port: int, rcon_port: int, rcon_password: str, bind_ip: str = "127.0.0.1") -> None:
    props = dict((settings.get("server") or {}).get("properties") or {})
    props["server-port"] = str(game_port)
    props["server-ip"] = bind_ip
    props["rcon.port"] = str(rcon_port)
    props["rcon.password"] = rcon_password
    (server_dir / "eula.txt").write_text("eula=true\n", encoding="utf-8")
    (server_dir / "server.properties").write_text("".join(f"{k}={v}\n" for k, v in props.items()), encoding="utf-8")


def _prewarm_flat_world(cache: Path, target: Target, settings: dict, java_exe: str, timeout: float = 180.0) -> None:
    """Boot the clean vanilla+loader server once to generate the flat world so every case skips worldgen."""
    write_server_properties(cache, settings, game_port=0, rcon_port=0, rcon_password="seed")
    # Disable rcon during seed boot so port 0 isn't rejected.
    props_path = cache / "server.properties"
    text = props_path.read_text(encoding="utf-8").replace("enable-rcon=true", "enable-rcon=false")
    props_path.write_text(text, encoding="utf-8")
    argv = [java_exe, *fast_jvm_flags(settings, "1G"), *launch_argv(cache, target)]
    env = dict(os.environ)
    env.pop("JAVA_TOOL_OPTIONS", None)
    sep = ";" if sys.platform.startswith("win") else ":"
    env["PATH"] = f"{Path(java_exe).parent}{sep}{env.get('PATH', '')}"
    proc = ManagedProcess(argv, cwd=cache, env=env, log_path=cache / "seed.log")
    proc.start()
    try:
        proc.wait_for_log(r'Done \(|For help, type "help"', timeout=timeout)
    finally:
        proc.kill(force=False)
        (cache / "seed.log").unlink(missing_ok=True)


def materialize_server(dest: Path, target: Target, settings: dict, timeout: float = 240.0) -> None:
    """Materialize a launchable, pre-warmed server tree into ``dest`` using hardlinks for immutable jars/libs."""
    cache = server_cache_dir(target)
    cache.parent.mkdir(parents=True, exist_ok=True)
    with FileLock(str(cache) + ".lock"):
        if not (cache / _SEED_MARKER).is_file():
            cache.mkdir(parents=True, exist_ok=True)
            java_exe = java_bin(settings, target.java)
            _install_server(cache, target, java_exe)
            _prewarm_flat_world(cache, target, settings, java_exe, timeout=timeout)
            (cache / _SEED_MARKER).write_text("1\n", encoding="utf-8")
        materialize_tree(
            cache,
            dest,
            hardlink_dirs=frozenset({"libraries", "versions", ".fabric"}),
            skip=_SEED_SKIP_ON_COPY,
        )
        launch_argv(dest, target)
