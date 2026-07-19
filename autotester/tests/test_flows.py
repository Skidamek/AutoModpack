"""End-to-end engine tests: run the *real* shipped scenarios and macro library
through a fake bridge, with the Docker lifecycle verbs stubbed out.

This proves the declarative pipeline (config -> macros -> executor -> verbs ->
selectors/conditions/templating) works against the actual scenario files, with
no Docker, HeadlessMC, or Minecraft server involved.
"""
from __future__ import annotations

import pytest

from automodpack_autotester.config import load_macros, load_scenarios, parse_server_files
from automodpack_autotester.engine import run_flow
from automodpack_autotester.engine.registry import verb

from .conftest import FakeBridge


# ── stub the lifecycle verbs the runner normally provides (need Docker) ────


@verb("launch_server", "wait_server")
def _noop(ctx, step):
    pass


@verb("launch_client")
def _launch_client(ctx, step):
    ctx.bridge.exited = False  # a fresh client process is running


@verb("wait_bridge")
def _wait_bridge(ctx, step):
    assert ctx.bridge is not None


@verb("connect")
def _connect(ctx, step):
    ctx.bridge.connect(ctx.srv_name, int(step.get("port", 25565)))


@verb("quit")
def _quit(ctx, step):
    ctx.bridge.request("quit")


@verb("disconnect")
def _disconnect(ctx, step):
    ctx.bridge.request("disconnect")


@verb("wait_client_exit")
def _wait_client_exit(ctx, step):
    assert ctx.bridge.exited, "client did not exit after restart"


@verb("wait_exit")
def _wait_exit(ctx, step):
    pass


@verb("stage_modpack")
def _stage_modpack(ctx, step):
    pass


@verb("wait_join")
def _wait_join(ctx, step):
    assert ctx.gui().get("screenClass") is None, "player never reached in-game"


# The verb registry is a process-global; importing the runner elsewhere in the
# suite (e.g. tests/test_meta.py) registers the *real* Docker lifecycle verbs and
# clobbers the stubs above. Re-install the stubs before each flow test so these
# tests are independent of module import order.
_STUBS = {
    "launch_server": _noop, "wait_server": _noop,
    "launch_client": _launch_client, "wait_bridge": _wait_bridge,
    "connect": _connect, "quit": _quit, "disconnect": _disconnect,
    "wait_client_exit": _wait_client_exit, "wait_exit": _wait_exit,
    "stage_modpack": _stage_modpack, "wait_join": _wait_join,
}


@pytest.fixture(autouse=True)
def _use_stub_verbs():
    from automodpack_autotester.engine.registry import VERBS

    saved = {name: VERBS.get(name) for name in _STUBS}
    VERBS.update(_STUBS)
    try:
        yield
    finally:
        for name, fn in saved.items():
            if fn is None:
                VERBS.pop(name, None)
            else:
                VERBS[name] = fn


# ── helpers ───────────────────────────────────────────────────────────────


def _ctx_for(make_ctx, scenario: dict):
    sf = parse_server_files(scenario)
    ctx = make_ctx(
        scenario=scenario,
        modpack_name=sf.modpack_name,
        marker_rel=sf.marker,
        scenario_files=sf.files,
        expected_mods=sf.expected_mods,
    )
    ctx.bridge = FakeBridge(ctx)
    ctx.logs_provider = lambda which, tail=None: (
        "[Server thread/INFO]: Certificate fingerprint: AB:CD:EF:01:23"
        if which == "server"
        else ""
    )
    return ctx


# ── tests ─────────────────────────────────────────────────────────────────


def test_download_only_flow(make_ctx):
    scenario = load_scenarios()["download-only"]
    ctx = _ctx_for(make_ctx, scenario)

    results = run_flow(ctx, scenario, lib=load_macros())

    assert all(r["ok"] for r in results), [r for r in results if not r["ok"]]
    assert ctx.bridge.fingerprint == "AB:CD:EF:01:23"
    root = ctx.game_dir / ctx.selected_modpack_dir()
    for rel, _ in ctx.scenario_files:
        assert (root / rel).exists(), f"missing synced file {rel}"


def test_sync_flow_round_trip(make_ctx):
    scenario = load_scenarios()["sync"]
    ctx = _ctx_for(make_ctx, scenario)

    results = run_flow(ctx, scenario, lib=load_macros())

    assert all(r["ok"] for r in results), [r for r in results if not r["ok"]]
    names = [r["name"] for r in results]
    # the round trip really restarted and rejoined
    assert any("relaunch" in n for n in names), names
    assert any("in-game" in n for n in names), names
    assert ctx.bridge.exited  # final quit


def test_scenarios_only_reference_known_verbs():
    """Static guard: every verb named in the shipped scenarios/macros exists."""
    from automodpack_autotester.engine.registry import VERBS

    macros = load_macros()
    scenarios = load_scenarios()

    def verbs_in(steps):
        for raw in steps:
            if isinstance(raw, str):
                if raw not in macros:
                    yield raw
            elif isinstance(raw, dict):
                if "do" in raw:
                    yield raw["do"]
                for key in ("steps",):
                    if isinstance(raw.get(key), list):
                        yield from verbs_in(raw[key])

    used: set[str] = set()
    for seq in macros.values():
        used.update(verbs_in(seq))
    for sc in scenarios.values():
        used.update(verbs_in(sc.get("flow", [])))
        for name in sc.get("flow", []):
            if isinstance(name, dict) and "use" in name:
                used.update(verbs_in(macros.get(name["use"], [])))

    unknown = {v for v in used if v not in VERBS}
    assert not unknown, f"scenarios reference unregistered verbs: {unknown}"
