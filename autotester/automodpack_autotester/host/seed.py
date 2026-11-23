"""Host-path Minecraft server seed. itzg fills the cache once; cases copy from it."""
from __future__ import annotations

import shutil
import subprocess
import sys
import time
from pathlib import Path

from ..config import resolve_settings_path
from ..runtime.base import ServerSpec
from filelock import FileLock, Timeout


_SEED_MARKER = Path(".amp-seed-complete")


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


def _loader_args_file(root: Path, spec: ServerSpec) -> Path | None:
    """The Forge/NeoForge launch arg file for this kernel, when the seed installed one.

    Those installers write a ``run.sh``/``run.bat`` that only wraps ``java @<args file>``.
    The arg file already carries the JVM options, the classpath, the main class and the
    program arguments, so the harness runs it directly and keeps owning the JVM arguments.
    """
    name = "win_args.txt" if sys.platform.startswith("win") else "unix_args.txt"
    matches = sorted(root.glob(f"libraries/**/{name}"))
    if not matches:
        return None
    wanted = str(spec.env.get("FORGE_VERSION") or spec.env.get("NEOFORGE_VERSION") or "")
    requested = [path for path in matches if wanted and wanted in path.as_posix()]
    if requested:
        matches = requested
    if len(matches) > 1:
        raise RuntimeError(f"ambiguous launch args under {root}: {[path.as_posix() for path in matches]}")
    return matches[0]


def launch_argv(root: Path, spec: ServerSpec) -> list[str]:
    """The ``java`` arguments after the executable, relative to ``root``.

    Relative paths keep the command free of the case directory's possibly-spaced path.
    """
    args_file = _loader_args_file(root, spec)
    if args_file is not None:
        return [f"@{args_file.relative_to(root).as_posix()}", "nogui"]
    return ["-jar", launch_jar(root).name, "nogui"]


def _launchable(root: Path, spec: ServerSpec) -> bool:
    """Whether a launch command for this tree can be built at all."""
    try:
        launch_argv(root, spec)
        return True
    except RuntimeError:
        return False


def _seed_complete(root: Path, spec: ServerSpec) -> bool:
    """A seed is reusable only when it finished, not merely when its launch files exist.

    The marker is written after itzg reached "Done (", so an install killed halfway
    through (interrupted seed, drained disk) reseeds instead of booting a broken server.
    """
    return (root / _SEED_MARKER).is_file() and _launchable(root, spec)


def _itzg_seed(root: Path, spec: ServerSpec, timeout: float) -> None:
    if not shutil.which("docker"):
        raise RuntimeError(f"no complete Minecraft server seed under {root} and docker is not on PATH to seed it")
    name = f"amp-seed-{root.name}"[:63]
    env_args = []
    for key, value in spec.env.items():
        env_args.extend(["-e", f"{key}={value}"])
    # The seed boots the world once, so it runs with the same heap the cases get.
    env_args.extend(["-e", f"MEMORY={spec.memory}"])
    argv = ["docker", "run", "--name", name, "-v", f"{root}:/data", *env_args, spec.image]
    booted = False
    try:
        subprocess.run(["docker", "rm", "-f", name], check=False, capture_output=True)
        proc = subprocess.Popen(argv, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            line = proc.stdout.readline() if proc.stdout else ""
            if line:
                if "Done (" in line or 'For help, type "help"' in line:
                    booted = True
                    break
            elif proc.poll() is not None:
                break
            time.sleep(0.1)
        subprocess.run(["docker", "stop", "-t", "5", name], check=False, capture_output=True)
        proc.wait(timeout=20)
    finally:
        subprocess.run(["docker", "rm", "-f", name], check=False, capture_output=True)
    if not booted:
        raise RuntimeError(f"server seed for {root.name} never reached 'Done (' within {timeout:.0f}s; its cache is unusable and will be reseeded next run")
    launch_argv(root, spec)
    (root / _SEED_MARKER).write_text("1\n", encoding="utf-8")


# The seed's own world, logs and crash reports are the seed container's first boot, not the
# case's: world generation is cheap, and merging them would mix a different level seed into a
# restart's world and resurrect files the case deleted.
_SEED_RUNTIME_DIRS = frozenset({"world", "logs", "crash-reports"})


def _merge_tree(src: Path, dest: Path, skip: frozenset[str] = frozenset()) -> None:
    """Copy ``src`` into ``dest``. Files already in ``dest`` win, so a staged automodpack.jar stays."""
    dest.mkdir(parents=True, exist_ok=True)
    for child in src.iterdir():
        if child.name in skip:
            continue
        target = dest / child.name
        if child.is_dir():
            _merge_tree(child, target)
        elif not target.exists():
            shutil.copy2(child, target, follow_symlinks=False)


def materialize(dest: Path, spec: ServerSpec, *, target_id: str, settings: dict, timeout: float = 300) -> None:
    """Put a launchable server tree at ``dest`` without deleting files already staged there."""
    cache = cache_dir(settings, target_id)
    cache.parent.mkdir(parents=True, exist_ok=True)
    lock = FileLock(str(cache) + ".lock")
    try:
        lock.acquire(timeout=600)
    except Timeout as error:
        raise RuntimeError(f"server seed cache for {target_id} is locked") from error
    try:
        if not _seed_complete(cache, spec):
            cache.mkdir(parents=True, exist_ok=True)
            _itzg_seed(cache, spec, timeout)
        _merge_tree(cache, dest, skip=_SEED_RUNTIME_DIRS)
        launch_argv(dest, spec)
    finally:
        lock.release()
