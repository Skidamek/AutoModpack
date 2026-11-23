"""Windows HeadlessMC client on this kernel."""
from __future__ import annotations

import json
import types
from pathlib import Path

import pytest

from automodpack_autotester.bridge import BridgeClient
from automodpack_autotester.config import load_scenarios
from automodpack_autotester.engine.steps_ui import screenshot
from automodpack_autotester.host.backend import machine
from automodpack_autotester.host.client import WindowsNativeClientRuntime
from automodpack_autotester.host.process import Process
from automodpack_autotester.runtime import Capabilities
from automodpack_autotester.runtime.detect import PlatformUnavailable, select_platform


def _windows_settings(tmp_path: Path, *, launcher: Path | None = None, jdk: Path | None = None) -> dict:
    launcher = launcher or (tmp_path / "hmc.exe")
    launcher.write_bytes(b"launcher")
    jdk = jdk or (tmp_path / "jdk21")
    (jdk / "bin").mkdir(parents=True, exist_ok=True)
    (jdk / "bin" / "java.exe").write_bytes(b"java")
    return {
        "farm": {"provide": "native"},
        "windows": {
            "headlessmc": {"path": str(launcher), "version": "3.0.0-RC8"},
            "javaHomes": {"21": str(jdk)},
            "appData": str(tmp_path / "appdata"),
        },
    }


def test_windows_selection_exists_only_on_windows(monkeypatch):
    with pytest.raises(PlatformUnavailable, match="must run on Windows"):
        select_platform({}, requested="windows")

    monkeypatch.setattr("automodpack_autotester.runtime.detect.sys.platform", "win32")
    monkeypatch.setattr("automodpack_autotester.host.backend.sys.platform", "win32")
    platform = select_platform({}, requested="windows")

    assert platform.name == "native"
    assert platform.capabilities.native_client
    assert platform.capabilities.native_server
    assert not platform.capabilities.netem
    assert platform.capabilities.junction_probe


def test_windows_client_launch_command_pins_the_native_launcher(make_ctx, tmp_path):
    settings = _windows_settings(tmp_path)
    ctx = make_ctx(settings=settings, target=types.SimpleNamespace(
        id="1.21.1-fabric", minecraft="1.21.1", loader="fabric", java=21, fabric_loader="0.20.1"
    ))
    ctx.capabilities = machine({"farm": {"provide": "native"}}).capabilities
    runtime = WindowsNativeClientRuntime(ctx)

    command = runtime._launch_command(ctx)

    assert command[0] == settings["windows"]["headlessmc"]["path"]
    assert command[1] == "launch"
    assert command[2:5] == ["fabric", "1.21.1", "0.20.1"]
    assert "--offline" in command
    assert "-lwjgl" in command
    jvm = command[command.index("--jvm") + 1]
    assert f"-Dautomodpack.autotest.gamedir={ctx.game_dir}" in jvm
    assert "-Dautomodpack.autotest.token=tok" in jvm
    assert "-Dautomodpack.autotest.render=false" in jvm


def test_windows_client_writes_hmc_config_and_spawns_with_java_home(make_ctx, tmp_path, monkeypatch):
    settings = _windows_settings(tmp_path)
    ctx = make_ctx(settings=settings)
    ctx.capabilities = Capabilities(native_client=True, rendered_ui=False)
    seen = {}

    def fake_spawn(self, argv, *, cwd=None, env=None, log=None):
        seen.update(argv=argv, cwd=cwd, env=env, log=log)

    monkeypatch.setattr(Process, "spawn", fake_spawn)
    runtime = WindowsNativeClientRuntime(ctx)
    runtime.start(ctx)

    config = Path(settings["windows"]["appData"]) / "headlessmc" / "config.properties"
    text = config.read_text(encoding="utf-8")
    assert "hmc.files.game=" in text
    assert "hmc.java.versions=" in text
    assert "hmc.files.game-for-each-version=false" in text
    assert seen["env"]["HMC_HOME"]
    assert seen["env"]["JAVA_HOME"] == settings["windows"]["javaHomes"]["21"]
    assert seen["env"]["AUTOMODPACK_DATA_ROOT"] == "automodpack/client/data"
    assert "-lwjgl" in seen["argv"]


def test_bridge_command_overwrite_does_not_use_path_rename(make_ctx, tmp_path, monkeypatch):
    ctx = make_ctx(game_dir=tmp_path / "bridge-game")
    bridge = BridgeClient(ctx.game_dir, "tok")
    (ctx.game_dir / "automodpack" / "autotest").mkdir(parents=True, exist_ok=True)
    command = ctx.game_dir / "automodpack" / "autotest" / "bridge-command.json"
    command.write_text(json.dumps({"old": True}), encoding="utf-8")

    def refuse_rename(self, target):
        raise FileExistsError("Windows refuses rename over an existing file")

    monkeypatch.setattr(Path, "rename", refuse_rename)

    with pytest.raises(TimeoutError):
        bridge.request("ping", timeout=0.05)

    assert json.loads(command.read_text(encoding="utf-8"))["op"] == "ping"


def test_screenshots_are_a_no_op_without_a_rendered_ui(make_ctx):
    ctx = make_ctx()
    ctx.capabilities = Capabilities(rendered_ui=False)
    ctx.platform = types.SimpleNamespace(name="native")
    ctx.bridge = types.SimpleNamespace(screenshot=lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("must not capture")))

    screenshot(ctx, {"file": "first-connect"})


def test_sync_lane_declares_windows_explicitly():
    scenarios = load_scenarios()
    for name in ("download-only", "sync", "files-edge", "tiny-files", "state-history", "rollback-history", "restart-deletion"):
        assert "windows" in scenarios[name]["platforms"], name
    assert "windows" not in scenarios["static-host"].get("platforms", ["linux"])
