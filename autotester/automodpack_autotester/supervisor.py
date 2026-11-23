"""Run scope: crash cleanup for this run's containers and case children.

A detached watcher holds the scope lock. Whenever the tester process disappears without
cleaning up - Ctrl-C, a kill, a crash - the lock is released and the watcher removes the
containers labelled with the scope and kills the case children recorded in its pid registry.
"""
from __future__ import annotations

import os
import signal
import subprocess
import sys
import tempfile
from pathlib import Path

import docker as docker_py
from filelock import FileLock, Timeout


SCOPE_LABEL = "dev.automodpack.autotester.scope"
CHILD_PIDS_ENV = "AMP_AUTOTEST_CHILD_PIDS"
_LOCK_ROOT = Path(tempfile.gettempdir()) / "automodpack-autotester-runs"


def resource_labels(scope: str) -> dict[str, str]:
    return {SCOPE_LABEL: scope}


def child_pid_file(scope: str) -> Path:
    return _LOCK_ROOT / f"{scope}.pids"


def process_start_time(pid: int) -> str:
    """A token that changes when the kernel recycles ``pid``, or ``""`` where it cannot be read.

    Linux exposes it as field 22 of ``/proc/<pid>/stat``. Windows and macOS have no cheap
    equivalent here; their registries rely on the reap-on-exit pruning instead.
    """
    try:
        stat = Path(f"/proc/{pid}/stat").read_text(encoding="utf-8")
    except OSError:
        return ""
    # The command name may contain spaces and parentheses, so the fields start after the last ')'.
    fields = stat.rsplit(")", 1)[-1].split()
    return fields[19] if len(fields) > 19 else ""


def _append_child(entry: str) -> None:
    path = os.environ.get(CHILD_PIDS_ENV)
    if not path:
        return
    with open(path, "a", encoding="utf-8") as registry:
        registry.write(f"{entry}\n")


def register_child(pid: int) -> None:
    """Record a spawned case child so the run watcher can reap it if this process dies."""
    _append_child(f"+{pid} {process_start_time(pid)}")


def unregister_child(pid: int) -> None:
    """Retire a reaped child, so a normal run never leaves a pid for a later run to kill."""
    _append_child(f"-{pid}")


def kill_tree(pid: int) -> None:
    """Kill one child and everything it spawned on this kernel."""
    if sys.platform.startswith("win"):
        subprocess.run(["taskkill", "/PID", str(pid), "/T", "/F"], capture_output=True, check=False)
        return
    try:
        os.killpg(pid, signal.SIGKILL)
    except ProcessLookupError:
        pass


def _live_children(scope: str) -> dict[int, str]:
    """The children still alive when the tester died: the last entry per pid wins."""
    try:
        text = child_pid_file(scope).read_text(encoding="utf-8")
    except OSError:
        return {}
    live: dict[int, str] = {}
    for line in text.splitlines():
        entry = line.split()
        if not entry or not entry[0][1:].isdigit():
            continue
        pid = int(entry[0][1:])
        if entry[0].startswith("-"):
            live.pop(pid, None)
        else:
            live[pid] = entry[1] if len(entry) > 1 else ""
    return live


def cleanup_scope(scope: str, client=None) -> None:
    for pid, recorded_start in _live_children(scope).items():
        current_start = process_start_time(pid)
        if recorded_start and current_start and recorded_start != current_start:
            continue  # the kernel recycled this number; it is not our child
        kill_tree(pid)
    child_pid_file(scope).unlink(missing_ok=True)
    client = client or docker_py.from_env()
    filters = {"label": f"{SCOPE_LABEL}={scope}"}
    for container in client.containers.list(all=True, filters=filters):
        try:
            container.remove(force=True)
        except Exception:
            pass
    for network in client.networks.list(filters=filters):
        try:
            network.remove()
        except Exception:
            pass


def reap_orphaned_scopes(client=None) -> None:
    """Clean every scope whose owner is gone, before this run starts competing for the same names."""
    client = client or docker_py.from_env()
    scopes = {
        labels[SCOPE_LABEL]
        for resource in [*client.containers.list(all=True, filters={"label": SCOPE_LABEL}), *client.networks.list(filters={"label": SCOPE_LABEL})]
        if (labels := resource.attrs.get("Config", {}).get("Labels") or resource.attrs.get("Labels") or {}).get(SCOPE_LABEL)
    }
    scopes.update(path.stem for path in _LOCK_ROOT.glob("*.pids"))
    for scope in scopes:
        lock = FileLock(_lock_path(scope))
        try:
            lock.acquire(timeout=0)
        except Timeout:
            continue
        try:
            cleanup_scope(scope, client)
        finally:
            lock.release()


class RunSupervisor:
    """Hold the run lock while a detached watcher owns crash cleanup."""

    def __init__(self, scope: str):
        _LOCK_ROOT.mkdir(parents=True, exist_ok=True)
        self.scope = scope
        self.children = child_pid_file(scope)
        self.children.unlink(missing_ok=True)
        os.environ[CHILD_PIDS_ENV] = str(self.children)
        self.lock = FileLock(_lock_path(scope))
        self.lock.acquire()
        self.process = subprocess.Popen(
            [sys.executable, "-m", "automodpack_autotester.supervisor", "watch", scope],
            stdin=subprocess.DEVNULL,
            stdout=subprocess.DEVNULL,
            stderr=subprocess.DEVNULL,
            start_new_session=True,
            close_fds=True,
        )

    def close(self) -> None:
        """Normal end: every case child is already stopped, so the watcher has nothing to reap."""
        self.children.unlink(missing_ok=True)
        self._release()

    def abandon(self) -> None:
        """Interrupted end: keep the registry, the waiting watcher kills whatever is still running."""
        self._release()

    def _release(self) -> None:
        if self.lock.is_locked:
            self.lock.release()
        try:
            self.process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.process.terminate()


def _lock_path(scope: str) -> str:
    return str(_LOCK_ROOT / f"{scope}.lock")


def server_cache_lock(volume: str) -> FileLock:
    """Advisory lock serializing concurrent runs that would share one server cache volume."""
    _LOCK_ROOT.mkdir(parents=True, exist_ok=True)
    return FileLock(_LOCK_ROOT / f"cache-{volume}.lock")


def _watch(scope: str) -> None:
    lock = FileLock(_lock_path(scope))
    with lock:
        cleanup_scope(scope)


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] != "watch":
        raise SystemExit("usage: python -m automodpack_autotester.supervisor watch SCOPE")
    _watch(sys.argv[2])
