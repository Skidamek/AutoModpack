"""Windows HeadlessMC as a Java child on this kernel."""
from __future__ import annotations

import logging
import os
import shutil
import subprocess
import sys
from pathlib import Path

from ..runtime.base import RuntimeState
from ..runtime.hmc import (
    CLIENT_DATA_ROOT,
    cache_paths,
    hmc_build,
    profile_identity,
    profile_is_prepared,
    record_prepared_profile,
)
from .java import java_home
from .process import Process


logger = logging.getLogger(__name__)

LAUNCHER_VERSION = "3.0.0-RC8"
_CLIENT_WIDTH, _CLIENT_HEIGHT = 854, 480
_RENDER_WIDTH, _RENDER_HEIGHT = 640, 480


def launcher_path(settings: dict) -> Path:
    configured = (settings.get("windows") or {}).get("headlessmc", {}).get("path")
    candidate = Path(str(configured)) if configured else Path(r"C:\amp\hmc.exe")
    if candidate.is_file():
        return candidate
    found = shutil.which("hmc")
    if found:
        return Path(found)
    raise RuntimeError(f"HeadlessMC Windows launcher not found at {candidate}; set windows.headlessmc.path")


def _forward_slashes(path: Path) -> str:
    return str(path).replace("\\", "/")


def _hmc_appdata(ctx) -> Path:
    """This case's ``%APPDATA%``.

    HeadlessMC reads one ``headlessmc/config.properties`` under APPDATA, and the file names the
    case's game dir. Two concurrent cases sharing the machine's APPDATA would overwrite each
    other's game dir between a relaunch's config write and the launcher reading it.
    """
    return ctx.game_dir.parent / "appdata"


def _write_hmc_config(appdata: Path, cache_dir: Path, game_dir: Path, java: Path) -> None:
    config_dir = appdata / "headlessmc"
    config_dir.mkdir(parents=True, exist_ok=True)
    properties = {
        "hmc.files.mc": _forward_slashes(cache_dir),
        "hmc.files.game": _forward_slashes(game_dir),
        "hmc.files.game-for-each-version": "false",
        "hmc.java.download": "false",
        "hmc.java.versions": _forward_slashes(java),
        "hmc.launcher.crash-report-watcher": "true",
        "hmc.jline.enabled": "false",
        "hmc.xvfb.check": "false",
    }
    (config_dir / "config.properties").write_text("".join(f"{key}={value}\n" for key, value in properties.items()), encoding="utf-8")


def _profile_args(ctx) -> list[str]:
    args = [ctx.target.loader, ctx.target.minecraft]
    build = hmc_build(ctx.target)
    if build:
        args.append(build)
    return args


def _windows_short_path(path: Path) -> str:
    """NTFS 8.3 form of ``path``, so HMC's space-splitting ``--jvm`` passthrough is safe."""
    value = str(path)
    if not sys.platform.startswith("win"):
        return value
    try:
        import ctypes
        from ctypes import wintypes

        get = ctypes.windll.kernel32.GetShortPathNameW
        get.argtypes = [wintypes.LPCWSTR, wintypes.LPWSTR, wintypes.DWORD]
        get.restype = wintypes.DWORD
        size = get(value, None, 0)
        if size:
            buffer = ctypes.create_unicode_buffer(size)
            if get(value, buffer, size):
                return buffer.value
    except Exception:
        pass
    return value


def _jvm_property(key: str, value: str) -> str:
    if " " in value or "\t" in value:
        return f'-D{key}="{value}"'
    return f"-D{key}={value}"


def _jvm_args(ctx) -> str:
    render = bool(ctx.scenario.get("renderClient", False)) and ctx.capabilities.rendered_ui
    return " ".join(
        [
            "-Xmx2G",
            _jvm_property("automodpack.autotest.gamedir", _windows_short_path(ctx.game_dir).replace("\\", "/")),
            f"-Dautomodpack.autotest.token={ctx.token}",
            f"-Dautomodpack.autotest.render={'true' if render else 'false'}",
            "-Dcom.mojang.text2speech=false",
            "-Djava.awt.headless=true",
            "-Dmcholepunch.debug=true",
        ]
    )


def _game_args(ctx) -> str:
    if bool(ctx.scenario.get("renderClient", False)) and ctx.capabilities.rendered_ui:
        return f"--width {_RENDER_WIDTH} --height {_RENDER_HEIGHT}"
    return f"--width {_CLIENT_WIDTH} --height {_CLIENT_HEIGHT}"


def _child_env(ctx, cache_dir: Path, home: Path, appdata: Path) -> dict[str, str]:
    env = dict(os.environ)
    env.pop("JAVA_TOOL_OPTIONS", None)
    env["APPDATA"] = str(appdata)
    env["HMC_HOME"] = str(cache_dir)
    env["JAVA_HOME"] = str(home)
    env["PATH"] = f"{home / 'bin'};{env.get('PATH', '')}"
    env["AUTOMODPACK_DATA_ROOT"] = CLIENT_DATA_ROOT.as_posix()
    return env


class WindowsNativeClientRuntime:
    """The Windows HeadlessMC launcher, one native process per case."""

    def __init__(self, ctx):
        self.name = ctx.cli_name
        self.prep_name = ctx.prep_name or ctx.cli_name.replace("-c-", "-p-", 1)
        self._process: Process | None = None
        self._prepare: Process | None = None
        self._exit_code: int | None = None
        self._prep_exit_code: int | None = None
        self.log_path = ctx.game_dir.parent / "client.stdout.log"
        self.prep_log_path = ctx.game_dir.parent / "client-prepare.stdout.log"

    def profile_prepared(self, ctx) -> bool:
        hmc_cache_root, shared_versions = cache_paths(ctx)
        identity = profile_identity(ctx, self._launcher_identity(ctx))
        return profile_is_prepared(ctx, hmc_cache_root, shared_versions, identity)

    def record_profile(self, ctx) -> None:
        hmc_cache_root, _shared_versions = cache_paths(ctx)
        record_prepared_profile(hmc_cache_root, profile_identity(ctx, self._launcher_identity(ctx)))

    def _launcher_identity(self, ctx) -> str:
        version = (ctx.settings.get("windows") or {}).get("headlessmc", {}).get("version") or LAUNCHER_VERSION
        return f"hmc:{version}:windows-x64"

    def start_prepare(self, ctx) -> None:
        if self.profile_prepared(ctx):
            self._prep_exit_code = 0
            self._prepare = None
            return
        self._prepare = self._spawn(ctx, self._prepare_command(ctx), self.prep_log_path, "prepare")

    def _prepare_command(self, ctx) -> list[str]:
        return [str(launcher_path(ctx.settings)), "version", "install", *_profile_args(ctx)]

    def wait_prepare_exited(self, timeout: float) -> None:
        if self._prepare is None:
            return
        try:
            self._prep_exit_code = self._prepare.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            raise TimeoutError(f"client preparation did not exit within {timeout}s") from None

    def prepare_state(self) -> RuntimeState:
        if self._prepare is None:
            return RuntimeState(False, self._prep_exit_code)
        return RuntimeState(self._prepare.alive(), self._prepare.exit_code())

    def prepare_logs(self, tail: int | None = None) -> str:
        return self._prepare.logs(tail) if self._prepare is not None else ""

    def stop_prepare(self) -> None:
        if self._prepare is not None:
            self._prepare.kill()
            self._prep_exit_code = self._prepare.exit_code()
        self._prepare = None

    def start(self, ctx) -> None:
        self._process = self._spawn(ctx, self._launch_command(ctx), self.log_path, "client")

    def _launch_command(self, ctx) -> list[str]:
        return [
            str(launcher_path(ctx.settings)),
            "launch",
            *_profile_args(ctx),
            "--offline",
            "-lwjgl",
            "--jvm",
            _jvm_args(ctx),
            "--game",
            _game_args(ctx),
            "--resolution",
            f"{_CLIENT_WIDTH}x{_CLIENT_HEIGHT}",
        ]

    def _spawn(self, ctx, argv: list[str], log: Path, name: str) -> Process:
        hmc_cache_root, _shared_versions = cache_paths(ctx)
        home = java_home(ctx.settings, ctx.target.java)
        appdata = _hmc_appdata(ctx)
        _write_hmc_config(appdata, hmc_cache_root, ctx.game_dir, home)
        env = _child_env(ctx, hmc_cache_root, home, appdata)
        if ctx.slot is not None:
            return ctx.slot.spawn(name, [str(part) for part in argv], cwd=ctx.game_dir, env=env, log=log)
        child = Process()
        child.spawn([str(part) for part in argv], cwd=ctx.game_dir, env=env, log=log)
        return child

    def stop(self) -> None:
        if self._process is not None:
            self._process.kill()
            self._exit_code = self._process.exit_code()
        self._process = None

    def running(self) -> None:
        if self._process is None:
            raise RuntimeError(f"client {self.name} was never started")
        if not self._process.alive():
            self._exit_code = self._process.exit_code()
            tail = "\n".join(self.logs().splitlines()[-20:])
            raise RuntimeError(f"client {self.name} exited (code={self._exit_code})\n--- client log tail ---\n{tail}")

    def logs(self, tail: int | None = None) -> str:
        return self._process.logs(tail) if self._process is not None else ""

    def wait_exited(self, timeout: float) -> None:
        if self._process is None:
            return
        try:
            self._exit_code = self._process.wait(timeout=timeout)
        except subprocess.TimeoutExpired:
            raise TimeoutError(f"client {self.name} is still running after {timeout}s") from None

    def exit_code(self) -> int | None:
        if self._process is not None:
            code = self._process.exit_code()
            if code is not None:
                self._exit_code = code
        return self._exit_code

    def state(self) -> RuntimeState:
        if self._process is None:
            return RuntimeState(False, self._exit_code)
        alive = self._process.alive()
        code = self._process.exit_code()
        if code is not None:
            self._exit_code = code
        return RuntimeState(alive, code)

    def apply_netem(self, ctx) -> None:
        return None
