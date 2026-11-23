"""Runtime seam, Linux container pin, and capability gating."""
from __future__ import annotations

import types

import pytest

from automodpack_autotester import client_steps, runner, server_steps
from automodpack_autotester.docker_harness import Docker
from automodpack_autotester.runtime import (
    Capabilities,
    ClientRuntime,
    HostingRuntime,
    Platform,
    ServerRuntime,
    ServerSpec,
)
from automodpack_autotester.runtime import docker_linux
from automodpack_autotester.runtime.detect import (
    PlatformUnavailable,
    case_skip_reason,
    missing_capabilities,
    select_platform,
)
from automodpack_autotester.runtime.docker_linux import (
    DockerLinuxClientRuntime,
    DockerLinuxHostingRuntime,
    DockerLinuxPlatform,
    DockerLinuxServerRuntime,
)
from automodpack_autotester.host.backend import machine
from automodpack_autotester.host.client import WindowsNativeClientRuntime
from automodpack_autotester.host.server import JavaServer
from automodpack_autotester.supervisor import SCOPE_LABEL
from tests.fake_runtime import FakeClientRuntime, FakeHostingRuntime, FakeServerRuntime


class FakePlatform:
    name = "fake"

    def __init__(self, capabilities: Capabilities | None = None):
        self.capabilities = capabilities or Capabilities()
        self.client_runtime = FakeClientRuntime()
        self.server_runtime = FakeServerRuntime()
        self.hosting_runtime = FakeHostingRuntime()
        self.aborted = []

    def client(self, ctx):
        return self.client_runtime

    def server(self, ctx):
        return self.server_runtime

    def hosting(self, ctx):
        return self.hosting_runtime

    def workspace(self, ctx):
        return ctx.server_workspace

    def prerequisites(self, settings):
        return []

    def abort_case(self, ctx):
        self.aborted.append(ctx)


def test_every_runtime_satisfies_its_protocol(tmp_path):
    """Fakes and real runtimes alike: a protocol member added or renamed here must show up everywhere."""
    ctx = types.SimpleNamespace(cli_name="cli-container", prep_name="", srv_name="srv-container", game_dir=tmp_path / "game")

    assert isinstance(FakeClientRuntime(), ClientRuntime)
    assert isinstance(FakeServerRuntime(), ServerRuntime)
    assert isinstance(FakeHostingRuntime(), HostingRuntime)
    assert isinstance(FakePlatform(), Platform)
    assert isinstance(DockerLinuxClientRuntime(ctx), ClientRuntime)
    assert isinstance(DockerLinuxServerRuntime(ctx), ServerRuntime)
    assert isinstance(DockerLinuxHostingRuntime(), HostingRuntime)
    assert isinstance(DockerLinuxPlatform(), Platform)
    assert isinstance(WindowsNativeClientRuntime(ctx), ClientRuntime)
    assert isinstance(JavaServer(ctx), ServerRuntime)
    assert isinstance(machine({"farm": {"provide": "native"}}), Platform)
    assert isinstance(machine({"farm": {"provide": "docker"}}), Platform)


def test_launch_client_drives_the_attached_runtime(make_ctx, monkeypatch):
    ctx = make_ctx(netem=["delay", "10ms"], server_netem=["rate", "5mbit"], loss="1%")
    ctx.artifact.write_bytes(b"autotest-artifact")
    ctx.client = FakeClientRuntime(ctx.cli_name, ctx.prep_name)
    ctx.server = FakeServerRuntime(ctx.srv_name)
    monkeypatch.setattr(client_steps, "_jitter_sleep", lambda *_args, **_kwargs: None)

    client_steps._launch_client(ctx)

    assert ctx.client.calls[0] == ("start", ctx.cli_name)
    assert ("apply_netem", ("delay", "10ms")) in ctx.client.calls
    assert ("apply_netem", ("loss", "1%"), "loss") in ctx.server.calls
    assert ("apply_netem", ("rate", "5mbit"), "server netem") in ctx.server.calls


def test_server_launch_builds_a_platform_neutral_spec(make_ctx):
    target = types.SimpleNamespace(id="1.21.1-fabric", minecraft="1.21.1", loader="fabric", java=21)
    ctx = make_ctx(
        target=target,
        settings={
            "serverTypes": {"fabric": "FABRIC"},
            "server": {"memory": "2G"},
            "serverCache": {"enabled": False},
        },
        server_netem=["delay", "50ms"],
    )
    ctx.server = FakeServerRuntime(ctx.srv_name)

    server_steps._launch_server(ctx)

    kind, spec = ctx.server.calls[0]
    assert kind == "start"
    assert isinstance(spec, ServerSpec)
    assert spec.image == "itzg/minecraft-server:java21"
    assert spec.env["TYPE"] == "FABRIC"
    assert spec.env["VERSION"] == "1.21.1"
    assert spec.env["MEMORY"] == "2G"
    assert spec.env["AUTOMODPACK_DATA_ROOT"] == "automodpack/data"
    assert spec.cache_volume is None
    assert spec.mounts == ((ctx.server_dir, "/data", False),)
    assert spec.cap_add == ("NET_ADMIN",)
    assert "UID" not in spec.env


def test_docker_client_launch_keeps_the_exact_legacy_container(make_ctx, monkeypatch, tmp_path):
    seen = {}
    ctx = make_ctx(
        target=types.SimpleNamespace(
            id="1.20.1-forge",
            minecraft="1.20.1",
            loader="forge",
            java=17,
            forge_version="47.4.26",
        ),
        netem=["delay", "300ms"],
        server_netem=[],
        scenario={"renderClient": True},
        settings={"timeouts": {"displayStartSeconds": 7}},
    )
    ctx.resource_scope = "scope123"
    monkeypatch.setattr(Docker, "run", lambda _self, **kwargs: seen.update(kwargs))
    monkeypatch.setattr(docker_linux, "profile_is_prepared", lambda *_args, **_kwargs: True)
    monkeypatch.setattr(docker_linux, "cache_paths", lambda _ctx: (tmp_path / "hmc", tmp_path / "shared"))
    monkeypatch.setattr(docker_linux, "launcher_meta_dir", lambda _ctx: tmp_path / "meta")
    monkeypatch.setattr(docker_linux, "uid", lambda: 1000)
    monkeypatch.setattr(docker_linux, "gid", lambda: 1001)

    docker_linux.DockerLinuxClientRuntime(ctx).start(ctx)

    assert seen["name"] == ctx.cli_name
    assert seen["image"] == "img"
    assert seen["network"] == "net"
    assert seen["env"] == {
        "AM_AUTOTEST_BRIDGE_TOKEN": "tok",
        "AM_AUTOTEST_GAME_DIR": "/work/inst/game",
        "AM_AUTOTEST_HMC_CACHE_DIR": "/work/hmc-cache",
        "AM_AUTOTEST_HMC_PREPARED": "true",
        "AM_AUTOTEST_PREPARE_ONLY": "false",
        "AM_AUTOTEST_DISPLAY_START_SECONDS": "7",
        "AM_AUTOTEST_RENDER_CLIENT": "true",
        "AUTOMODPACK_DATA_ROOT": "automodpack/client/data",
        "HOME": "/work",
        "XDG_CONFIG_HOME": "/work/.config",
        "JAVA_TOOL_OPTIONS": "-Xmx2G",
    }
    assert seen["mounts"] == [
        (tmp_path / "meta", "/work/inst", False),
        (ctx.game_dir, "/work/inst/game", False),
        (tmp_path / "hmc", "/work/hmc-cache", False),
        (tmp_path / "shared", "/work/hmc-shared-versions", False),
    ]
    assert seen["command"] == [
        "/opt/automodpack/run-headlessmc-client",
        "forge",
        "1.20.1",
        "localhost",
        "25565",
        "17",
        "47.4.26",
    ]
    assert seen["user"] == "1000:1001"
    assert seen["entrypoint"] == ["/usr/bin/tini", "--"]
    assert seen["labels"] == {SCOPE_LABEL: "scope123"}
    assert seen["cap_add"] == ["NET_ADMIN"]
    assert seen["sysctls"] == {
        "net.ipv4.tcp_wmem": "4096 65536 33554432",
        "net.ipv4.tcp_rmem": "4096 131072 33554432",
    }


def test_docker_server_launch_keeps_the_exact_legacy_container(make_ctx, monkeypatch):
    seen = {}
    ctx = make_ctx()
    ctx.resource_scope = "scope123"
    mounts = (("/cache-vol", "/data", False), (ctx.server_dir / "mods", "/data/mods", False))
    spec = ServerSpec(
        image="itzg/minecraft-server:java21",
        env={"TYPE": "FABRIC", "VERSION": "1.21"},
        mounts=mounts,
        cap_add=(),
        cache_volume="cache-vol",
        clean_cache=True,
        labels={SCOPE_LABEL: "scope123"},
    )
    calls = []
    monkeypatch.setattr(Docker, "remove_volume", lambda _self, name: calls.append(("remove_volume", name)))
    monkeypatch.setattr(Docker, "ensure_volume", lambda _self, name: calls.append(("ensure_volume", name)))
    monkeypatch.setattr(Docker, "run", lambda _self, **kwargs: seen.update(kwargs))
    monkeypatch.setattr(docker_linux, "uid", lambda: 1000)
    monkeypatch.setattr(docker_linux, "gid", lambda: 1001)

    docker_linux.DockerLinuxServerRuntime(ctx).start(ctx, spec)

    assert calls == [("remove_volume", "cache-vol"), ("ensure_volume", "cache-vol")]
    assert seen["name"] == ctx.srv_name
    assert seen["image"] == "itzg/minecraft-server:java21"
    assert seen["network"] == "net"
    assert seen["env"] == {"TYPE": "FABRIC", "VERSION": "1.21", "UID": "1000", "GID": "1001", "SERVER_PORT": "25565", "RCON_PORT": "25575"}
    assert seen["mounts"] == mounts
    assert seen["labels"] == {SCOPE_LABEL: "scope123"}
    assert seen.get("cap_add") is None
    assert seen["sysctls"] is None


def test_capability_gate_names_the_missing_requirement():
    scenario = {"platforms": ["windows"], "requires": ["netem", "junction_probe"]}
    platform = FakePlatform(Capabilities(netem=False, junction_probe=False))

    assert missing_capabilities(scenario, platform.capabilities) == ["netem", "junction_probe"]
    reason = case_skip_reason(scenario, platform)
    assert reason is not None and "platforms" in reason

    linux = types.SimpleNamespace(name="linux", capabilities=Capabilities())
    assert case_skip_reason({"requires": ["netem"]}, linux) is None
    assert missing_capabilities({"requires": ["netem"]}, linux.capabilities) == []


def test_unknown_requirement_is_treated_as_missing():
    assert missing_capabilities({"requires": ["gpu"]}, Capabilities()) == ["gpu"]


def test_native_client_reaches_the_server_on_loopback():
    native = types.SimpleNamespace(capabilities=Capabilities(native_client=True))
    container = types.SimpleNamespace(capabilities=Capabilities())

    assert runner.server_host_for("bridge", native, "srv-container") == "127.0.0.1"
    assert runner.server_host_for("bridge", container, "srv-container") == "srv-container"
    assert runner.server_host_for("host", container, "srv-container") == "127.0.0.1"


def test_deletion_correctness_is_not_a_platform_skip():
    scenario = {"platforms": ["linux", "windows"]}
    linux = types.SimpleNamespace(name="linux", capabilities=Capabilities())
    windows = types.SimpleNamespace(name="windows", capabilities=Capabilities(native_client=True, netem=False, rendered_ui=False, junction_probe=True))

    assert case_skip_reason(scenario, linux) is None
    assert case_skip_reason(scenario, windows) is None


def test_single_string_requirement_is_one_capability_not_characters():
    assert missing_capabilities({"requires": "netem"}, Capabilities(netem=False)) == ["netem"]
    assert missing_capabilities({"requires": "netem"}, Capabilities()) == []


def test_undeclared_platforms_run_on_every_kernel(monkeypatch):
    monkeypatch.setattr("automodpack_autotester.runtime.detect.sys.platform", "win32")
    assert case_skip_reason({}, FakePlatform()) is None
    monkeypatch.setattr("automodpack_autotester.runtime.detect.sys.platform", "linux")
    assert case_skip_reason({}, FakePlatform()) is None
    monkeypatch.setattr("automodpack_autotester.runtime.detect.sys.platform", "win32")
    assert case_skip_reason({"platforms": ["linux"]}, FakePlatform()) is not None


def test_platform_selection_defaults_to_native_and_windows_is_this_kernel():
    assert select_platform({}).name == "native"
    assert select_platform({}, requested="docker").name == "docker"
    with pytest.raises(PlatformUnavailable, match="must run on Windows"):
        select_platform({}, requested="windows")
    with pytest.raises(PlatformUnavailable, match="unknown farm"):
        select_platform({}, requested="plan9")
    assert select_platform({}, requested="windows", require_host=False).name == "native"


def test_run_case_refuses_shaping_on_a_platform_without_netem(tmp_path):
    platform = FakePlatform(Capabilities(netem=False))
    platform.name = "linux"
    with pytest.raises(ValueError, match="network shaping is not available"):
        runner.run_case(
            types.SimpleNamespace(id="1.21.1-fabric", minecraft="1.21.1", loader="fabric", java=21, artifact_pattern="*.jar"),
            {"flow": [{"do": "quit"}]},
            out_dir=tmp_path,
            artifact_dir=tmp_path,
            client_image="img",
            settings={},
            resource_scope="scope",
            netem=["delay", "1ms"],
            platform=platform,
        )


def test_step_modules_do_not_import_docker_harness():
    from pathlib import Path
    import ast

    root = Path(__file__).parents[1] / "automodpack_autotester"
    allow = {"runtime/docker_linux.py", "docker_harness.py", "host/backend.py"}
    offenders = []
    for path in root.rglob("*.py"):
        relative = path.relative_to(root).as_posix()
        if relative in allow:
            continue
        tree = ast.parse(path.read_text(encoding="utf-8"))
        for node in ast.walk(tree):
            if isinstance(node, ast.ImportFrom) and node.module and "docker_harness" in node.module:
                offenders.append(relative)
            if isinstance(node, ast.Import) and any("docker_harness" in alias.name for alias in node.names):
                offenders.append(relative)
    assert offenders == []
