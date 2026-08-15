from __future__ import annotations

import subprocess
import sys
import tempfile
from pathlib import Path

import docker as docker_py
from filelock import FileLock, Timeout


SCOPE_LABEL = "dev.automodpack.autotester.scope"
_LOCK_ROOT = Path(tempfile.gettempdir()) / "automodpack-autotester-runs"


def resource_labels(scope: str) -> dict[str, str]:
    return {SCOPE_LABEL: scope}


def cleanup_scope(scope: str, client=None) -> None:
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
    client = client or docker_py.from_env()
    scopes = {
        labels[SCOPE_LABEL]
        for resource in [*client.containers.list(all=True, filters={"label": SCOPE_LABEL}), *client.networks.list(filters={"label": SCOPE_LABEL})]
        if (labels := resource.attrs.get("Config", {}).get("Labels") or resource.attrs.get("Labels") or {}).get(SCOPE_LABEL)
    }
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
        if self.lock.is_locked:
            self.lock.release()
        try:
            self.process.wait(timeout=30)
        except subprocess.TimeoutExpired:
            self.process.terminate()


def _lock_path(scope: str) -> str:
    return str(_LOCK_ROOT / f"{scope}.lock")


def _watch(scope: str) -> None:
    lock = FileLock(_lock_path(scope))
    with lock:
        cleanup_scope(scope)


if __name__ == "__main__":
    if len(sys.argv) != 3 or sys.argv[1] != "watch":
        raise SystemExit("usage: python -m automodpack_autotester.supervisor watch SCOPE")
    _watch(sys.argv[2])
