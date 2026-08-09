"""Contracts for CLI and runner helper behavior outside Docker."""

from __future__ import annotations

import hashlib
import json
import threading
import time
import types
from pathlib import Path

import pytest
from automodpack_autotester import cli, runner
from automodpack_autotester.engine.steps_io import wait_file_content, wait_generation


def _target(**kw):
    base = {
        "id": "1.21.1-neoforge",
        "minecraft": "1.21.1",
        "loader": "neoforge",
        "java": 21,
    }
    base.update(kw)
    return types.SimpleNamespace(**base)


def test_targets_command_uses_configured_defaults(monkeypatch, capsys):
    target = _target(id="selected")
    monkeypatch.setattr(
        cli,
        "load_settings",
        lambda: {"run": {"scenario": "custom", "target": "selected"}},
    )
    monkeypatch.setattr(cli, "load_scenarios", lambda: {"custom": {}})
    monkeypatch.setattr(cli, "load_targets", lambda: {"selected": target})
    monkeypatch.setattr(cli, "load_macros", dict)
    monkeypatch.setattr(cli, "validate_scenario", lambda *_: [])
    monkeypatch.setattr(cli, "scenario_matches_target", lambda *_: True)

    assert cli._cmd_targets(None, None) == 0
    assert json.loads(capsys.readouterr().out) == ["selected"]


# ── validation ─────────────────────────────────────────────────────────────


def test_server_history_compaction_uses_registered_command():
    source = (Path(__file__).parents[1] / "automodpack_autotester/runner.py").read_text(
        encoding="utf-8"
    )

    assert (
        '["rcon-cli", "automodpack", "generate", "storage", "compact", "confirm"]'
        in source
    )
    assert (
        '["rcon-cli", "automodpack", "generate", "history", "storage", "compact", "confirm"]'
        not in source
    )


def test_seed_client_options_preserves_existing_settings(tmp_path):
    options_path = tmp_path / "options.txt"
    options_path.write_text(
        "narrator:0\nfoo:bar\nskipMultiplayerWarning:false\n", encoding="utf-8"
    )

    runner._seed_client_options(tmp_path)

    assert (
        options_path.read_text(encoding="utf-8")
        == "narrator:0\nfoo:bar\nskipMultiplayerWarning:true\n"
    )


def test_wait_file_content_waits_for_replaced_payload(make_ctx):
    ctx = make_ctx()
    path = ctx.game_dir / "automodpack/client/active/config/update.txt"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text("old\n", encoding="utf-8")

    def replace_payload():
        time.sleep(0.05)
        path.write_text("new\n", encoding="utf-8")

    writer = threading.Thread(target=replace_payload)
    writer.start()
    try:
        wait_file_content(
            ctx,
            {
                "path": "automodpack/client/active/config/update.txt",
                "content": "new\n",
                "timeout": "1s",
                "poll": "10ms",
            },
        )
    finally:
        writer.join()


# ── artifact and staged manifest handling ────────────────────────────────────


def test_artifact_resolution_uses_target_id(tmp_path):
    target = _target(
        id="26.1-fabric",
        minecraft="26.1.2",
        loader="fabric",
        artifact_pattern="automodpack-mc{id}-*.jar",
    )
    artifact = tmp_path / "automodpack-mc26.1-fabric-test.jar"
    artifact.touch()

    assert runner._resolve_artifact(target, tmp_path) == artifact.resolve()


def test_artifact_resolution_rejects_ambiguous_matches(tmp_path):
    target = _target(artifact_pattern="automodpack-mc{id}-*.jar")
    (tmp_path / "automodpack-mc1.21.1-neoforge-a.jar").touch()
    (tmp_path / "automodpack-mc1.21.1-neoforge-b.jar").touch()
    with pytest.raises(RuntimeError, match="Ambiguous artifacts"):
        runner._resolve_artifact(target, tmp_path)


def test_wait_generation_requires_committed_state_and_matching_record(make_ctx):
    ctx = make_ctx()
    marker = ctx.game_dir / "automodpack/client/active/config/amp-autotest-marker.json"
    marker.parent.mkdir(parents=True, exist_ok=True)
    marker.write_text("projected\n", encoding="utf-8")

    with pytest.raises(TimeoutError, match="active generation state was not committed"):
        wait_generation(ctx, {"timeout": "1ms", "poll": "1ms"})

    generation_id = "a" * 40
    record = (
        ctx.game_dir / "automodpack/client/records" / generation_id / "manifest.json"
    )
    record.parent.mkdir(parents=True, exist_ok=True)
    record.write_text(
        json.dumps(
            {"modpackId": "packaaa", "generation": {"generationId": generation_id}}
        ),
        encoding="utf-8",
    )
    state = ctx.game_dir / "automodpack/client/active-state.json"
    state.write_text(
        json.dumps(
            {"modpackId": "packaaa", "generationId": generation_id, "status": "ACTIVE"}
        ),
        encoding="utf-8",
    )

    wait_generation(ctx, {"timeout": "1s", "poll": "1ms"})


def test_wait_generation_requires_expected_patch_notes(make_ctx):
    ctx = make_ctx()
    generation_id = "b" * 40
    record = (
        ctx.game_dir / "automodpack/client/records" / generation_id / "manifest.json"
    )
    record.parent.mkdir(parents=True, exist_ok=True)
    record.write_text(
        json.dumps(
            {
                "modpackId": "packaaa",
                "generation": {"generationId": generation_id, "patchNotes": "stale"},
            }
        ),
        encoding="utf-8",
    )
    state = ctx.game_dir / "automodpack/client/active-state.json"
    state.write_text(
        json.dumps(
            {"modpackId": "packaaa", "generationId": generation_id, "status": "ACTIVE"}
        ),
        encoding="utf-8",
    )

    with pytest.raises(TimeoutError, match="active generation state was not committed"):
        wait_generation(
            ctx, {"patchNotes": "expected", "timeout": "1ms", "poll": "1ms"}
        )

    record.write_text(
        json.dumps(
            {
                "modpackId": "packaaa",
                "generation": {"generationId": generation_id, "patchNotes": "expected"},
            }
        ),
        encoding="utf-8",
    )
    wait_generation(ctx, {"patchNotes": "expected", "timeout": "1s", "poll": "1ms"})


def test_client_data_root_stays_pinned_across_relaunch_staging(make_ctx, monkeypatch):
    ctx = make_ctx()
    ctx.artifact.write_bytes(b"autotest-artifact")
    monkeypatch.setattr(runner, "_run_container", lambda **_kwargs: None)
    monkeypatch.setattr(runner, "_assert_running", lambda _name: None)
    monkeypatch.setattr(runner, "_jitter_sleep", lambda *_args, **_kwargs: None)

    runner._launch_client(ctx)
    marker = ctx.game_dir / "automodpack/data-root.json"
    before = json.loads(marker.read_text())

    runner._v_stage_modpack(
        ctx,
        {
            "recordOnly": True,
            "packId": "packbbb",
            "packName": "Pack B",
            "files": [{"path": "config/b.txt", "content": "b"}],
        },
    )

    assert before == {"root": "/work/game/automodpack/client/data", "shared": False}
    assert json.loads(marker.read_text()) == before


def test_connect_screen_classifier_does_not_loop_on_first_connection():
    assert runner._is_connecting_screen(
        "net.minecraft.client.gui.screens.ConnectScreen"
    )
    assert runner._is_connecting_screen("net.minecraft.client.gui.screens.class_412")
    assert not runner._is_connecting_screen(
        "pl.skidam.automodpack.client.ui.FirstConnectScreen"
    )


def test_connection_failure_screen_is_retried_instead_of_reported_as_connected():
    assert runner._is_connection_failure_screen("net.minecraft.class_419")
    assert runner._is_connection_failure_screen(
        "net.minecraft.client.gui.screens.DisconnectedScreen"
    )
    assert not runner._is_connection_failure_screen(
        "pl.skidam.automodpack.client.ui.FirstConnectScreen"
    )


def test_assert_preload_acquired_checks_complete_projection(make_ctx):
    ctx = make_ctx()
    payloads = {"a" * 40: b"first", "b" * 40: b"second"}
    projection = {
        "groups": {
            "main": {
                "files": {
                    "config/a.txt": {
                        "sha1": hashlib.sha1(payloads["a" * 40]).hexdigest(),
                        "size": str(len(payloads["a" * 40])),
                    },
                    "config/b.txt": {
                        "sha1": hashlib.sha1(payloads["b" * 40]).hexdigest(),
                        "size": str(len(payloads["b" * 40])),
                    },
                    "config/a-copy.txt": {
                        "sha1": hashlib.sha1(payloads["a" * 40]).hexdigest(),
                        "size": str(len(payloads["a" * 40])),
                    },
                }
            }
        }
    }
    projection_path = (
        ctx.server_dir / "automodpack" / "server" / "current-projection.json"
    )
    projection_path.parent.mkdir(parents=True, exist_ok=True)
    projection_path.write_text(json.dumps(projection), encoding="utf-8")
    objects = runner._ensure_client_data_root(ctx.game_dir) / "objects"
    objects.mkdir(parents=True, exist_ok=True)
    for payload in payloads.values():
        (objects / hashlib.sha1(payload).hexdigest()).write_bytes(payload)
    ctx.logs_provider = lambda _which, _tail=None: (
        "Preloaded 2 complete modpack objects in 1ms"
    )

    runner._v_assert_preload_acquired(ctx, {})

    assert ctx.vars["preloaded_object_count"] == 2


def test_wait_exit_or_alive_tolerates_still_running(monkeypatch):
    def never_exits(name, timeout):
        raise TimeoutError("still running")

    monkeypatch.setattr(runner, "_wait_exited", never_exits)
    ctx = types.SimpleNamespace(cli_name="c")
    with pytest.raises(TimeoutError):
        runner._v_wait_client_exit(ctx, {})  # default: must exit
    runner._v_wait_client_exit(ctx, {"or_alive": True})  # tolerated


def test_wait_exit_expect_clean_and_crash(monkeypatch):
    monkeypatch.setattr(runner, "_wait_exited", lambda name, timeout: None)
    ctx = types.SimpleNamespace(cli_name="c")

    monkeypatch.setattr(runner, "_exit_code", lambda name: 0)
    runner._v_wait_client_exit(ctx, {"expect": "clean"})
    with pytest.raises(AssertionError):
        runner._v_wait_client_exit(ctx, {"expect": "crash"})

    monkeypatch.setattr(runner, "_exit_code", lambda name: 1)
    runner._v_wait_client_exit(ctx, {"expect": "crash"})
    with pytest.raises(AssertionError):
        runner._v_wait_client_exit(ctx, {"expect": "clean"})


# ── verb discovery ──────────────────────────────────────────────────────────
