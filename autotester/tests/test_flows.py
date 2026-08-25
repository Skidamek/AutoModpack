"""End-to-end engine tests: run the *real* shipped scenarios and macro library
through a fake bridge, with the Docker lifecycle verbs stubbed out.

This proves the declarative pipeline (config -> macros -> executor -> verbs ->
selectors/conditions/templating) works against the actual scenario files, with
no Docker, HeadlessMC, or Minecraft server involved.
"""

from __future__ import annotations

import hashlib
import json
import shutil
from contextlib import contextmanager
from pathlib import Path

import pytest
from automodpack_autotester.config import (
    CLIENT_GENERATION_STATE_PATHS,
    load_macros,
    load_scenarios,
    parse_server_files,
)
from automodpack_autotester.engine import run_flow, steps_io, steps_ui
from automodpack_autotester.engine.registry import VERBS
from automodpack_autotester import runner
from automodpack_autotester.mod_fixtures import (
    assert_valid_mod_fixture,
    valid_mod_jar_bytes,
)

from .conftest import FakeBridge

# ── stub the lifecycle verbs the runner normally provides (need Docker) ────


def _noop(ctx, step):
    pass


def _launch_client(ctx, step):
    if ctx.bridge.exited:
        ctx.bridge.screen = "title"
    ctx.bridge.exited = False  # a fresh client process is running
    connection_path = (
        ctx.game_dir
        / "automodpack"
        / "client"
        / "data"
        / "packs"
        / "packaaa"
        / "connection.json"
    )
    try:
        connection = json.loads(connection_path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError):
        connection = {}
    if (connection.get("secrets", {}) or {}).get(ctx.vars.get("bootstrap_origin")):
        objects = ctx.game_dir / "automodpack" / "client" / "data" / "objects"
        objects.mkdir(parents=True, exist_ok=True)
        for payload in (b"bootstrap-a\n", b"bootstrap-b\n"):
            object_path = runner.cas_object(objects, hashlib.sha1(payload).hexdigest())
            object_path.parent.mkdir(parents=True, exist_ok=True)
            object_path.write_bytes(payload)
        ctx.vars["fake_preload_logged"] = True
        ctx.vars["fake_preload_review_logged"] = True
        latest_log = ctx.game_dir / "logs" / "latest.log"
        latest_log.parent.mkdir(parents=True, exist_ok=True)
        latest_log.write_text("Launch apply acquired 2 complete modpack objects in 1ms\nLaunch apply is waiting for first-install review\n", encoding="utf-8")


def _wait_bridge(ctx, step):
    assert ctx.bridge is not None


def _seed_bootstrap(ctx, step):
    """Model the live bootstrap writer for the no-Docker release-gate flow test."""
    origin = f"{ctx.srv_name}:25565"
    ctx.vars.update(
        {
            "bootstrap_origin": origin,
            "bootstrap_endpoint": origin,
            "bootstrap_fingerprint": str(ctx.vars.get("fingerprint", "")),
            "bootstrap_modpack_id": "packaaa",
            "bootstrap_connection_mode": "HOLEPUNCH",
        }
    )
    ctx.bridge.bootstrap = True
    ctx.vars["fake_bootstrap_imported"] = True
    data = ctx.game_dir / "automodpack" / "client" / "data"
    (data / "packs" / "packaaa").mkdir(parents=True, exist_ok=True)
    (ctx.game_dir / "automodpack" / "client-config.json").write_text(
        json.dumps({"selectedModpackId": "packaaa"}), encoding="utf-8"
    )
    (data / "known-hosts.json").write_text(
        json.dumps(
            {
                "hosts": {
                    origin: {
                        "fingerprint": str(ctx.vars.get("fingerprint", ""))
                        .replace(":", "")
                        .lower(),
                        "reason": "SEED",
                    }
                }
            }
        ), encoding="utf-8"
    )
    connection_path = data / "packs" / "packaaa" / "connection.json"
    previous_connection = {}
    if connection_path.is_file():
        previous_connection = json.loads(connection_path.read_text(encoding="utf-8"))
    connection_path.write_text(
        json.dumps(
            {
                "connection": {
                    "origin": origin,
                    "endpoint": origin,
                    "connectionMode": "HOLEPUNCH",
                },
                "secrets": previous_connection.get("secrets", {}),
            }
        ), encoding="utf-8"
    )
    projection_root = ctx.server_dir / "automodpack" / "server"
    projection_root.mkdir(parents=True, exist_ok=True)
    payloads = {
        "config/bootstrap-a.txt": b"bootstrap-a\n",
        "config/bootstrap-b.txt": b"bootstrap-b\n",
    }
    files = {}
    for path, payload in payloads.items():
        sha1 = hashlib.sha1(payload).hexdigest()
        files[path] = {"sha1": sha1, "size": str(len(payload))}
    (projection_root / "current-projection.json").write_text(
        json.dumps({"modpackId": "packaaa", "groups": {"main": {"files": files}}}), encoding="utf-8"
    )


def _connect(ctx, step):
    ctx.bridge.connect(ctx.srv_name, int(step.get("port", 25565)))


def _quit(ctx, step):
    ctx.bridge.request("quit")


def _disconnect(ctx, step):
    ctx.bridge.request("disconnect")


def _wait_client_exit(ctx, step):
    assert ctx.bridge.exited, "client did not exit after restart"


def _wait_exit(ctx, step):
    pass


def _reset_client_generation(ctx, step):
    ctx.bridge._reset_client_generation()
    for relative in CLIENT_GENERATION_STATE_PATHS:
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


def _reset_isolated_client_objects(ctx, step):
    objects = ctx.game_dir / "automodpack" / "client" / "data" / "objects"
    if objects.is_dir():
        shutil.rmtree(objects)
    else:
        objects.unlink(missing_ok=True)


def _stage_modpack(ctx, step):
    if step.get("recordOnly") and ctx.bridge is not None:
        ctx.bridge.secondary_pack = True
        ctx.bridge.pack_b_files = [
            (
                Path(entry["path"]),
                valid_mod_jar_bytes(entry["fixture"], ctx.target.minecraft)
                if "fixture" in entry
                else str(entry.get("content", "")),
            )
            for entry in step.get("files", [])
        ]


def _publish_server_generation(ctx, step):
    ctx.vars["published_server_generation"] = int(step.get("generation", 1))
    if ctx.bridge is not None:
        ctx.bridge.update_available = True


def _compact_server_history(ctx, step):
    """Record an explicit fake receipt; Docker remains runtime authority."""
    ctx.vars["fake_server_history_compacted"] = {
        "receipt": "fake-only",
        "runtimeAuthority": "Docker runner",
    }


def _rollback_server_generation(ctx, step):
    """Record a fake receipt; Docker remains runtime authority for rollback."""
    ctx.vars["fake_server_generation_rollback"] = {
        "receipt": "fake-only",
        "runtimeAuthority": "Docker runner",
        "command": ["rcon-cli", "automodpack", "generate", "revert", "<ancestor>", "confirm", "notes", step.get("notes", "")],
        "notes": step.get("notes", ""),
    }


def _collect_server_objects(ctx, step):
    """Record a fake receipt; Docker remains runtime authority for object GC."""
    ctx.vars["fake_server_object_gc"] = {
        "receipt": "fake-only",
        "runtimeAuthority": "Docker runner",
        "command": ["rcon-cli", "automodpack", "generate", "storage", "collect", "confirm"],
    }


def _wait_join(ctx, step):
    assert ctx.gui().get("screenClass") is None, "player never reached in-game"


def _assert_preload_rejected(ctx, step):
    objects = ctx.game_dir / "automodpack" / "client" / "data" / "objects"
    if objects.is_dir():
        assert not any(objects.iterdir())


def _assert_client_objects_absent(ctx, step):
    objects = ctx.game_dir / "automodpack" / "client" / "data" / "objects"
    assert not objects.exists()


def _assert_preload_acquired(ctx, step):
    assert ctx.vars.get("fake_preload_logged")


_BUILTIN_VERBS = {
    "wait_file": steps_io.wait_file,
    "wait_file_content": steps_io.wait_file_content,
    "wait_files": steps_io.wait_files,
    "verify_files": steps_io.verify_files,
    "verify_mods": steps_io.verify_mods,
    "wait_generation": steps_io.wait_generation,
    "assert_file_content": steps_io.assert_file_content,
    "write_file": steps_io.write_file,
    "mutate_client_file": steps_io.mutate_client_file,
    "mutate_active_object": steps_io.mutate_active_object,
    "assert_client_object": steps_io.assert_client_object,
    "mutate_preservation_object": steps_io.mutate_preservation_object,
    "assert_bootstrap_import": steps_io.assert_bootstrap_import,
    "assert_authenticated_secret": steps_io.assert_authenticated_secret,
    "seed_unowned_local_file": steps_io.seed_unowned_local_file,
    "seed_same_path_conflict": steps_io.seed_same_path_conflict,
    "seed_mod_fixture": steps_io.seed_mod_fixture,
    "assert_mod_fixture": steps_io.assert_mod_fixture,
    "assert_preservation_claim": steps_io.assert_preservation_claim,
    "assert_generation": steps_io.assert_generation,
    "click": steps_ui.click,
    "type": steps_ui.type_,
    "paste": steps_ui.type_,
    "screenshot": steps_ui.screenshot,
    "wait_for": steps_ui.wait_for,
    "assert": steps_ui.assert_,
    "sleep": steps_ui.sleep,
}

_FAKE_VERBS = {
    "launch_server": _noop,
    "prepare_client": _noop,
    "wait_server": _noop,
    "launch_client": _launch_client,
    "wait_bridge": _wait_bridge,
    "seed_bootstrap": _seed_bootstrap,
    "connect": _connect,
    "quit": _quit,
    "disconnect": _disconnect,
    "wait_client_exit": _wait_client_exit,
    "wait_exit": _wait_exit,
	"reset_client_generation": _reset_client_generation,
	"reset_isolated_client_objects": _reset_isolated_client_objects,
    "stage_modpack": _stage_modpack,
    "publish_server_generation": _publish_server_generation,
    "compact_server_history": _compact_server_history,
    "rollback_server_generation": _rollback_server_generation,
    "collect_server_objects": _collect_server_objects,
    "wait_join": _wait_join,
    "assert_preload_rejected": _assert_preload_rejected,
    "assert_client_objects_absent": _assert_client_objects_absent,
    "assert_preload_acquired": _assert_preload_acquired,
}


@contextmanager
def _install_flow_verbs():
    """Install the exact fake-flow map and restore the process registry afterward."""
    saved = VERBS.copy()
    VERBS.clear()
    VERBS.update(_BUILTIN_VERBS)
    VERBS.update(_FAKE_VERBS)
    try:
        yield
    finally:
        VERBS.clear()
        VERBS.update(saved)


@pytest.fixture
def flow_verbs():
    with _install_flow_verbs():
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
        else "\n".join(
            filter(
                None,
                [
                    "Imported seeded certificate pin for origin amp-server:25565"
                    if ctx.vars.get("fake_bootstrap_imported")
                    else "",
                    "Launch apply acquired 2 complete modpack objects in 1ms"
                    if ctx.vars.get("fake_preload_logged")
                    else "",
                    "Launch apply is waiting for first-install review"
                    if ctx.vars.get("fake_preload_review_logged")
                    else "",
                ],
            )
        )
    )
    return ctx


# ── tests ─────────────────────────────────────────────────────────────────


def test_download_only_flow(make_ctx, flow_verbs):
    scenario = load_scenarios()["download-only"]
    ctx = _ctx_for(make_ctx, scenario)

    results = run_flow(ctx, scenario, lib=load_macros())

    assert all(r["ok"] for r in results), [r for r in results if not r["ok"]]
    assert ctx.bridge.fingerprint == "AB:CD:EF:01:23"
    root = ctx.game_dir / ctx.active_projection_dir()
    for rel, _ in ctx.scenario_files:
        assert (root / rel).exists(), f"missing synced file {rel}"


def test_sync_flow_round_trip(make_ctx, flow_verbs):
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
        {"id": 6, "text": "No, back to the game", "enabled": True, "visible": True, "key": "automodpack.restart.cancel"},
        {"id": 4, "text": "Yes, close the game", "enabled": True, "visible": True, "key": "automodpack.restart.confirm"},
        {"id": 40, "text": "View changelogs", "enabled": True, "visible": True},
    ]


def test_fake_new_repair_and_preservation_ui_states(make_ctx):
    ctx = make_ctx()
    bridge = FakeBridge(ctx)
    ctx.bridge = bridge

    # First-install cleanup is explicit and reversible before confirmation; archive is the safe default.
    for name in ("local-one.jar", "local-two.jar"):
        (ctx.game_dir / "mods" / name).write_bytes(b"local")
    bridge.screen = "first_connection"
    assert any(button["text"] == "Archive 2 existing mod files" for button in bridge.gui()["buttons"])
    bridge.click(89)
    assert any(button["text"] == "Keep 2 existing mod files in mods" for button in bridge.gui()["buttons"])
    bridge.click(89)
    assert any(button["text"] == "Archive 2 existing mod files" for button in bridge.gui()["buttons"])
    assert any(button["text"] == "Download" for button in bridge.gui()["buttons"])

    # Repair is available only for the active pack. Its destructive choices default to keep.
    bridge.pack_a_installed = True
    bridge.secondary_pack = True
    bridge.detail_pack = "B"
    bridge.screen = "details"
    assert not any(button["text"] == "Repair" for button in bridge.gui()["buttons"])
    bridge.detail_pack = "A"
    assert any(button["text"] == "Repair" for button in bridge.gui()["buttons"])
    bridge.click(70)
    buttons = bridge.gui()["buttons"]
    # Defaults: unchecked on both rows - each unchecked box is a removal/reset consent.
    assert any(button["text"] == "[ ] Keep changes in config/pack-shared-editable.txt" for button in buttons)
    assert any(button["text"] == "[ ] Keep 2 unowned mods" for button in buttons)
    bridge.click(95)  # Checking the editable row keeps the player's changes.
    assert any(button["text"] == "[x] Keep changes in config/pack-shared-editable.txt" for button in bridge.gui()["buttons"])
    bridge.click(95)
    bridge.click(97)  # Checking the unowned row opts into keeping them.
    assert any(button["text"] == "[x] Keep 2 unowned mods" for button in bridge.gui()["buttons"])
    bridge.click(100)
    assert bridge.gui()["screenClass"] == "ModpackDetailsScreen"

    # Storage verification returns to the same screen after a corrupt claimed object fails.
    object_path = bridge._vault_claim("packaaa", "config/amp-autotest-gamma.cfg", b"gamma", "SERVER_REMOVAL")
    bridge.vault_claim_available = True
    bridge.screen = "storage"
    bridge.click(91)
    assert bridge.storage_verified
    object_path.write_bytes(b"damaged")
    bridge.click(91)
    assert bridge.gui()["screenClass"] == "ErrorScreen"
    bridge.click(93)
    assert ctx.vars["fake_error_details_copied"]
    bridge.click(94)
    assert bridge.gui()["screenClass"] == "ClientStorageMaintenanceScreen"

    # Restore refuses to overwrite an active owned path. Save-copy and two-click deletion remain available.
    active_owned = ctx.game_dir / "automodpack/client/active/config/amp-autotest-gamma.cfg"
    active_owned.parent.mkdir(parents=True, exist_ok=True)
    active_owned.write_bytes(b"server")
    object_path.write_bytes(b"gamma")
    bridge.screen = "preservation"
    bridge.click(83)
    bridge.click(84)
    assert bridge.gui()["screenClass"] == "ErrorScreen"
    bridge.click(94)
    assert bridge.gui()["screenClass"] == "PreservationVaultScreen"
    bridge.click(83)  # Reselecting a row cancels any pending destructive action.
    bridge.click(90)
    bridge.click(90)
    assert not (ctx.game_dir / "automodpack/client/preservation/packaaa/claims.json").exists()


def test_release_gate_flow(make_ctx, flow_verbs):
    """Exercise the declarative release flow; Docker remains runtime authority."""
    scenario = load_scenarios()["all"]
    ctx = _ctx_for(make_ctx, scenario)

    results = run_flow(ctx, scenario, lib=load_macros())

    assert all(r["ok"] for r in results), [r for r in results if not r["ok"]]
    assert ctx.bridge.exited
    assert ctx.vars.get("fake_server_history_compacted") == {
        "receipt": "fake-only",
        "runtimeAuthority": "Docker runner",
    }
    assert ctx.vars["fake_server_generation_rollback"] == {
        "receipt": "fake-only",
        "runtimeAuthority": "Docker runner",
        "command": ["rcon-cli", "automodpack", "generate", "revert", "<ancestor>", "confirm", "notes", "Release gate rollback: restore the retained ancestor."],
        "notes": "Release gate rollback: restore the retained ancestor.",
    }
    assert ctx.vars["fake_server_object_gc"]["command"] == ["rcon-cli", "automodpack", "generate", "storage", "collect", "confirm"]
    assert not ctx.bridge.secondary_pack
    assert ctx.bridge.screenshots, "release gate must exercise render screenshots"
    assert "all-local-storage-before" in ctx.bridge.screenshots
    assert "all-local-storage-after" in ctx.bridge.screenshots
    active = ctx.game_dir / ctx.active_projection_dir()
    assert ctx.bridge.selected_pack == "A"
    assert not (ctx.game_dir / "automodpack/client/active-state.json").exists()
    assert not (active / "config/amp-autotest-alpha.txt").exists()
    assert not (
        ctx.game_dir
        / "automodpack/client/overlays/packaaa/config/pack-shared-editable.txt"
    ).exists()
    client_config = json.loads(
        (ctx.game_dir / "automodpack/client-config.json").read_text(encoding="utf-8")
    )
    assert client_config.get("selectedModpackId") == ""
    assert not (ctx.game_dir / "mods/local-unowned.jar").exists()
    preservation = (
        ctx.game_dir
        / "automodpack/client/preservation/packbbb/claims.json"
    )
    assert not preservation.exists()
    assert not (ctx.game_dir / "mods/amp-autotest-conflict.jar").exists()
    assert (ctx.game_dir / "config/amp-autotest-baseline.json").read_text(
        encoding="utf-8"
    ) == "local-baseline\n"
