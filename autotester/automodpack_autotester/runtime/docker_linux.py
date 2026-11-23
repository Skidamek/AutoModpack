"""Docker-on-Linux platform: today's autotester behaviour behind the runtime seam.

Every container argument here is the same value the step modules used to pass to
``docker_harness``: names, mounts, user, tini entrypoint, capabilities, network
modes and environment.
"""
from __future__ import annotations

import logging
import subprocess
from pathlib import Path

from ..docker_harness import Docker, daemon_prerequisite, gid, shared, shaped_tcp_sysctls, uid
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


def _shape_egress(docker: Docker, container_name: str, qdisc: list[str], label: str) -> None:
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
    _disable_iface_offloads(docker, container_name)
    _disable_peer_offloads_of(docker, container_name)
    result = docker.exec(container_name, ["tc", "qdisc", "add", "dev", "eth0", "root", "netem", "limit", "50000", *qdisc], user="root")
    if result.exit_code != 0:
        raise RuntimeError(f"{container_name} container could not apply the {label} qdisc ({result.exit_code}): {result.output}")
    logger.info("Applied %s qdisc to %s eth0: %s", label, container_name, " ".join(qdisc))


def _disable_iface_offloads(docker: Docker, container_name: str) -> None:
    """Turns offloads off on a container's eth0 so the qdisc sees segmented packets; best effort by design."""
    docker.exec(container_name, ["ethtool", "-K", "eth0", "tso", "off", "gso", "off", "gro", "off"], user="root")


def _disable_peer_offloads_of(docker: Docker, container_name: str) -> None:
    peer_index = docker.exec(container_name, ["cat", "/sys/class/net/eth0/iflink"], user="root").output.strip()
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

    def __init__(self, ctx, docker: Docker | None = None):
        self.name = ctx.cli_name
        self.prep_name = ctx.prep_name or ctx.cli_name.replace("-c-", "-p-", 1)
        self.docker = docker or shared()

    def profile_prepared(self, ctx) -> bool:
        hmc_cache_root, shared_versions = cache_paths(ctx)
        identity = profile_identity(ctx, self.docker.image_id(ctx.client_image))
        return profile_is_prepared(ctx, hmc_cache_root, shared_versions, identity)

    def record_profile(self, ctx) -> None:
        hmc_cache_root, _shared_versions = cache_paths(ctx)
        record_prepared_profile(hmc_cache_root, profile_identity(ctx, self.docker.image_id(ctx.client_image)))

    def start_prepare(self, ctx) -> None:
        self.docker.remove(self.prep_name)
        self._start(ctx, self.prep_name, prepare_only=True)

    def wait_prepare_exited(self, timeout: float) -> None:
        self.docker.wait_exited(self.prep_name, timeout)

    def prepare_state(self) -> RuntimeState:
        return self.docker.state(self.prep_name)

    def prepare_logs(self, tail: int | None = None) -> str:
        return self.docker.logs(self.prep_name, tail=tail)

    def stop_prepare(self) -> None:
        self.docker.remove(self.prep_name)

    def start(self, ctx) -> None:
        self._start(ctx, self.name, prepare_only=False)

    def _start(self, ctx, name: str, *, prepare_only: bool) -> None:
        hmc_cache_root, shared_versions = cache_paths(ctx)
        prepared = self.profile_prepared(ctx)

        self.docker.run(
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
                ctx.server_host or "localhost",
                str(int(ctx.vars.get("server_game_port") or 25565)),
                str(ctx.target.java),
                hmc_build(ctx.target),
            ],
            user=f"{uid()}:{gid()}",
            entrypoint=["/usr/bin/tini", "--"],
            labels=resource_labels(ctx.resource_scope),
            # NET_ADMIN is only needed so the --netem qdisc can be applied from inside; never granted otherwise.
            cap_add=["NET_ADMIN"] if ctx.netem else None,
        )

    def stop(self) -> None:
        self.docker.remove(self.name)

    def running(self) -> None:
        self.docker.assert_running(self.name)

    def logs(self, tail: int | None = None) -> str:
        return self.docker.logs(self.name, tail=tail)

    def wait_exited(self, timeout: float) -> None:
        self.docker.wait_exited(self.name, timeout)

    def exit_code(self) -> int | None:
        return self.docker.exit_code(self.name)

    def state(self) -> RuntimeState:
        return self.docker.state(self.name)

    def apply_netem(self, ctx) -> None:
        if ctx.netem:
            _shape_egress(self.docker, self.name, ctx.netem, "netem")


class DockerLinuxServerRuntime:
    """The itzg Minecraft server container."""

    capabilities = Capabilities()

    def __init__(self, ctx, docker: Docker | None = None):
        self.name = ctx.srv_name
        self.docker = docker or shared()

    def start(self, ctx, spec: ServerSpec) -> None:
        if spec.cache_volume:
            if spec.clean_cache:
                self.docker.remove_volume(spec.cache_volume)
            self.docker.ensure_volume(spec.cache_volume)
        env = dict(spec.env)
        env.setdefault("MEMORY", spec.memory)
        if spec.jvm_args:
            env.setdefault("JVM_OPTS", " ".join(spec.jvm_args))
        # Run the itzg server as the same user the runner uses, so it can write to the
        # bind-mounted mods/automodpack dirs (which the runner created). Without this the
        # server runs as itzg's default UID 1000 and fails on hosts with a different UID
        # (e.g. the GitHub runner's 1001): AccessDenied writing /data/mods/*.download.
        env.setdefault("UID", str(uid()))
        env.setdefault("GID", str(gid()))
        game_port = int(ctx.vars.get("server_game_port") or 0)
        if game_port:
            env["SERVER_PORT"] = str(game_port)
        rcon_port = int(ctx.vars.get("server_rcon_port") or 0)
        if rcon_port:
            env["RCON_PORT"] = str(rcon_port)
        slot = getattr(ctx, "slot", None)
        if slot is not None:
            # The slot holds the loopback ports so no concurrent case is handed the same numbers;
            # the server is about to bind them, so they are handed over here.
            slot.release_ports()
        self.docker.run(
            name=self.name,
            image=spec.image,
            network=ctx.net_name,
            env=env,
            mounts=spec.mounts,
            labels=spec.labels or resource_labels(ctx.resource_scope),
            cap_add=list(spec.cap_add) if spec.cap_add else None,
            sysctls=spec.sysctls if spec.sysctls is not None else shaped_tcp_sysctls(ctx.netem, ctx.server_netem),
        )

    def stop(self) -> None:
        self.docker.remove(self.name)

    def logs(self, tail: int | None = None) -> str:
        return self.docker.logs(self.name, tail=tail)

    def exec(self, argv: list[str], *, user: str | None = None) -> ExecResult:
        return self.docker.exec(self.name, argv, user=user)

    def restart(self, timeout: float = 60) -> None:
        self.docker.restart(self.name, timeout=timeout)

    def wait_for_log(self, needle: str, timeout: float) -> None:
        self.docker.wait_for_log(self.name, needle, timeout)

    def apply_netem(self, ctx, qdisc: list[str], label: str) -> None:
        _shape_egress(self.docker, self.name, qdisc, label)


class DockerLinuxHostingRuntime:
    """Static HTTPS host, MinIO and one-shot upload containers."""

    def __init__(self, docker: Docker | None = None):
        self.docker = docker or shared()

    def run(self, name: str, *, image: str, network: str, env: dict[str, str], mounts=(), command: list[str] | None = None, entrypoint: list[str] | None = None, aliases: list[str] | None = None, labels: dict[str, str] | None = None) -> None:
        self.docker.run(name=name, image=image, network=network, env=env, mounts=list(mounts), command=command, entrypoint=entrypoint, aliases=aliases, labels=labels)

    def logs(self, name: str, tail: int | None = None) -> str:
        return self.docker.logs(name, tail=tail)

    def wait_for_log(self, name: str, needle: str, timeout: float) -> None:
        self.docker.wait_for_log(name, needle, timeout)

    def wait_exited(self, name: str, timeout: float) -> None:
        self.docker.wait_exited(name, timeout)

    def exit_code(self, name: str) -> int | None:
        return self.docker.exit_code(name)

    def remove(self, name: str) -> None:
        self.docker.remove(name)

    def ensure_network(self, name: str, labels: dict[str, str] | None = None) -> None:
        self.docker.ensure_network(name, labels)

    def remove_network(self, name: str) -> None:
        self.docker.remove_network(name)


class DockerLinuxPlatform:
    """Linux: Docker client container + Docker server container, full capability set."""

    name = "linux"
    capabilities = Capabilities(
        container_client=True,
        container_server=True,
        netem=True,
        rendered_ui=True,
    )

    def client(self, ctx) -> DockerLinuxClientRuntime:
        return DockerLinuxClientRuntime(ctx)

    def server(self, ctx) -> DockerLinuxServerRuntime:
        return DockerLinuxServerRuntime(ctx)

    def hosting(self, ctx) -> DockerLinuxHostingRuntime:
        return DockerLinuxHostingRuntime()

    def workspace(self, ctx) -> HostPathServerWorkspace:
        return HostPathServerWorkspace(ctx.server_dir)

    def prerequisites(self, settings: dict) -> list[tuple[str, bool, str]]:
        """A reachable Docker daemon is the only runtime prerequisite; the Linux controller adds nothing."""
        return [daemon_prerequisite()]

    def abort_case(self, ctx) -> None:
        docker = shared()
        for name in (ctx.cli_name, ctx.prep_name, ctx.srv_name, ctx.static_name, ctx.s3_name):
            if not name:
                continue
            try:
                docker.remove(name)
            except Exception:
                logger.warning("Failed to remove expired case container %s", name)
        try:
            docker.remove(f"{ctx.s3_name}-mc")
        except Exception:
            pass
        if ctx.net_name and ctx.net_name != "host":
            try:
                docker.remove_network(ctx.net_name)
            except Exception:
                logger.warning("Failed to remove expired case network %s", ctx.net_name)


__all__ = [
    "DockerLinuxClientRuntime",
    "DockerLinuxHostingRuntime",
    "DockerLinuxPlatform",
    "DockerLinuxServerRuntime",
]
