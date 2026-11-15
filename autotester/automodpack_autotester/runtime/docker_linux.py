"""Docker-on-Linux platform: today's autotester behaviour behind the runtime seam.

Every container argument here is the same value the step modules used to pass to
``docker_harness``: names, mounts, user, tini entrypoint, capabilities, network
modes and environment.
"""
from __future__ import annotations

import logging
import subprocess
from pathlib import Path

import docker as docker_py

from ..docker_harness import (
    _assert_running,
    _container,
    _container_logs,
    _docker,
    _ensure_network,
    _ensure_volume,
    _exec_output,
    _exit_code,
    _inspect_container,
    _remove_container,
    _remove_network,
    _remove_volume,
    _run_container,
    _uid,
    _gid,
    _wait_exited,
    _wait_for_log,
    shaped_tcp_sysctls,
)
from ..supervisor import resource_labels
from .base import Capabilities, ExecResult, RuntimeState, ServerSpec
from .workspace import HostPathServerWorkspace
from .hmc import (
    CLIENT_DATA_ROOT,
    cache_paths,
    hmc_build,
    launcher_meta_dir,
    profile_identity,
    profile_is_prepared,
    record_prepared_profile,
)


logger = logging.getLogger(__name__)


def _image_id(image: str) -> str:
    try:
        return _docker.images.get(image).id
    except docker_py.errors.ImageNotFound:
        return image


def _shape_egress(container_name: str, qdisc: list[str], label: str) -> None:
    """One root netem qdisc on a container's eth0; runs as root because the game user holds no
    effective capabilities.

    TSO/GSO let the stack emit super-packets the qdisc counts as one, inflating any rate
    limit several-fold. Offloads must go off on BOTH ends of the veth pair: the container
    side so the qdisc sees segmented packets, the host peer so segmenting survives the hop.
    The qdisc's limit is raised from netem's 1000-packet default (~1.5 MB): a pure delay
    shape at loopback fill rates holds several bandwidth-delay products in the queue, and
    the default tail-drops them, so TCP spends the run recovering from self-inflicted
    losses instead of filling the window the shape exists to exercise.
    No teardown counterpart exists on purpose: the container is ephemeral and is removed
    with the case, and every (re)launch re-applies the qdisc on the fresh container.
    """
    _disable_iface_offloads(container_name)
    _disable_peer_offloads_of(container_name)
    result = _container(container_name).exec_run(["tc", "qdisc", "add", "dev", "eth0", "root", "netem", "limit", "50000", *qdisc], user="root")
    output = _exec_output(result)
    if result.exit_code != 0:
        raise RuntimeError(f"{container_name} container could not apply the {label} qdisc ({result.exit_code}): {output}")
    logger.info("Applied %s qdisc to %s eth0: %s", label, container_name, " ".join(qdisc))


def _disable_iface_offloads(container_name: str) -> None:
    """Turns offloads off on a container's eth0 so the qdisc sees segmented packets; best effort by design."""
    _container(container_name).exec_run(["ethtool", "-K", "eth0", "tso", "off", "gso", "off", "gro", "off"], user="root")


def _disable_peer_offloads_of(container_name: str) -> None:
    result = _container(container_name).exec_run(["cat", "/sys/class/net/eth0/iflink"], user="root")
    peer_index = _exec_output(result).strip()
    if not peer_index.isdigit():
        logger.warning("Cannot resolve the %s eth0 host peer (iflink %r); the shaped rate may run high", container_name, peer_index)
        return
    for device in Path("/sys/class/net").glob("*"):
        try:
            if (device / "ifindex").read_text().strip() == peer_index:
                subprocess.run(["ethtool", "-K", device.name, "tso", "off", "gso", "off", "gro", "off"],
                               check=False, capture_output=True)
                return
        except OSError:
            continue
    logger.warning("No host interface carries ifindex %s; the shaped rate may run high", peer_index)


class DockerLinuxClientRuntime:
    """The Linux HeadlessMC client container."""

    capabilities = Capabilities()

    def __init__(self, ctx):
        self.name = ctx.cli_name
        self.prep_name = ctx.prep_name or ctx.cli_name.replace("-c-", "-p-", 1)

    def profile_prepared(self, ctx) -> bool:
        hmc_cache_root, shared_versions = cache_paths(ctx)
        identity = profile_identity(ctx, _image_id(ctx.client_image))
        return profile_is_prepared(ctx, hmc_cache_root, shared_versions, identity)

    def record_profile(self, ctx) -> None:
        hmc_cache_root, _shared_versions = cache_paths(ctx)
        record_prepared_profile(hmc_cache_root, profile_identity(ctx, _image_id(ctx.client_image)))

    def start_prepare(self, ctx) -> None:
        _remove_container(self.prep_name)
        self._start(ctx, self.prep_name, prepare_only=True)

    def wait_prepare_exited(self, timeout: float) -> None:
        _wait_exited(self.prep_name, timeout)

    def prepare_state(self) -> RuntimeState:
        state = _inspect_container(self.prep_name).get("State", {})
        return RuntimeState(bool(state.get("Running", False)), state.get("ExitCode"))

    def prepare_logs(self, tail: int | None = None) -> str:
        return _container_logs(self.prep_name, tail=tail)

    def stop_prepare(self) -> None:
        _remove_container(self.prep_name)

    def start(self, ctx) -> None:
        self._start(ctx, self.name, prepare_only=False)

    def _start(self, ctx, name: str, *, prepare_only: bool) -> None:
        hmc_cache_root, shared_versions = cache_paths(ctx)
        prepared = self.profile_prepared(ctx)

        _run_container(
            name=name,
            image=ctx.client_image,
            network=ctx.net_name,
            sysctls=shaped_tcp_sysctls(ctx.netem, ctx.server_netem),
            env={
                "AM_AUTOTEST_BRIDGE_TOKEN": ctx.token,
                "AM_AUTOTEST_GAME_DIR": "/work/inst/game",
                "AM_AUTOTEST_HMC_CACHE_DIR": "/work/hmc-cache",
                "AM_AUTOTEST_HMC_PREPARED": str(prepared).lower(),
                "AM_AUTOTEST_PREPARE_ONLY": str(prepare_only).lower(),
                "AM_AUTOTEST_DISPLAY_START_SECONDS": str(ctx.settings.get("timeouts", {}).get("displayStartSeconds", 5)),
                "AM_AUTOTEST_RENDER_CLIENT": str(bool(ctx.scenario.get("renderClient", False)) and not prepare_only).lower(),
                "AUTOMODPACK_DATA_ROOT": str(CLIENT_DATA_ROOT),
                "HOME": "/work",
                "XDG_CONFIG_HOME": "/work/.config",
                "JAVA_TOOL_OPTIONS": "-Xmx2G",
            },
            mounts=[
                (launcher_meta_dir(ctx), "/work/inst", False),
                (ctx.game_dir, "/work/inst/game", False),
                (hmc_cache_root, "/work/hmc-cache", False),
                (shared_versions, "/work/hmc-shared-versions", False),
            ],
            command=[
                "/opt/automodpack/run-headlessmc-client",
                ctx.target.loader,
                ctx.target.minecraft,
                "localhost",
                "25565",
                str(ctx.target.java),
                hmc_build(ctx.target),
            ],
            user=f"{_uid()}:{_gid()}",
            entrypoint=["/usr/bin/tini", "--"],
            labels=resource_labels(ctx.resource_scope),
            # NET_ADMIN is only needed so the --netem qdisc can be applied from inside; never granted otherwise.
            cap_add=["NET_ADMIN"] if ctx.netem else None,
        )

    def stop(self) -> None:
        _remove_container(self.name)

    def running(self) -> None:
        _assert_running(self.name)

    def is_running(self) -> bool:
        try:
            _assert_running(self.name)
            return True
        except (RuntimeError, docker_py.errors.NotFound):
            return False

    def logs(self, tail: int | None = None) -> str:
        return _container_logs(self.name, tail=tail)

    def exec(self, argv: list[str], *, user: str | None = None) -> ExecResult:
        result = _container(self.name).exec_run(argv, user=user)
        return ExecResult(int(result.exit_code), _exec_output(result))

    def wait_exited(self, timeout: float) -> None:
        _wait_exited(self.name, timeout)

    def exit_code(self) -> int | None:
        return _exit_code(self.name)

    def state(self) -> RuntimeState:
        state = _inspect_container(self.name).get("State", {})
        return RuntimeState(bool(state.get("Running", False)), state.get("ExitCode"))

    def apply_netem(self, ctx) -> None:
        if ctx.netem:
            _shape_egress(self.name, ctx.netem, "netem")


class DockerLinuxServerRuntime:
    """The itzg Minecraft server container."""

    capabilities = Capabilities()

    def __init__(self, ctx):
        self.name = ctx.srv_name

    def start(self, ctx, spec: ServerSpec) -> None:
        if spec.cache_volume:
            if spec.clean_cache:
                _remove_volume(spec.cache_volume)
            _ensure_volume(spec.cache_volume)
        env = dict(spec.env)
        # Run the itzg server as the same user the runner uses, so it can write to the
        # bind-mounted mods/automodpack dirs (which the runner created). Without this the
        # server runs as itzg's default UID 1000 and fails on hosts with a different UID
        # (e.g. the GitHub runner's 1001): AccessDenied writing /data/mods/*.download.
        env.setdefault("UID", str(_uid()))
        env.setdefault("GID", str(_gid()))
        _run_container(
            name=self.name,
            image=spec.image,
            network=ctx.net_name,
            env=env,
            mounts=list(spec.mounts),
            labels=spec.labels or resource_labels(ctx.resource_scope),
            cap_add=list(spec.cap_add) if spec.cap_add else None,
            sysctls=spec.sysctls if spec.sysctls is not None else shaped_tcp_sysctls(ctx.netem, ctx.server_netem),
        )

    def stop(self) -> None:
        _remove_container(self.name)

    def logs(self, tail: int | None = None) -> str:
        return _container_logs(self.name, tail=tail)

    def exec(self, argv: list[str], *, user: str | None = None) -> ExecResult:
        result = _container(self.name).exec_run(argv, user=user)
        return ExecResult(int(result.exit_code), _exec_output(result))

    def restart(self, timeout: float = 60) -> None:
        _container(self.name).restart(timeout=timeout)

    def wait_for_log(self, needle: str, timeout: float) -> None:
        _wait_for_log(self.name, needle, timeout)

    def apply_netem(self, ctx, qdisc: list[str], label: str) -> None:
        _shape_egress(self.name, qdisc, label)


class DockerLinuxHostingRuntime:
    """Static HTTPS host, MinIO and one-shot upload containers."""

    def run(
        self,
        name: str,
        *,
        image: str,
        network: str,
        env: dict[str, str],
        mounts=(),
        command: list[str] | None = None,
        entrypoint: list[str] | None = None,
        aliases: list[str] | None = None,
        labels: dict[str, str] | None = None,
        sysctls: dict[str, str] | None = None,
        cap_add: list[str] | None = None,
    ) -> None:
        _run_container(
            name=name,
            image=image,
            network=network,
            env=env,
            mounts=list(mounts),
            command=command,
            entrypoint=entrypoint,
            aliases=aliases,
            labels=labels,
            sysctls=sysctls,
            cap_add=cap_add,
        )

    def logs(self, name: str, tail: int | None = None) -> str:
        return _container_logs(name, tail=tail)

    def wait_for_log(self, name: str, needle: str, timeout: float) -> None:
        _wait_for_log(name, needle, timeout)

    def wait_exited(self, name: str, timeout: float) -> None:
        _wait_exited(name, timeout)

    def exit_code(self, name: str) -> int | None:
        return _exit_code(name)

    def remove(self, name: str) -> None:
        _remove_container(name)

    def ensure_network(self, name: str, labels: dict[str, str] | None = None) -> None:
        _ensure_network(name, labels)

    def remove_network(self, name: str) -> None:
        _remove_network(name)

    def ensure_volume(self, name: str) -> None:
        _ensure_volume(name)

    def remove_volume(self, name: str) -> None:
        _remove_volume(name)


class DockerLinuxPlatform:
    """Linux: Docker client container + Docker server container, full capability set."""

    name = "linux"
    capabilities = Capabilities(
        container_client=True,
        container_server=True,
        netem=True,
        rendered_ui=True,
    )

    def __init__(self, settings: dict | None = None):
        self.settings = dict(settings or {})

    def client(self, ctx) -> DockerLinuxClientRuntime:
        return DockerLinuxClientRuntime(ctx)

    def server(self, ctx) -> DockerLinuxServerRuntime:
        return DockerLinuxServerRuntime(ctx)

    def hosting(self, ctx) -> DockerLinuxHostingRuntime:
        return DockerLinuxHostingRuntime()

    def workspace(self, ctx) -> HostPathServerWorkspace:
        return HostPathServerWorkspace(ctx.server_dir)

    def abort_case(self, ctx) -> None:
        for name in (ctx.cli_name, ctx.prep_name, ctx.srv_name, ctx.static_name, ctx.s3_name):
            if not name:
                continue
            try:
                _remove_container(name)
            except Exception:
                logger.warning("Failed to remove expired case container %s", name)
        try:
            _remove_container(f"{ctx.s3_name}-mc")
        except Exception:
            pass
        if ctx.net_name and ctx.net_name != "host":
            try:
                _remove_network(ctx.net_name)
            except Exception:
                logger.warning("Failed to remove expired case network %s", ctx.net_name)


__all__ = [
    "DockerLinuxClientRuntime",
    "DockerLinuxHostingRuntime",
    "DockerLinuxPlatform",
    "DockerLinuxServerRuntime",
]
