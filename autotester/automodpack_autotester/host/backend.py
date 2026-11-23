"""How this process spawns case children on this kernel."""
from __future__ import annotations

import logging
import subprocess
import sys

from ..config import load_targets, resolve_settings_path
from ..docker_harness import daemon_prerequisite, prepare_client_environment, shared
from ..runtime.base import Capabilities
from ..runtime.docker_linux import DockerLinuxClientRuntime, DockerLinuxHostingRuntime, DockerLinuxPlatform
from ..runtime.workspace import HostPathServerWorkspace
from .client import WindowsNativeClientRuntime, launcher_path
from .farm import load_farm
from .java import java_bin
from .server import JavaServer


logger = logging.getLogger(__name__)


class FarmBackend:
    """``native`` or ``docker`` spawn strategy for this kernel."""

    def __init__(self, config: FarmConfig):
        if config.provide == "docker" and sys.platform.startswith("win"):
            raise RuntimeError("farm.provide=docker needs a Linux kernel; this process is on Windows")
        prepare_client_environment()
        self.config = config
        self.name = config.provide
        self._docker = DockerLinuxPlatform() if config.provide == "docker" else None
        windows = sys.platform.startswith("win")
        if config.provide == "docker":
            self.capabilities = Capabilities(container_client=True, container_server=True, native_client=False, native_server=False, netem=True, rendered_ui=True, junction_probe=False)
        else:
            self.capabilities = Capabilities(
                container_client=not windows,
                container_server=False,
                native_client=windows,
                native_server=True,
                netem=False,
                rendered_ui=not windows,
                junction_probe=windows,
            )

    def client(self, ctx):
        if self._docker is not None:
            return self._docker.client(ctx)
        if sys.platform.startswith("win"):
            return WindowsNativeClientRuntime(ctx)
        return DockerLinuxClientRuntime(ctx)

    def server(self, ctx):
        if self._docker is not None:
            return self._docker.server(ctx)
        return JavaServer(ctx)

    def hosting(self, ctx):
        return DockerLinuxHostingRuntime()

    def workspace(self, ctx):
        return HostPathServerWorkspace(ctx.server_dir)

    def prerequisites(self, settings: dict) -> list[tuple[str, bool, str]]:
        checks = [daemon_prerequisite()]
        if self.config.provide == "native":
            for java in sorted({target.java for target in load_targets().values()}):
                try:
                    checks.append((f"java{java}", True, java_bin(settings, java)))
                except Exception as error:
                    checks.append((f"java{java}", False, str(error)))
        if sys.platform.startswith("win"):
            try:
                launcher = launcher_path(settings)
                result = subprocess.run([str(launcher), "--version"], capture_output=True, text=True, timeout=30)
                checks.append(("headlessmc", result.returncode == 0, f"{launcher} -> {(result.stdout or result.stderr).strip()}"))
            except Exception as error:
                checks.append(("headlessmc", False, str(error)))
            out_dir = resolve_settings_path(settings, "outDir", "out")
            drive = out_dir.drive or "C:"
            try:
                volume = subprocess.run(["fsutil", "fsinfo", "volumeinfo", drive], capture_output=True, text=True, timeout=30)
                checks.append(("filesystem", "NTFS" in volume.stdout.upper(), drive))
            except Exception as error:
                checks.append(("filesystem", False, f"{drive}: {error}"))
        return checks

    def abort_case(self, ctx) -> None:
        if getattr(ctx, "slot", None) is not None:
            ctx.slot.kill_all()
        for runtime in (ctx.client, ctx.server):
            if runtime is None:
                continue
            try:
                runtime.stop()
            except Exception:
                logger.warning("Failed to stop expired case service")
        docker = shared()
        for name in (ctx.cli_name, ctx.prep_name, ctx.srv_name, ctx.static_name, ctx.s3_name, f"{ctx.s3_name}-mc"):
            if not name:
                continue
            try:
                docker.remove(name)
            except Exception:
                logger.warning("Failed to remove expired case container %s", name)
        if ctx.net_name and ctx.net_name != "host":
            try:
                docker.remove_network(ctx.net_name)
            except Exception:
                logger.warning("Failed to remove expired case network %s", ctx.net_name)


def machine(settings: dict | None = None, *, provide: str | None = None) -> FarmBackend:
    return FarmBackend(load_farm(settings, provide=provide))
