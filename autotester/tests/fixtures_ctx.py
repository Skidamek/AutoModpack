"""A Docker-free Context factory: the one fixture every engine test builds on."""
from __future__ import annotations

import types
from pathlib import Path

import pytest

from automodpack_autotester.engine import Context
from automodpack_autotester.runtime import Capabilities
from automodpack_autotester.runtime.workspace import HostPathServerWorkspace
from tests.fake_runtime import FakeClientRuntime, FakeHostingRuntime, FakeServerRuntime


@pytest.fixture
def make_ctx(tmp_path):
    """Build a Context backed by tmp dirs. Override any field via kwargs."""

    def _make(**overrides) -> Context:
        game_dir = overrides.pop("game_dir", tmp_path / "game")
        game_dir.mkdir(parents=True, exist_ok=True)
        # Every real client has the loader jar in standard mods/ from the start (it's how
        # AutoModpack itself loads) - mirror that so `only_files`-style assertions on mods/
        # see the same baseline a real run would.
        (game_dir / "mods").mkdir(parents=True, exist_ok=True)
        (game_dir / "mods" / "automodpack.jar").write_bytes(b"")
        vars_values = {"server_game_port": 25565, "server_endpoint_port": 25565, "server_rcon_port": 25575}
        vars_values.update(overrides.pop("vars", {}) or {})
        defaults = dict(
            target=types.SimpleNamespace(
                id="1.21-fabric", minecraft="1.21", loader="fabric", java=21
            ),
            scenario={},
            settings={},
            game_dir=game_dir,
            server_dir=tmp_path / "server",
            out_dir=tmp_path / "out",
            client_image="img",
            srv_name="srv-container",
            cli_name="cli-container",
            net_name="net",
            token="tok",
            artifact=tmp_path / "automodpack.jar",
            modpack_name="amp-autotest",
            marker_rel=Path("config/amp-autotest-marker.json"),
            scenario_files=[],
            vars=vars_values,
        )
        defaults.update(overrides)
        ctx = Context(**defaults)
        ctx.prep_name = ctx.prep_name or "prep-container"
        ctx.capabilities = Capabilities()
        ctx.client = FakeClientRuntime(ctx.cli_name, ctx.prep_name)
        ctx.server = FakeServerRuntime(ctx.srv_name)
        ctx.hosting = FakeHostingRuntime()
        ctx.server_workspace = HostPathServerWorkspace(ctx.server_dir)
        ctx.running_provider = lambda: None  # client is always "running" in tests
        return ctx

    return _make
