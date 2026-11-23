"""Host-path Minecraft server seed. itzg fills the cache once; cases copy from it."""
from __future__ import annotations

import shutil
import subprocess
import time
from pathlib import Path

from ..config import resolve_settings_path
from ..runtime.base import ServerSpec
from filelock import FileLock, Timeout


def cache_dir(settings: dict, target_id: str) -> Path:
    root = resolve_settings_path(settings, "serverCacheDir", "autotester/.server-cache")
    return root / target_id


def launch_jar(root: Path) -> Path:
    for name in ("fabric-server-launch.jar", "run.jar", "server.jar"):
        path = root / name
        if path.is_file():
            return path
    matches = sorted(root.glob("fabric-server*.jar")) + sorted(root.glob("neoforge-*-server.jar")) + sorted(p for p in root.glob("*forge*.jar") if "fabric-server" not in p.name)
    if matches:
        return matches[0]
    raise RuntimeError(f"no Minecraft server launch jar under {root}")


def _has_launch_jar(root: Path) -> bool:
    try:
        launch_jar(root)
        return True
    except RuntimeError:
        return False


def _itzg_seed(root: Path, spec: ServerSpec, timeout: float) -> None:
    if not shutil.which("docker"):
        raise RuntimeError(f"no Minecraft server launch jar under {root} and docker is not on PATH to seed it")
    name = f"amp-seed-{root.name}"[:63]
    env_args = []
    for key, value in spec.env.items():
        env_args.extend(["-e", f"{key}={value}"])
    argv = ["docker", "run", "--name", name, "-v", f"{root}:/data", *env_args, spec.image]
    try:
        subprocess.run(["docker", "rm", "-f", name], check=False, capture_output=True)
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            line = proc.stdout.readline() if proc.stdout else ""
            if line:
                if "Done (" in line or 'For help, type "help"' in line:
                    break
            elif proc.poll() is not None:
                break
            time.sleep(0.1)
        subprocess.run(["docker", "stop", "-t", "5", name], check=False, capture_output=True)
        proc.wait(timeout=20)
    finally:
        subprocess.run(["docker", "rm", "-f", name], check=False, capture_output=True)
    launch_jar(root)


def _wipe_world(root: Path) -> None:
    for name in ("world", "logs", "crash-reports"):
        path = root / name
        if path.is_dir():
            shutil.rmtree(path)
        elif path.is_file():
            path.unlink()


def _merge_tree(src: Path, dest: Path) -> None:
    """Copy ``src`` into ``dest``. Files already in ``dest`` win, so a staged automodpack.jar stays."""
    dest.mkdir(parents=True, exist_ok=True)
    for child in src.iterdir():
        target = dest / child.name
        if child.is_dir():
            _merge_tree(child, target)
        elif not target.exists():
            shutil.copy2(child, target, follow_symlinks=False)


def materialize(dest: Path, spec: ServerSpec, *, target_id: str, settings: dict, timeout: float = 300) -> Path:
    """Put a launchable server tree at ``dest`` without deleting files already staged there."""
    cache = cache_dir(settings, target_id)
    cache.parent.mkdir(parents=True, exist_ok=True)
    lock = FileLock(str(cache) + ".lock")
    try:
        lock.acquire(timeout=600)
    except Timeout as error:
        raise RuntimeError(f"server seed cache for {target_id} is locked") from error
    try:
        if not _has_launch_jar(cache):
            cache.mkdir(parents=True, exist_ok=True)
            _itzg_seed(cache, spec, timeout)
        _merge_tree(cache, dest)
        _wipe_world(dest)
        return launch_jar(dest)
    finally:
        lock.release()
