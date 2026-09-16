"""Low-level Docker plumbing: containers, networks, volumes, log/exec helpers."""
from __future__ import annotations

import os
import random
import time

import docker as docker_py


_docker = docker_py.from_env()


def _jitter_sleep(base, fraction=0.2):
    time.sleep(random.uniform(base * (1 - fraction), base * (1 + fraction)))


def _container(name):
    return _docker.containers.get(name)


def _container_logs(name, tail=None):
    try:
        kwargs = {}
        if tail is not None:
            kwargs["tail"] = tail
        return _container(name).logs(**kwargs).decode("utf-8", errors="replace")
    except docker_py.errors.NotFound:
        return ""


def _remove_container(name):
    try:
        _container(name).remove(force=True)
    except docker_py.errors.NotFound:
        pass


def _ensure_network(name, labels=None):
    _remove_network(name)
    _docker.networks.create(name, check_duplicate=True, labels=labels or {})


def _remove_network(name):
    try:
        _docker.networks.get(name).remove()
    except docker_py.errors.NotFound:
        pass


def _ensure_volume(name):
    _docker.volumes.create(name)


def _remove_volume(name):
    try:
        _docker.volumes.get(name).remove()
    except docker_py.errors.NotFound:
        pass


def _run_container(name, image, network, env, mounts, command=None, user=None, entrypoint=None, labels=None):
    volumes = {}
    for host, container_path, readonly in mounts:
        volumes[str(host)] = {"bind": container_path, "mode": "ro" if readonly else "rw"}
    kwargs = dict(
        image=image, detach=True, name=name,
        environment=dict(env), volumes=volumes, command=command, user=user, labels=labels or {},
    )
    # "host" is a network *mode*, not a user-defined network: server and client
    # share the host's network namespace (so the client reaches the server on
    # localhost). This is the only topology a --network-host-only sandbox allows.
    if network == "host":
        kwargs["network_mode"] = "host"
    else:
        kwargs["network"] = network
    if entrypoint is not None:
        kwargs["entrypoint"] = entrypoint
    return _docker.containers.run(**kwargs)


def _assert_running(name):
    c = _container(name)
    c.reload()
    state = c.attrs.get("State", {})
    if not state.get("Running", False):
        raise RuntimeError(
            f"Container {name} exited (code={state.get('ExitCode', -1)}, error={state.get('Error', '')})"
        )


def _inspect_container(name):
    return _container(name).attrs


def _wait_for_log(name, needle, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if needle in _container_logs(name, tail=200):
            return
        _assert_running(name)
        _jitter_sleep(2)
    raise TimeoutError(f"Timeout waiting for {needle!r} in {name}")


def _wait_exited(name, timeout):
    c = _container(name)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        c.reload()
        if not c.attrs.get("State", {}).get("Running", False):
            return
        _jitter_sleep(1)
    raise TimeoutError(f"Timeout waiting for {name} to exit")


def _uid():
    return int(os.environ.get("AUTOTEST_DOCKER_UID", os.getuid()))


def _gid():
    return int(os.environ.get("AUTOTEST_DOCKER_GID", os.getgid()))


def _exit_code(name) -> int | None:
    try:
        return _inspect_container(name).get("State", {}).get("ExitCode")
    except docker_py.errors.NotFound:
        return None


def _exec_output(result) -> str:
    output = result.output
    if isinstance(output, bytes):
        return output.decode("utf-8", errors="replace")
    return str(output or "")
