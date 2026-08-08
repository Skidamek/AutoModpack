"""End-to-end engine tests: run the *real* shipped scenarios and macro library
through a fake bridge, with the Docker lifecycle verbs stubbed out.

This proves the declarative pipeline (config -> macros -> executor -> verbs ->
selectors/conditions/templating) works against the actual scenario files, with
no Docker, HeadlessMC, or Minecraft server involved.
"""
from __future__ import annotations

import json
import hashlib
import shutil
from pathlib import Path

import pytest

from automodpack_autotester.config import load_macros, load_scenarios, parse_server_files
from automodpack_autotester.engine import run_flow
from automodpack_autotester.engine.registry import temporary, verb
from automodpack_autotester.mod_fixtures import assert_valid_mod_fixture, valid_mod_jar_bytes

from .conftest import FakeBridge


# ── stub the lifecycle verbs the runner normally provides (need Docker) ────


@verb("launch_server", "wait_server")
def _noop(ctx, step):
    pass


@verb("launch_client")
def _launch_client(ctx, step):
    if ctx.bridge.exited:
        ctx.bridge.screen = "title"
    ctx.bridge.exited = False  # a fresh client process is running
    connection_path = ctx.game_dir / "automodpack" / "client" / "data" / "packs" / "packaaa" / "connection.json"
    try:
        connection = json.loads(connection_path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError):
        connection = {}
    if (connection.get("secrets", {}) or {}).get(ctx.vars.get("bootstrap_origin")):
        objects = ctx.game_dir / "automodpack" / "client" / "data" / "objects"
        objects.mkdir(parents=True, exist_ok=True)
        for payload in (b"bootstrap-a\n", b"bootstrap-b\n"):
            (objects / hashlib.sha1(payload).hexdigest()).write_bytes(payload)
        ctx.vars["fake_preload_logged"] = True


@verb("wait_bridge")
def _wait_bridge(ctx, step):
    assert ctx.bridge is not None


@verb("seed_bootstrap")
def _seed_bootstrap(ctx, step):
    """Model the live bootstrap writer for the no-Docker release-gate flow test."""
    origin = f"{ctx.srv_name}:25565"
    ctx.vars.update({
        "bootstrap_origin": origin,
        "bootstrap_endpoint": origin,
        "bootstrap_fingerprint": str(ctx.vars.get("fingerprint", "")),
        "bootstrap_modpack_id": "packaaa",
        "bootstrap_connection_mode": "HOLEPUNCH",
    })
    ctx.bridge.bootstrap = True
    ctx.vars["fake_bootstrap_imported"] = True
    data = ctx.game_dir / "automodpack" / "client" / "data"
    (data / "packs" / "packaaa").mkdir(parents=True, exist_ok=True)
    (ctx.game_dir / "automodpack" / "client-config.json").write_text(json.dumps({"selectedModpackId": "packaaa"}))
    (data / "known-hosts.json").write_text(json.dumps({"hosts": {origin: {
        "fingerprint": str(ctx.vars.get("fingerprint", "")).replace(":", "").lower(), "reason": "SEED",
    }}}))
    connection_path = data / "packs" / "packaaa" / "connection.json"
    previous_connection = {}
    if connection_path.is_file():
        previous_connection = json.loads(connection_path.read_text(encoding="utf-8"))
    connection_path.write_text(json.dumps({
        "connection": {"origin": origin, "endpoint": origin, "connectionMode": "HOLEPUNCH"},
        "secrets": previous_connection.get("secrets", {}),
    }))
    projection_root = ctx.server_dir / "automodpack" / "server"
    projection_root.mkdir(parents=True, exist_ok=True)
    payloads = {"config/bootstrap-a.txt": b"bootstrap-a\n", "config/bootstrap-b.txt": b"bootstrap-b\n"}
    files = {}
    for path, payload in payloads.items():
        sha1 = hashlib.sha1(payload).hexdigest()
        files[path] = {"sha1": sha1, "size": str(len(payload))}
    (projection_root / "current-projection.json").write_text(json.dumps({"modpackId": "packaaa", "groups": {"main": {"files": files}}}))


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


@verb("reset_client_generation")
def _reset_client_generation(ctx, step):
    for relative in ("records", "active", "active-state.json", "data/objects"):
        path = ctx.game_dir / "automodpack" / "client" / relative
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink(missing_ok=True)
    ctx.vars["client_generation_reset"] = True
    ctx.bridge.synced = False
    ctx.bridge.bootstrap = True
    ctx.bridge.update_available = False
    ctx.bridge.screen = "title"


@verb("stage_modpack")
def _stage_modpack(ctx, step):
    if step.get("recordOnly") and ctx.bridge is not None:
        ctx.bridge.secondary_pack = True
        ctx.bridge.pack_b_files = [
            (Path(entry["path"]), valid_mod_jar_bytes(entry["fixture"]) if "fixture" in entry else str(entry.get("content", "")))
            for entry in step.get("files", [])
        ]


@verb("publish_server_generation")
def _publish_server_generation(ctx, step):
    ctx.vars["published_server_generation"] = int(step.get("generation", 1))
    if ctx.bridge is not None:
        ctx.bridge.update_available = True


@verb("wait_join")
def _wait_join(ctx, step):
    assert ctx.gui().get("screenClass") is None, "player never reached in-game"


@verb("assert_preload_rejected")
def _assert_preload_rejected(ctx, step):
    objects = ctx.game_dir / "automodpack" / "client" / "data" / "objects"
    if objects.is_dir():
        assert not any(objects.iterdir())


@verb("assert_client_objects_absent")
def _assert_client_objects_absent(ctx, step):
    objects = ctx.game_dir / "automodpack" / "client" / "data" / "objects"
    assert not objects.exists()


@verb("assert_preload_acquired")
def _assert_preload_acquired(ctx, step):
    assert ctx.vars.get("fake_preload_logged")


# The verb registry is a process-global; importing the runner elsewhere in the
# suite (e.g. tests/test_meta.py) registers the *real* Docker lifecycle verbs and
# clobbers the stubs above. Re-install the stubs before each flow test so these
# tests are independent of module import order.
_STUBS = {
    "launch_server": _noop, "wait_server": _noop,
    "launch_client": _launch_client, "wait_bridge": _wait_bridge, "seed_bootstrap": _seed_bootstrap,
    "connect": _connect, "quit": _quit, "disconnect": _disconnect,
    "wait_client_exit": _wait_client_exit, "wait_exit": _wait_exit, "reset_client_generation": _reset_client_generation,
    "stage_modpack": _stage_modpack, "publish_server_generation": _publish_server_generation, "wait_join": _wait_join,
    "assert_preload_rejected": _assert_preload_rejected, "assert_client_objects_absent": _assert_client_objects_absent, "assert_preload_acquired": _assert_preload_acquired,
}


@pytest.fixture(autouse=True)
def _use_stub_verbs():
    with temporary(_STUBS):
        yield


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
        else "\n".join(filter(None, [
            "Imported seeded certificate pin for origin amp-server:25565" if ctx.vars.get("fake_bootstrap_imported") else "",
            "Preloaded 2 complete modpack objects in 1ms" if ctx.vars.get("fake_preload_logged") else "",
        ]))
    )
    return ctx


# ── tests ─────────────────────────────────────────────────────────────────


def test_download_only_flow(make_ctx):
    scenario = load_scenarios()["download-only"]
    ctx = _ctx_for(make_ctx, scenario)

    results = run_flow(ctx, scenario, lib=load_macros())

    assert all(r["ok"] for r in results), [r for r in results if not r["ok"]]
    assert ctx.bridge.fingerprint == "AB:CD:EF:01:23"
    root = ctx.game_dir / ctx.active_projection_dir()
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


def test_fake_restart_screen_matches_production_button_order(make_ctx):
    bridge = FakeBridge(make_ctx())
    bridge.screen = "restart"

    assert bridge.gui()["buttons"] == [
        {"id": 6, "text": "No, back to the game", "enabled": True, "visible": True},
        {"id": 4, "text": "Yes, close the game", "enabled": True, "visible": True},
        {"id": 40, "text": "View changelogs", "enabled": True, "visible": True},
    ]


def test_release_gate_flow(make_ctx):
    scenario = load_scenarios()["all"]
    ctx = _ctx_for(make_ctx, scenario)

    results = run_flow(ctx, scenario, lib=load_macros())

    assert all(r["ok"] for r in results), [r for r in results if not r["ok"]]
    assert ctx.bridge.exited
    assert ctx.bridge.secondary_pack
    assert ctx.bridge.screenshots, "release gate must exercise render screenshots"
    active = ctx.game_dir / ctx.active_projection_dir()
    assert ctx.bridge.selected_pack == "A"
    assert (active / "config/pack-a-only.txt").read_text(encoding="utf-8") == "pack-a-v2\n"
    assert not (active / "config/pack-b.txt").exists()
    assert_valid_mod_fixture(
        (ctx.game_dir / "mods/local-unowned.jar").read_bytes(),
        {"modId": "amp_autotest_unowned", "version": "1.0.0-local-unowned", "marker": "unowned-local"},
    )
    quarantine = ctx.game_dir / "automodpack/client/quarantine/packbbb/conflicts/fake-conflict/payload"
    assert_valid_mod_fixture(
        quarantine.read_bytes(),
        {"modId": "amp_autotest_conflict", "version": "1.0.0-local", "marker": "local"},
    )
    assert not (ctx.game_dir / "mods/amp-autotest-conflict.jar").exists()


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
