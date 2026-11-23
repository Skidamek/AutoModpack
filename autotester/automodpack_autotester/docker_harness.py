"""Low-level Docker plumbing shared by every platform: containers, networks, volumes, log/exec helpers.

The daemon connection opens on first use and never at import time, so importing a platform module (or ``plan``)
works without Docker and a platform can supply its own client.
"""
from __future__ import annotations

import json
import os
import random
import sys
import tempfile
import time
from functools import cache
from pathlib import Path

import docker as docker_py

from .runtime.base import ExecResult, RuntimeState


def _jitter_sleep(base, fraction=0.2):
    time.sleep(random.uniform(base * (1 - fraction), base * (1 + fraction)))


# Autotuned TCP buffers otherwise cap one connection near the kernel's 4 MiB tcp_wmem maximum, so a
# delay-shaped run measures the kernel default instead of the pipeline window it exists to measure.
# These keys are network-namespace scoped: Docker accepts them at container creation only, and a later
# sysctl write from inside the container is refused as read-only.
SHAPED_TCP_SYSCTLS = {
    "net.ipv4.tcp_wmem": "4096 65536 33554432",
    "net.ipv4.tcp_rmem": "4096 131072 33554432",
}


def shaped_tcp_sysctls(netem, server_netem):
    """The buffer ceilings for this run, or None when they would only hurt.

    A pure delay shape needs room for one bandwidth-delay product in flight, and the kernel's
    4 MiB autotuning ceiling caps the window below it. Behind a rate cap the pipe is bounded by
    the rate anyway, and the big buffers just hold a standing queue the tail of the transfer
    must drain, so they stay off there.
    """
    tokens = list(netem or []) + list(server_netem or [])
    if "delay" not in tokens or "rate" in tokens:
        return None
    return dict(SHAPED_TCP_SYSCTLS)


def uid() -> int:
    return int(os.environ.get("AUTOTEST_DOCKER_UID", os.getuid()))


def gid() -> int:
    return int(os.environ.get("AUTOTEST_DOCKER_GID", os.getgid()))


@cache
def shared() -> "Docker":
    """The controller's own daemon connection: the Linux host's daemon, or the Docker Desktop engine."""
    return Docker()


def prepare_client_environment() -> None:
    """Keep anonymous pulls off Docker Desktop's credential helper on Windows.

    The helper needs an interactive logon session, which an SSH-driven controller does not have,
    so a public pull fails with ``A specified logon session does not exist``. A private config with
    an empty ``auths`` map keeps the pull on the daemon's own path. The same environment reaches the
    ``docker`` CLI a seed shells out to.
    """
    if not sys.platform.startswith("win"):
        return
    config_dir = Path(tempfile.gettempdir()) / "automodpack-autotest-docker"
    config_dir.mkdir(parents=True, exist_ok=True)
    config = config_dir / "config.json"
    if not config.is_file():
        config.write_text(json.dumps({"auths": {}}), encoding="utf-8")
    os.environ["DOCKER_CONFIG"] = str(config_dir)


def daemon_prerequisite() -> tuple[str, bool, str]:
    """The prerequisite every platform shares: a reachable daemon, with the engine it reports."""
    prepare_client_environment()
    try:
        version = docker_py.from_env(timeout=10).version()
        return "docker", True, f"{version.get('Os', '?')}/{version.get('Arch', '?')} {version.get('Version', '?')}"
    except Exception as error:
        return "docker", False, str(error)


class Docker:
    """One daemon plus the container/network/volume verbs every platform shares."""

    def __init__(self, client: docker_py.DockerClient | None = None):
        self._client = client

    @property
    def client(self) -> docker_py.DockerClient:
        if self._client is None:
            prepare_client_environment()
            self._client = docker_py.from_env()
        return self._client

    def container(self, name: str):
        return self.client.containers.get(name)

    def image_id(self, image: str) -> str:
        try:
            return self.client.images.get(image).id
        except docker_py.errors.ImageNotFound:
            return image

    def state(self, name: str) -> RuntimeState:
        state = self.container(name).attrs.get("State", {})
        return RuntimeState(bool(state.get("Running", False)), state.get("ExitCode"))

    def exit_code(self, name: str) -> int | None:
        try:
            return self.state(name).exit_code
        except docker_py.errors.NotFound:
            return None

    def logs(self, name: str, tail: int | None = None) -> str:
        try:
            kwargs = {"tail": tail} if tail is not None else {}
            return self.container(name).logs(**kwargs).decode("utf-8", errors="replace")
        except docker_py.errors.NotFound:
            return ""

    def exec(self, name: str, argv: list[str], *, user: str | None = None) -> ExecResult:
        result = self.container(name).exec_run(argv, user=user)
        output = result.output
        if isinstance(output, bytes):
            output = output.decode("utf-8", errors="replace")
        return ExecResult(int(result.exit_code), str(output or ""))

    def restart(self, name: str, timeout: float = 60) -> None:
        self.container(name).restart(timeout=timeout)

    def assert_running(self, name: str) -> None:
        container = self.container(name)
        container.reload()
        state = container.attrs.get("State", {})
        if not state.get("Running", False):
            raise RuntimeError(f"Container {name} exited (code={state.get('ExitCode', -1)}, error={state.get('Error', '')})")

    def wait_for_log(self, name: str, needle: str, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if needle in self.logs(name, tail=200):
                return
            self.assert_running(name)
            _jitter_sleep(2)
        raise TimeoutError(f"Timeout waiting for {needle!r} in {name}")

    def wait_exited(self, name: str, timeout: float) -> None:
        container = self.container(name)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            container.reload()
            if not container.attrs.get("State", {}).get("Running", False):
                return
            _jitter_sleep(1)
        raise TimeoutError(f"Timeout waiting for {name} to exit")

    def remove(self, name: str) -> None:
        try:
            self.container(name).remove(force=True)
        except docker_py.errors.NotFound:
            pass

    def ensure_network(self, name: str, labels: dict[str, str] | None = None) -> None:
        try:
            self.client.networks.get(name)
        except docker_py.errors.NotFound:
            self.client.networks.create(name, check_duplicate=True, labels=labels or {})

    def remove_network(self, name: str) -> None:
        try:
            self.client.networks.get(name).remove()
        except docker_py.errors.NotFound:
            pass

    def ensure_volume(self, name: str) -> None:
        try:
            self.client.volumes.get(name)
        except docker_py.errors.NotFound:
            self.client.volumes.create(name)

    def remove_volume(self, name: str) -> None:
        try:
            self.client.volumes.get(name).remove(force=True)
        except docker_py.errors.NotFound:
            pass

    def run(self, name: str, *, image: str, network: str, env: dict[str, str], mounts=(), command: list[str] | None = None, user: str | None = None, entrypoint: list[str] | None = None, aliases: list[str] | None = None, labels: dict[str, str] | None = None, cap_add=None, sysctls: dict[str, str] | None = None, ports: dict | None = None) -> None:
        """Create and start one container; ``network="host"`` is a namespace, not a user-defined network."""
        kwargs = dict(
            image=image, detach=True, name=name,
            environment=dict(env), mounts=self._mounts(mounts), command=command, user=user, labels=labels or {},
        )
        if cap_add:
            kwargs["cap_add"] = list(cap_add)
        if sysctls:
            kwargs["sysctls"] = dict(sysctls)
        if ports:
            kwargs["ports"] = dict(ports)
        # Server and client share the host's network namespace, so the client reaches the server on localhost. This is
        # the only topology a --network-host-only sandbox allows.
        if network == "host":
            kwargs["network_mode"] = "host"
        else:
            kwargs["network"] = network
            if aliases:
                # A stable DNS name alongside the random container name, so scenarios can advertise an endpoint whose
                # hostname does not change per case.
                kwargs["networking_config"] = {network: docker_py.types.EndpointConfig(self.client.api._version, aliases=list(aliases))}
        if entrypoint is not None:
            kwargs["entrypoint"] = entrypoint
        self.client.containers.run(**kwargs)

    @staticmethod
    def _mounts(mounts) -> list[docker_py.types.Mount]:
        """A Path or an absolute string names a host path to bind; anything else is a named volume (the server cache)."""
        resolved = []
        for source, target, read_only in mounts:
            source_text = str(source)
            kind = "bind" if isinstance(source, Path) or os.path.isabs(source_text) else "volume"
            resolved.append(docker_py.types.Mount(target=target, source=source_text, type=kind, read_only=bool(read_only)))
        return resolved


__all__ = [
    "Docker",
    "SHAPED_TCP_SYSCTLS",
    "daemon_prerequisite",
    "gid",
    "shared",
    "shaped_tcp_sysctls",
    "uid",
]
