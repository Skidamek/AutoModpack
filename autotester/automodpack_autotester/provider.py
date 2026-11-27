"""Unified Provider abstraction and LocalProvider (native Linux/macOS/Windows, zero Docker)."""
from __future__ import annotations

import os
import secrets
import shutil
import sys
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Protocol

from .bridge import BridgeClient
from .config import Target
from .hmc import ensure_hmc_binary, ensure_profile_prepared, hmc_env, profile_args, seed_fml_toml, write_hmc_config
from .java import fast_jvm_flags, java_bin, java_home
from .process import ManagedProcess, PortReservation
from .rcon import RconResult, rcon_command
from .server_seed import launch_argv, materialize_server, write_server_properties


@dataclass(frozen=True)
class Capabilities:
    name: str
    os_family: str  # linux | darwin | windows
    native_windows_ntfs: bool = False
    rendered_ui: bool = False


class ServerProcess(Protocol):
    server_dir: Path
    game_port: int
    endpoint_port: int
    rcon_port: int
    rcon_password: str
    host: str

    def start(self) -> None: ...
    def stop(self, *, force: bool = False) -> None: ...
    def restart(self) -> None: ...
    def alive(self) -> bool: ...
    def wait_ready(self, timeout: float = 180.0) -> None: ...
    def wait_for_log(self, pattern: str, timeout: float = 60.0): ...
    def rcon(self, command: str, timeout: float = 15.0) -> RconResult: ...
    def logs(self, tail: int = 120) -> str: ...


class ClientProcess(Protocol):
    name: str
    game_dir: Path
    token: str
    bridge: BridgeClient | None

    def start(self, *, fast_timers: bool = True, render: bool = False, extra_jvm_args: list[str] | None = None, resolution: tuple[int, int] | None = None) -> None: ...
    def stop(self, *, force: bool = True) -> None: ...
    def wait_ready(self, timeout: float = 180.0) -> BridgeClient: ...
    def wait_exit(self, timeout: float = 60.0) -> int: ...
    def alive(self) -> bool: ...
    def logs(self, tail: int = 120) -> str: ...
    def game_log(self) -> str: ...
    def read_game_file(self, rel_path: str) -> str | None: ...
    def write_game_file(self, rel_path: str, content: bytes) -> None: ...


class Provider(Protocol):
    capabilities: Capabilities

    def prerequisites(self, settings: dict, targets: list[Target]) -> list[tuple[str, bool, str]]: ...
    def create_server(self, target: Target, settings: dict, server_dir: Path, artifact: Path) -> ServerProcess: ...
    def create_client(self, name: str, target: Target, settings: dict, client_root: Path, artifact: Path) -> ClientProcess: ...


class LocalServer:
    """Native JVM Minecraft server running on loopback."""

    def __init__(self, target: Target, settings: dict, server_dir: Path, artifact: Path, *, bind_ip: str = "127.0.0.1", advertise_host: str = "127.0.0.1"):
        self.target = target
        self.settings = settings
        self.server_dir = server_dir
        self.artifact = artifact
        self.bind_ip = bind_ip
        self.host = advertise_host
        self._ports = PortReservation(3)
        self.game_port, self.endpoint_port, self.rcon_port = self._ports.ports
        self.rcon_password = secrets.token_hex(8)
        self._proc: ManagedProcess | None = None
        self._materialized = False
        self.log_path = server_dir.parent / "server.log"

    def _prepare_dir(self) -> None:
        if not self._materialized:
            self.server_dir.mkdir(parents=True, exist_ok=True)
            materialize_server(self.server_dir, self.target, self.settings)
            mods_dir = self.server_dir / "mods"
            mods_dir.mkdir(parents=True, exist_ok=True)
            dest_jar = mods_dir / self.artifact.name
            if not dest_jar.exists():
                shutil.copy2(self.artifact, dest_jar)
            self._materialized = True
        write_server_properties(
            self.server_dir,
            self.settings,
            game_port=self.game_port,
            rcon_port=self.rcon_port,
            rcon_password=self.rcon_password,
            bind_ip=self.bind_ip,
        )

    def start(self) -> None:
        self._prepare_dir()
        java_exe = java_bin(self.settings, self.target.java)
        memory = str((self.settings.get("jvm") or {}).get("serverMemory", "1G"))
        argv = [java_exe, *fast_jvm_flags(self.settings, memory), "-Dmcholepunch.debug=true", *launch_argv(self.server_dir, self.target)]
        env = dict(os.environ)
        env.pop("JAVA_TOOL_OPTIONS", None)
        self._ports.release()
        self._proc = ManagedProcess(argv, cwd=self.server_dir, env=env, log_path=self.log_path)
        self._proc.start()

    def stop(self, *, force: bool = False) -> None:
        self._ports.release()
        if self._proc is not None:
            if not force and self._proc.alive():
                try:
                    self.rcon("stop", timeout=5.0)
                    self._proc.wait(timeout=15.0)
                except Exception:
                    pass
            self._proc.kill(force=True)
            self._proc = None

    def restart(self) -> None:
        self.stop(force=False)
        self.start()

    def alive(self) -> bool:
        return self._proc is not None and self._proc.alive()

    def wait_ready(self, timeout: float = 180.0) -> None:
        if self._proc is None:
            raise RuntimeError("Server was not started")
        self._proc.wait_for_log(r'Done \(|For help, type "help"', timeout=timeout)

    def wait_for_log(self, pattern: str, timeout: float = 60.0):
        if self._proc is None:
            raise RuntimeError("Server was not started")
        return self._proc.wait_for_log(pattern, timeout=timeout)

    def rcon(self, command: str, timeout: float = 15.0) -> RconResult:
        return rcon_command("127.0.0.1", self.rcon_port, self.rcon_password, command, timeout=timeout)

    def logs(self, tail: int = 120) -> str:
        return self._proc.tailer.tail(tail) if self._proc is not None else (self.log_path.read_text(encoding="utf-8", errors="replace") if self.log_path.is_file() else "")


def linux_render_wrapper(width: int = 1280, height: int = 720) -> list[str] | None:
    """Return a virtual display command prefix on Linux (`xvfb-run` first, then `gamescope` headless)."""
    xvfb = shutil.which("xvfb-run")
    if xvfb:
        return [xvfb, "-a", "-s", f"-screen 0 {width}x{height}x24"]
    gamescope = shutil.which("gamescope")
    if gamescope:
        return [gamescope, "--backend", "headless", "-W", str(width), "-H", str(height), "--"]
    return None


class LocalClient:
    """Native HeadlessMC 3 Minecraft client running directly on the host OS (no Docker)."""

    def __init__(self, name: str, target: Target, settings: dict, client_root: Path, artifact: Path):
        self.name = name
        self.target = target
        self.settings = settings
        self.client_root = client_root
        self.game_dir = client_root / "game"
        self.artifact = artifact
        self.token = secrets.token_hex(16)
        self.bridge: BridgeClient | None = None
        self._proc: ManagedProcess | None = None
        self.log_path = client_root / f"client-{name}.log"
        self._prepared = False
        self._render = False

    def _prepare(self, *, xvfb_check: bool = False, gui_scale: int | None = None) -> tuple[Path, Path, Path]:
        home = java_home(self.settings, self.target.java)
        mc_cache = ensure_profile_prepared(self.target, self.settings, home)
        self.game_dir.mkdir(parents=True, exist_ok=True)
        mods_dir = self.game_dir / "mods"
        mods_dir.mkdir(parents=True, exist_ok=True)
        dest_jar = mods_dir / self.artifact.name
        if not dest_jar.exists():
            shutil.copy2(self.artifact, dest_jar)
        seed_fml_toml(self.game_dir, self.target)
        options_txt = self.game_dir / "options.txt"
        if not options_txt.is_file():
            extra = f"guiScale:{gui_scale}\n" if gui_scale is not None else ""
            options_txt.write_text(f"onboardAccessibility:false\nskipMultiplayerWarning:true\n{extra}", encoding="utf-8")
        elif gui_scale is not None:
            lines = [line for line in options_txt.read_text(encoding="utf-8").splitlines() if not line.startswith("guiScale:")]
            lines.append(f"guiScale:{gui_scale}")
            options_txt.write_text("\n".join(lines) + "\n", encoding="utf-8")
        import uuid

        player_name = f"amp_{self.name}"[:16]
        player_uuid = str(uuid.uuid3(uuid.NAMESPACE_DNS, f"OfflinePlayer:{player_name}"))
        cfg_root = self.client_root / "hmc-config"
        write_hmc_config(cfg_root, mc_cache, self.game_dir, home, username=player_name, user_uuid=player_uuid, xvfb_check=xvfb_check)
        self._prepared = True
        return mc_cache, home, cfg_root

    def start(self, *, fast_timers: bool = True, render: bool = False, extra_jvm_args: list[str] | None = None, resolution: tuple[int, int] | None = None) -> None:
        if self.bridge is not None:
            self.bridge.close()
            self.bridge = None
        # Clear previous bridge-state.json so wait_ready waits for THIS launch's port.
        state_file = self.game_dir / "automodpack" / "autotest" / "bridge-state.json"
        state_file.unlink(missing_ok=True)

        self._render = render
        width, height = resolution or ((1280, 720) if render else (854, 480))
        mc_cache, home, cfg_root = self._prepare(xvfb_check=render, gui_scale=2 if render else None)
        hmc_bin = ensure_hmc_binary(self.settings)
        memory = str((self.settings.get("jvm") or {}).get("clientMemory", "1G"))
        jvm_args = [
            *fast_jvm_flags(self.settings, memory),
            f"-Dautomodpack.autotest.gamedir={self.game_dir.as_posix()}",
            f"-Dautomodpack.autotest.token={self.token}",
            "-Dautomodpack.autotest.port=0",
            f"-Dautomodpack.autotest.fastTimers={'true' if fast_timers else 'false'}",
            f"-Dautomodpack.autotest.render={'true' if render else 'false'}",
            "-Dcom.mojang.text2speech=false",
            "-Djava.awt.headless=true",
            "-Dmcholepunch.debug=true",
            *(extra_jvm_args or []),
        ]
        argv = [
            str(hmc_bin),
            "launch",
            *profile_args(self.target),
            "--offline",
            *([] if render else ["-lwjgl"]),
            "--jvm",
            " ".join(jvm_args),
            "--resolution",
            f"{width}x{height}",
        ]
        if render and not sys.platform.startswith("win"):
            wrapper = linux_render_wrapper(width, height)
            if not wrapper:
                raise RuntimeError("render=True requires xvfb-run or gamescope on Linux")
            argv = [*wrapper, *argv]
        env = hmc_env(mc_cache, home, cfg_root)
        self._proc = ManagedProcess(argv, cwd=self.game_dir, env=env, log_path=self.log_path)
        self._proc.start()

    def wait_ready(self, timeout: float = 180.0) -> BridgeClient:
        if self._proc is None:
            raise RuntimeError(f"Client {self.name!r} was not started")
        call_timeout = float((self.settings.get("timeouts") or {}).get("bridgeCallSeconds", 20))
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if not self._proc.alive():
                raise RuntimeError(f"Client {self.name!r} exited early (code={self._proc.returncode()})\n--- log tail ---\n{self.logs(60)}")
            client = BridgeClient.from_state_file(self.game_dir, self.token, timeout=call_timeout)
            if client is not None:
                try:
                    client.ping()
                except Exception:
                    client.close()
                    time.sleep(0.05)
                    continue
                if self._render and "forcing headless mode" in self.logs(200).lower():
                    client.close()
                    self.stop(force=True)
                    raise RuntimeError(
                        "HeadlessMC forced stub-LWJGL headless mode despite render=True. "
                        "Offline rendered UI requires Xvfb (xvfb-run) on Linux with hmc.xvfb.check=true, "
                        "or a logged-in Microsoft account."
                    )
                self.bridge = client
                return client
            time.sleep(0.05)
        raise TimeoutError(f"Client {self.name!r} bridge did not become ready within {timeout:.0f}s\n--- log tail ---\n{self.logs(60)}")

    def wait_exit(self, timeout: float = 60.0) -> int:
        if self._proc is None:
            return 0
        if self.bridge is not None:
            self.bridge.close()
            self.bridge = None
        return self._proc.wait(timeout=timeout)

    def stop(self, *, force: bool = True) -> None:
        if self.bridge is not None:
            if not force:
                try:
                    self.bridge.quit()
                except Exception:
                    pass
            self.bridge.close()
            self.bridge = None
        if self._proc is not None:
            if not force and self._proc.alive():
                try:
                    self._proc.wait(timeout=15.0)
                except Exception:
                    pass
            self._proc.kill(force=True)
            self._proc = None

    def alive(self) -> bool:
        return self._proc is not None and self._proc.alive()

    def logs(self, tail: int = 120) -> str:
        return self._proc.tailer.tail(tail) if self._proc is not None else (self.log_path.read_text(encoding="utf-8", errors="replace") if self.log_path.is_file() else "")

    def game_log(self) -> str:
        latest = self.game_dir / "logs" / "latest.log"
        return latest.read_text(encoding="utf-8", errors="replace") if latest.is_file() else ""

    def read_game_file(self, rel_path: str) -> str | None:
        p = self.game_dir / rel_path
        if not p.is_file():
            return None
        try:
            return p.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return None

    def write_game_file(self, rel_path: str, content: bytes) -> None:
        p = self.game_dir / rel_path
        p.parent.mkdir(parents=True, exist_ok=True)
        p.write_bytes(content)


class LocalProvider:
    """Runs Minecraft servers and HeadlessMC clients natively on the local OS."""

    def __init__(self):
        os_family = "windows" if sys.platform.startswith("win") else ("darwin" if sys.platform == "darwin" else "linux")
        self.capabilities = Capabilities(
            name="local",
            os_family=os_family,
            native_windows_ntfs=(os_family == "windows"),
            rendered_ui=bool(os_family == "linux" and shutil.which("xvfb-run")),
        )

    def prerequisites(self, settings: dict, targets: list[Target]) -> list[tuple[str, bool, str]]:
        checks: list[tuple[str, bool, str]] = []
        seen_java: set[int] = set()
        for t in targets:
            if t.java in seen_java:
                continue
            seen_java.add(t.java)
            try:
                exe = java_bin(settings, t.java)
                checks.append((f"java-{t.java}", True, exe))
            except Exception as e:
                checks.append((f"java-{t.java}", False, str(e)))
        checks.append(("provider", True, f"local ({self.capabilities.os_family}, zero-docker)"))
        if self.capabilities.os_family == "linux":
            if shutil.which("xvfb-run"):
                render_backend = "xvfb-run"
            elif shutil.which("gamescope"):
                render_backend = "gamescope (offline rendering requires xvfb-run or Microsoft account)"
            else:
                render_backend = "none"
        else:
            render_backend = "native"
        checks.append(("renderedUi", True, render_backend))
        return checks

    def create_server(self, target: Target, settings: dict, server_dir: Path, artifact: Path) -> ServerProcess:
        return LocalServer(target, settings, server_dir, artifact)

    def create_client(self, name: str, target: Target, settings: dict, client_root: Path, artifact: Path) -> ClientProcess:
        return LocalClient(name, target, settings, client_root, artifact)
