"""Contracts for CLI and runner helper behavior outside Docker."""

from __future__ import annotations

import hashlib
import json
import threading
import time
import types
import zipfile
from pathlib import Path

import pytest
from automodpack_autotester import cli, client_steps, runner, server_steps, staging_steps
from automodpack_autotester.config import load_macros
from automodpack_autotester.engine.steps_io import wait_file, wait_file_content, wait_generation
from automodpack_autotester.engine.util import ClientExited


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


def test_target_selection_accepts_a_bounded_batch():
    first = _target(id="first")
    second = _target(id="second")

    requested, selected = cli._select_targets({"first": first, "second": second}, "first,second", {})

    assert requested == [first, second]
    assert selected == requested


def test_sigterm_enters_the_interrupt_cleanup_path():
    with pytest.raises(KeyboardInterrupt):
        cli._interrupt_on_termination(None, None)


def test_partial_matrix_cannot_be_reported_as_green():
    selected = [_target(id="first"), _target(id="second")]

    payload = cli._matrix_payload(selected, {"first": {"target": "first", "ok": True}}, interrupted=False)

    assert payload["ok"] is False
    assert [result["target"] for result in payload["results"]] == ["first", "second"]
    assert payload["results"][1]["error"] == "Case did not produce a terminal result"


def test_connection_path_matrix_runs_every_path(monkeypatch, tmp_path):
    seen = []

    def fake_run_case(target, scenario, **_kwargs):
        mode = scenario["connectionPath"]["mode"]
        seen.append(mode)
        return {"target": target.id, "scenario": scenario["id"], "connectionMode": mode, "ok": True, "duration": 1}

    monkeypatch.setattr(cli, "run_case", fake_run_case)
    result = cli._run_target_cases(
        _target(),
        [
            {"id": "all-direct", "connectionPath": {"mode": "DIRECT"}},
            {"id": "all-magic", "connectionPath": {"mode": "MAGIC"}},
            {"id": "all-holepunch", "connectionPath": {"mode": "HOLEPUNCH"}},
        ],
        out_dir=tmp_path,
        artifact_dir=tmp_path,
        client_image="client",
        settings={},
        resource_scope="scope",
    )

    assert seen == ["DIRECT", "MAGIC", "HOLEPUNCH"]
    assert result["ok"] is True
    assert [path["connectionMode"] for path in result["connectionPaths"]] == seen


def test_interrupted_run_cleans_only_its_docker_resources(monkeypatch):
    class Resource:
        def __init__(self, name):
            self.name = name
            self.removed = False

        def remove(self, **_kwargs):
            self.removed = True

    owned_container = Resource("amp-owned123-c-abcd")
    other_container = Resource("amp-other456-c-abcd")
    owned_network = Resource("amp-owned123-n-abcd")
    other_network = Resource("amp-other456-n-abcd")
    docker = types.SimpleNamespace(
        containers=types.SimpleNamespace(list=lambda **_kwargs: [owned_container, other_container]),
        networks=types.SimpleNamespace(list=lambda **_kwargs: [owned_network, other_network]),
    )
    monkeypatch.setattr(cli.docker_py, "from_env", lambda: docker)

    cli._cleanup_run_resources("owned123")

    assert owned_container.removed
    assert owned_network.removed
    assert not other_container.removed
    assert not other_network.removed


# ── validation ─────────────────────────────────────────────────────────────


def test_server_history_compaction_uses_registered_boundary_command():
    source = (Path(__file__).parents[1] / "automodpack_autotester/server_steps.py").read_text(
        encoding="utf-8"
    )

    assert (
        '["rcon-cli", "automodpack", "generate", "storage", "compact", "before", str(boundary_seq), "confirm"]'
        in source
    )
    assert (
        '["rcon-cli", "automodpack", "generate", "history", "storage", "compact", "confirm"]'
        not in source
    )


class _ExecResult:
    def __init__(self, output=b"", exit_code=0):
        self.output = output
        self.exit_code = exit_code


def _journal_entry(seq, token, notes, *, restore_of=-1, snapshot=False):
    return {
        "seq": seq,
        "contentToken": token,
        "policySha1": "c" * 40,
        "createdAt": "2026-09-02T23:45:32Z",
        "notes": notes,
        "restoreOf": restore_of,
        "snapshot": snapshot,
        "changes": [{"path": "config/a.txt", "toSha1": "a" * 40, "toSize": 3}],
    }


def _write_server_state(server_root, journal, projection):
    (server_root / "journal.jsonl").write_text(
        "".join(json.dumps(entry) + "\n" for entry in journal), encoding="utf-8"
    )
    (server_root / "current-projection.json").write_text(
        json.dumps(projection), encoding="utf-8"
    )


def test_publish_server_generation_uses_durable_generation_receipt(make_ctx, monkeypatch):
    ctx = make_ctx()
    server_root = ctx.server_dir / "automodpack" / "server"
    server_root.mkdir(parents=True)
    notes = "Update without a loader-specific log receipt."
    token = "b" * 40
    head_entry = _journal_entry(1, token, notes)

    class Container:
        def exec_run(self, command):
            assert command == ["rcon-cli", "automodpack", "generate", "notes", notes]
            _write_server_state(
                server_root,
                [head_entry],
                {"contentToken": token, "journalHead": 1, "journal": [head_entry]},
            )
            return _ExecResult()

    monkeypatch.setattr(server_steps, "_write_server_generation", lambda *_: None)
    monkeypatch.setattr(server_steps, "_server_generation", lambda *_: {"patchNotes": notes})
    monkeypatch.setattr(server_steps, "_container", lambda _name: Container())

    server_steps._v_publish_server_generation(ctx, {"generation": 1})

    assert ctx.vars["published_server_generation"] == 1
    assert ctx.vars["published_server_generation_id"] == token


def test_completed_compaction_rewrites_the_journal_under_a_head_snapshot():
    root_token, update_token, rollback_token = "a" * 40, "b" * 40, "c" * 40
    entries_before = [
        _journal_entry(1, root_token, "root", snapshot=True),
        _journal_entry(2, update_token, "update"),
        _journal_entry(3, rollback_token, "rollback", restore_of=1),
    ]
    boundary_snapshot = _journal_entry(1, update_token, "update", snapshot=True)
    survivor = _journal_entry(2, rollback_token, "rollback", restore_of=1)
    entries_after = [boundary_snapshot, survivor]
    projection = {"contentToken": rollback_token, "journalHead": 2, "journal": entries_after}

    assert server_steps._completed_compaction(entries_before, entries_after, projection)

    assert not server_steps._completed_compaction(entries_before, entries_before, projection)
    unrenumbered = [entries_after[0], _journal_entry(3, rollback_token, "rollback", restore_of=1)]
    assert not server_steps._completed_compaction(entries_before, unrenumbered, projection)
    drift = dict(projection, contentToken="d" * 40)
    assert not server_steps._completed_compaction(entries_before, entries_after, drift)


def test_rollback_server_generation_appends_a_durable_restore_entry(make_ctx, monkeypatch):
    ctx = make_ctx()
    server_root = ctx.server_dir / "automodpack" / "server"
    server_root.mkdir(parents=True)
    target_token = "a" * 40
    current_token = "b" * 40
    target_seq, current_seq = 1, 2
    notes = "Release gate rollback verification."
    journal = [
        _journal_entry(target_seq, target_token, "root", snapshot=True),
        _journal_entry(current_seq, current_token, "update"),
    ]
    journal[-1]["policySha1"] = "d" * 40
    _write_server_state(
        server_root,
        journal,
        {"contentToken": current_token, "journalHead": current_seq, "journal": journal},
    )

    class Container:
        def __init__(self):
            self.commands = []

        def exec_run(self, command):
            self.commands.append(command)
            if command[0] == "rcon-cli":
                restore_seq = current_seq + 1
                restore = _journal_entry(restore_seq, target_token, notes, restore_of=target_seq)
                _write_server_state(
                    server_root,
                    [*journal, restore],
                    {"contentToken": target_token, "journalHead": restore_seq, "journal": [*journal, restore]},
                )
            return _ExecResult()

    container = Container()
    monkeypatch.setattr(server_steps, "_container", lambda _name: container)

    server_steps._v_rollback_server_generation(ctx, {})

    assert container.commands[-1] == [
        "rcon-cli", "automodpack", "generate", "revert", str(target_seq), "confirm", "notes", notes
    ]
    assert ctx.vars["server_rollback"]["targetSeq"] == target_seq
    assert ctx.vars["server_rollback"]["restoreSeq"] == current_seq + 1
    assert ctx.vars["server_rollback"]["contentToken"] == target_token
    assert ctx.vars["server_rollback"]["journalHead"] == current_seq + 1


def test_collect_server_objects_requires_real_transition_receipt(make_ctx, monkeypatch):
    ctx = make_ctx()

    class Container:
        def __init__(self):
            self.commands = []
            self.orphan_exists = True

        def exec_run(self, command):
            self.commands.append(command)
            if command[0] == "sh" and "count=0" in command[2]:
                return _ExecResult(b"2 47\n" if self.orphan_exists else b"1 23\n")
            if command[0] == "sh" and "sha1sum" in command[2]:
                return _ExecResult()
            if command[0] == "rcon-cli":
                self.orphan_exists = False
                return _ExecResult(b"Generation objects collected\nObjects - 2 -> 1\nBytes - 47 -> 23\nDeleted - 1 objects, 24 bytes\n")
            if command[0] == "test":
                return _ExecResult(exit_code=0 if self.orphan_exists else 1)
            raise AssertionError(command)

    container = Container()
    monkeypatch.setattr(server_steps, "_container", lambda _name: container)

    server_steps._v_collect_server_objects(ctx, {})

    assert container.commands[-1] == ["test", "-e", "/data/automodpack/data/objects/d8/e1759b948add3eb7d6cc6e6532a31f71292ecc"]
    assert ctx.vars["server_object_collection"]["deletedCount"] == 1


def test_seed_client_options_preserves_existing_settings(tmp_path):
    options_path = tmp_path / "options.txt"
    options_path.write_text(
        "narrator:0\nfoo:bar\nskipMultiplayerWarning:false\n", encoding="utf-8"
    )

    client_steps._seed_client_options(tmp_path)

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


def test_filesystem_waits_fail_when_client_exits(make_ctx):
    ctx = make_ctx()
    ctx.running_provider = lambda: (_ for _ in ()).throw(ClientExited("client exited with code 124"))

    with pytest.raises(ClientExited, match="code 124"):
        wait_file(ctx, {"path": "missing", "timeout": "1h", "poll": "1ms"})

    with pytest.raises(ClientExited, match="code 124"):
        wait_generation(ctx, {"timeout": "1h", "poll": "1ms"})


# ── artifact and staged manifest handling ────────────────────────────────────


def test_artifact_resolution_uses_target_id(tmp_path):
    target = _target(
        id="26.1-fabric",
        minecraft="26.1.2",
        loader="fabric",
        artifact_pattern="automodpack-mc{id}-*.jar",
    )
    artifact = tmp_path / "automodpack-mc26.1-fabric-test.jar"
    with zipfile.ZipFile(artifact, "w") as jar:
        jar.writestr("pl/skidam/automodpack/client/autotest/AutoTestBridge.class", b"")

    assert runner._resolve_artifact(target, tmp_path) == artifact.resolve()


def test_artifact_resolution_rejects_release_mode_artifact(tmp_path):
    target = _target(artifact_pattern="automodpack-mc{id}-*.jar")
    artifact = tmp_path / "automodpack-mc1.21.1-neoforge-test.jar"
    with zipfile.ZipFile(artifact, "w") as jar:
        jar.writestr("fabric.mod.json", "{}")

    with pytest.raises(RuntimeError, match="rebuild with -Pautomodpack.autotest"):
        runner._resolve_artifact(target, tmp_path)


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

    content_token = "a" * 40
    record = (
        ctx.game_dir / "automodpack/client/records" / content_token / "manifest.json"
    )
    record.parent.mkdir(parents=True, exist_ok=True)
    record.write_text(
        json.dumps({"contentToken": content_token, "policy": {"modpackId": "packaaa"}}),
        encoding="utf-8",
    )
    state = ctx.game_dir / "automodpack/client/active-state.json"
    state.write_text(
        json.dumps(
            {"schemaVersion": 1, "modpackId": "packaaa", "contentToken": content_token, "status": "ACTIVE"}
        ),
        encoding="utf-8",
    )

    wait_generation(ctx, {"timeout": "1s", "poll": "1ms"})


def test_wait_generation_requires_expected_patch_notes(make_ctx):
    ctx = make_ctx()

    def write_record(notes):
        content_token = "b" * 40
        record = (
            ctx.game_dir / "automodpack/client/records" / content_token / "manifest.json"
        )
        record.parent.mkdir(parents=True, exist_ok=True)
        head_entry = {
            "seq": 1,
            "contentToken": content_token,
            "policySha1": "c" * 40,
            "createdAt": "2026-09-02T23:45:32Z",
            "notes": notes,
            "restoreOf": -1,
            "snapshot": True,
            "changes": [],
        }
        record.write_text(
            json.dumps(
                {
                    "schemaVersion": 1,
                    "contentToken": content_token,
                    "journalHead": 1,
                    "journal": [head_entry],
                    "policy": {"modpackId": "packaaa"},
                }
            ),
            encoding="utf-8",
        )
        state = ctx.game_dir / "automodpack/client/active-state.json"
        state.write_text(
            json.dumps(
                {"schemaVersion": 1, "modpackId": "packaaa", "contentToken": content_token, "status": "ACTIVE"}
            ),
            encoding="utf-8",
        )

    write_record("stale")

    with pytest.raises(TimeoutError, match="active generation state was not committed"):
        wait_generation(
            ctx, {"patchNotes": "expected", "timeout": "1ms", "poll": "1ms"}
        )

    write_record("expected")
    wait_generation(ctx, {"patchNotes": "expected", "timeout": "1s", "poll": "1ms"})


def test_client_data_root_stays_pinned_across_relaunch_staging(make_ctx, monkeypatch):
    ctx = make_ctx()
    ctx.artifact.write_bytes(b"autotest-artifact")
    monkeypatch.setattr(client_steps, "_run_container", lambda **_kwargs: None)
    monkeypatch.setattr(client_steps, "_assert_running", lambda _name: None)
    monkeypatch.setattr(client_steps, "_jitter_sleep", lambda *_args, **_kwargs: None)

    client_steps._launch_client(ctx)
    data_root = ctx.game_dir / "automodpack/client/data"
    assert data_root.is_dir()

    staging_steps._v_stage_modpack(
        ctx,
        {
            "recordOnly": True,
            "packId": "packbbb",
            "packName": "Pack B",
            "files": [{"path": "config/b.txt", "content": "b"}],
        },
    )

    assert data_root.is_dir()


def test_client_start_timeout_is_not_a_process_lifetime(make_ctx):
    ctx = make_ctx(settings={"timeouts": {"clientStartSeconds": 600, "clientRunSeconds": 300}})

    assert client_steps._client_start_timeout(ctx, {}) == 600


def test_client_launcher_does_not_kill_a_healthy_scenario_at_the_startup_deadline():
    source = (Path(__file__).parents[1] / "docker/client/run-headlessmc-client").read_text(encoding="utf-8")

    assert "client_timeout" not in source
    assert 'hmc --command "download ${minecraft}"' not in source
    assert "xvfb-run" not in source
    assert "Xvfb -displayfd 3" in source
    assert 'prepared_profile="${AM_AUTOTEST_HMC_PREPARED:-false}"' in source
    assert 'prepare_only="${AM_AUTOTEST_PREPARE_ONLY:-false}"' in source
    assert 'jvmargs="-Xmx2G ' in source


def test_client_container_caps_java_heap(make_ctx, monkeypatch):
    seen = {}
    monkeypatch.setattr(client_steps, "_client_profile_is_prepared", lambda *_args: True)
    monkeypatch.setattr(client_steps, "_run_container", lambda **kwargs: seen.update(kwargs))
    ctx = make_ctx()
    ctx.out_dir.mkdir(parents=True, exist_ok=True)

    client_steps._start_client_container(ctx, "cli")

    assert seen["env"]["JAVA_TOOL_OPTIONS"] == "-Xmx2G"


def test_run_stops_gradle_daemons(monkeypatch, tmp_path):
    wrapper = tmp_path / "gradlew"
    wrapper.write_text("#!/bin/sh\n", encoding="utf-8")
    wrapper.chmod(0o755)
    seen = []
    monkeypatch.setattr(cli, "REPO_ROOT", tmp_path)
    monkeypatch.setattr(cli.subprocess, "run", lambda cmd, **_kwargs: seen.append(cmd) or types.SimpleNamespace(returncode=0))

    cli._stop_gradle_daemons()

    assert seen == [[str(wrapper), "--stop"]]


def test_client_launcher_uses_canonical_targets_when_the_profile_is_prepared():
    source = (Path(__file__).parents[1] / "docker/client/run-headlessmc-client").read_text(encoding="utf-8")
    prepare_guard = 'if [ "$prepared_profile" != "true" ] && [ -n "$loader_version" ]; then'

    for target in (
        'launch_target="fabric-loader-${loader_version}-${minecraft}"',
        'launch_target="${minecraft}-forge-${loader_version}"',
        'launch_target="neoforge-${loader_version}"',
    ):
        assert target in source
        assert source.index(target) < source.index(prepare_guard)

    # Without a configured loader version there is no exact profile to pin, so
    # retain the generic target as the deliberate fallback.
    generic_target = 'launch_target="${loader}:${minecraft}"'
    assert generic_target in source
    assert source.index(generic_target) < source.index(prepare_guard)
    installation = source[source.index(prepare_guard) : source.index("\nfi", source.index(prepare_guard))]
    assert "launch_target=" not in installation


def test_bootstrap_overlaps_profile_preparation_with_server_startup():
    steps = load_macros()["boot_with_bootstrap"]
    actions = [step.get("do") or step.get("use") for step in steps]

    assert actions.index("launch_server") < actions.index("prepare_client") < actions.index("wait_server")
    assert actions.index("seed_bootstrap") < actions.index("launch_client")


def test_client_launch_waits_for_successful_profile_preparation(make_ctx, monkeypatch):
    ctx = make_ctx(settings={"timeouts": {"clientStartSeconds": 600}})
    ctx.vars["client_preparation"] = "client-prepare"
    calls = []
    monkeypatch.setattr(client_steps, "_wait_exited", lambda name, timeout: calls.append(("wait", name, timeout)))
    monkeypatch.setattr(client_steps, "_inspect_container", lambda _name: {"State": {"ExitCode": 0}})
    monkeypatch.setattr(client_steps, "_record_prepared_client_profile", lambda _ctx: calls.append(("receipt",)))
    monkeypatch.setattr(client_steps, "_client_profile_is_prepared", lambda *_args: True)
    monkeypatch.setattr(client_steps, "_remove_container", lambda name: calls.append(("remove", name)))

    client_steps._await_client_preparation(ctx)

    assert calls[0][0] == "wait" and calls[0][1] == "client-prepare"
    assert calls[0][2] == pytest.approx(600.0, abs=5.0)
    assert calls[1:] == [("receipt",), ("remove", "client-prepare")]


def test_wait_bridge_retries_only_transient_dependency_download(make_ctx, monkeypatch):
    ctx = make_ctx()
    bridge_state = client_steps._bridge_state(ctx)
    bridge_state.parent.mkdir(parents=True, exist_ok=True)
    bridge_state.write_text(json.dumps({"status": "ready"}), encoding="utf-8")
    ctx.bridge = types.SimpleNamespace(request=lambda *_args, **_kwargs: None)
    running_checks = iter((RuntimeError("first download failed"), RuntimeError("second download failed"), None))
    launches = []

    def assert_running(_name):
        failure = next(running_checks)
        if failure:
            raise failure

    monkeypatch.setattr(client_steps, "_assert_running", assert_running)
    monkeypatch.setattr(client_steps, "_container_logs", lambda _name: "[LibraryDownloader]: missing dependency\nHTTP connect timed out")
    monkeypatch.setattr(client_steps, "_remove_container", lambda name: launches.append(("remove", name)))
    monkeypatch.setattr(client_steps, "_launch_client", lambda launched_ctx: launches.append(("launch", launched_ctx.target.id)))
    monkeypatch.setattr(client_steps, "_record_prepared_client_profile", lambda _ctx: None)
    client_steps._v_wait_bridge(ctx, {"timeout": "1s"})

    assert launches == [("remove", ctx.cli_name), ("launch", ctx.target.id), ("remove", ctx.cli_name), ("launch", ctx.target.id)]
    assert not client_steps._transient_dependency_download_failure("MixinApplyError\nHTTP connect timed out")


def test_connect_screen_classifier_does_not_loop_on_first_connection():
    assert client_steps._is_connecting_screen(
        "net.minecraft.client.gui.screens.ConnectScreen"
    )
    assert client_steps._is_connecting_screen("net.minecraft.client.gui.screens.class_412")
    assert not client_steps._is_connecting_screen(
        "pl.skidam.automodpack.client.ui.screen.UnverifiedPackConfirmScreen"
    )


def test_connection_failure_screen_is_retried_instead_of_reported_as_connected():
    assert client_steps._is_connection_failure_screen("net.minecraft.class_419")
    assert client_steps._is_connection_failure_screen(
        "net.minecraft.client.gui.screens.DisconnectedScreen"
    )
    assert not client_steps._is_connection_failure_screen(
        "pl.skidam.automodpack.client.ui.screen.UnverifiedPackConfirmScreen"
    )


def test_assert_preload_acquired_checks_complete_projection(make_ctx):
    ctx = make_ctx()
    payloads = {"a" * 40: b"first", "b" * 40: b"second"}
    projection = {
        "policy": {
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
    }
    projection_path = (
        ctx.server_dir / "automodpack" / "server" / "current-projection.json"
    )
    projection_path.parent.mkdir(parents=True, exist_ok=True)
    projection_path.write_text(json.dumps(projection), encoding="utf-8")
    objects = client_steps._ensure_client_data_root(ctx.game_dir) / "objects"
    objects.mkdir(parents=True, exist_ok=True)
    for payload in payloads.values():
        object_path = client_steps.cas_object(objects, hashlib.sha1(payload).hexdigest())
        object_path.parent.mkdir(parents=True, exist_ok=True)
        object_path.write_bytes(payload)
    ctx.logs_provider = lambda _which, _tail=None: ""
    client_log = ctx.game_dir / "logs" / "latest.log"
    client_log.parent.mkdir(parents=True, exist_ok=True)
    client_log.write_text("Launch apply acquired 2 complete modpack objects in 1ms", encoding="utf-8")

    client_steps._v_assert_preload_acquired(ctx, {})

    assert ctx.vars["preloaded_object_count"] == 2


def test_wait_exit_or_alive_tolerates_still_running(monkeypatch):
    def never_exits(name, timeout):
        raise TimeoutError("still running")

    monkeypatch.setattr(client_steps, "_wait_exited", never_exits)
    ctx = types.SimpleNamespace(cli_name="c")
    with pytest.raises(TimeoutError):
        client_steps._v_wait_client_exit(ctx, {})  # default: must exit
    client_steps._v_wait_client_exit(ctx, {"or_alive": True})  # tolerated


def test_wait_exit_expect_clean_and_crash(monkeypatch):
    monkeypatch.setattr(client_steps, "_wait_exited", lambda name, timeout: None)
    ctx = types.SimpleNamespace(cli_name="c")

    monkeypatch.setattr(client_steps, "_exit_code", lambda name: 0)
    client_steps._v_wait_client_exit(ctx, {"expect": "clean"})
    with pytest.raises(AssertionError):
        client_steps._v_wait_client_exit(ctx, {"expect": "crash"})

    monkeypatch.setattr(client_steps, "_exit_code", lambda name: 1)
    client_steps._v_wait_client_exit(ctx, {"expect": "crash"})
    with pytest.raises(AssertionError):
        client_steps._v_wait_client_exit(ctx, {"expect": "clean"})


# ── verb discovery ──────────────────────────────────────────────────────────


# ── staged generation timestamps are Java-canonical ─────────────────────────
# A fixed-width microsecond fraction (e.g. ".800000Z") fails the client's strict
# Instant round-trip check and crashes the first storage validation that reads the
# record — a ~10% flake per staged record (1.21.10-neoforge DIRECT, run 33695849021;
# 26.2-fabric MAGIC/HOLEPUNCH, run 33744193609). Canonical means no fraction or
# exactly 3/6/9 digits, verified against jshell Instant.toString behavior.


def test_canonical_timestamp_uses_java_fraction_groups():
    from datetime import datetime, timezone

    noon = datetime(2026, 9, 2, 23, 45, 32, tzinfo=timezone.utc)
    assert staging_steps._canonical_timestamp(noon) == "2026-09-02T23:45:32Z"
    assert staging_steps._canonical_timestamp(noon.replace(microsecond=800000)) == "2026-09-02T23:45:32.800Z"
    assert staging_steps._canonical_timestamp(noon.replace(microsecond=123000)) == "2026-09-02T23:45:32.123Z"
    assert staging_steps._canonical_timestamp(noon.replace(microsecond=123456)) == "2026-09-02T23:45:32.123456Z"
    assert staging_steps._canonical_timestamp(noon.replace(microsecond=477370)) == "2026-09-02T23:45:32.477370Z"


def test_non_canonical_staged_timestamp_is_rejected():
    staging_steps._check_canonical_timestamp("2026-09-02T23:45:32Z")
    staging_steps._check_canonical_timestamp("2026-09-02T23:45:32.800Z")
    staging_steps._check_canonical_timestamp("2026-09-02T23:45:32.123Z")
    staging_steps._check_canonical_timestamp("2026-09-02T23:45:32.123456Z")
    for bad in ("2026-09-02T23:45:32.800000Z", "2026-09-02T23:45:32.000Z", "2026-09-02T23:45:32.8Z",
                "2026-09-02T23:45:32.12Z", "2026-09-02T23:45:32.12345Z", "2026-09-02T23:45:32.47737Z"):
        with pytest.raises(ValueError, match="not canonical"):
            staging_steps._check_canonical_timestamp(bad)


def test_staged_manifest_receipt_rejects_a_non_canonical_timestamp(tmp_path):
    manifest = {
        "schemaVersion": 1,
        "contentToken": "0" * 40,
        "policySha1": "1" * 40,
        "createdAt": "2026-09-02T23:45:32.800000Z",
        "journalHead": 1,
        "journalTruncated": False,
        "journal": [],
        "ownershipLedger": {"modpackId": "packbbb", "entries": [], "digest": "2" * 40},
        "policy": {"modpackId": "packbbb"},
    }
    path = tmp_path / "manifest.json"
    path.write_text(json.dumps(manifest), encoding="utf-8")
    with pytest.raises(ValueError, match="not canonical"):
        staging_steps._verify_staged_manifest(path, "")


# ── poisoned HMC cache recovery ─────────────────────────────────────────────
# The installer can exit 0 while leaving installer-produced jars (old Forge
# *-srg/*-extra outputs) unwritten; every launch from that per-target cache then
# crashes before the bridge comes up. The crash names the exact jars, so recovery
# reinstalls once and proves those jars exist instead of trusting exit codes.
# (An upfront jar-list gate was tried first and reverted: Forge/NeoForge profile
# jsons do not expose a complete checkable library list, so it false-failed every
# non-Fabric target in run 33744193609.)


def test_missing_cache_jars_parsed_from_the_crash_message():
    logs = ("Exception in thread main java.io.UncheckedIOException: java.io.IOException: "
            "Invalid paths argument, contained no existing paths: "
            "[/work/hmc-cache/libraries/a/b.jar, /work/hmc-cache/libraries/c/d.jar]")
    assert client_steps._missing_cache_jar_paths(logs) == ["/work/hmc-cache/libraries/a/b.jar", "/work/hmc-cache/libraries/c/d.jar"]
    assert client_steps._missing_cache_jar_paths("unrelated crash") is None
    assert client_steps._missing_cache_jar_paths("contained no existing paths: oops") == []


def test_preparation_retries_a_failed_install_inside_the_budget(make_ctx, monkeypatch):
    ctx = make_ctx(settings={"timeouts": {"clientStartSeconds": 600}})
    ctx.vars["client_preparation"] = "client-prepare"
    monkeypatch.setattr(client_steps, "_wait_exited", lambda name, timeout: None)
    exits = iter([1, 0])
    monkeypatch.setattr(client_steps, "_inspect_container", lambda _name: {"State": {"ExitCode": next(exits)}})
    monkeypatch.setattr(client_steps, "_container_logs", lambda _name: "boom")
    monkeypatch.setattr(client_steps, "_record_prepared_client_profile", lambda _ctx: None)
    monkeypatch.setattr(client_steps, "_client_profile_is_prepared", lambda *_args: True)
    monkeypatch.setattr(client_steps, "_remove_container", lambda _name: None)
    relaunches = []
    monkeypatch.setattr(client_steps, "_launch_preparation_container", lambda _ctx: relaunches.append(1) or "client-prepare")

    client_steps._await_client_preparation(ctx)

    assert relaunches == [1]


def test_cache_recovery_reinstalls_once_and_proves_the_named_jars(make_ctx, monkeypatch, tmp_path):
    ctx = make_ctx()
    hmc_cache = tmp_path / "hmc"
    (hmc_cache / "libraries/a").mkdir(parents=True)
    monkeypatch.setattr(client_steps, "_client_cache_paths", lambda _ctx: (hmc_cache, tmp_path / "versions"))
    monkeypatch.setattr(client_steps, "_client_profile_receipt", lambda _root: hmc_cache / "prepared-profile.json")
    monkeypatch.setattr(client_steps, "_launch_preparation_container", lambda _ctx: "prep")

    def fake_await(_ctx):
        (hmc_cache / "libraries/a/b.jar").write_bytes(b"")
        (hmc_cache / "prepared-profile.json").write_text("{}")

    monkeypatch.setattr(client_steps, "_await_client_preparation", fake_await)
    client_steps._recover_client_cache(ctx, ["/work/hmc-cache/libraries/a/b.jar"])

    assert ctx.vars["client_preparation"] == "prep"
    assert (hmc_cache / "prepared-profile.json").is_file()


def test_cache_recovery_fails_fast_when_jars_stay_missing(make_ctx, monkeypatch, tmp_path):
    ctx = make_ctx()
    hmc_cache = tmp_path / "hmc"
    hmc_cache.mkdir()
    monkeypatch.setattr(client_steps, "_client_cache_paths", lambda _ctx: (hmc_cache, tmp_path / "versions"))
    monkeypatch.setattr(client_steps, "_client_profile_receipt", lambda _root: hmc_cache / "prepared-profile.json")
    monkeypatch.setattr(client_steps, "_launch_preparation_container", lambda _ctx: "prep")
    monkeypatch.setattr(client_steps, "_await_client_preparation", lambda _ctx: None)

    with pytest.raises(RuntimeError, match="did not produce required jars"):
        client_steps._recover_client_cache(ctx, ["/work/hmc-cache/libraries/a/b.jar"])


def test_preparation_gives_up_after_repeated_failures(make_ctx, monkeypatch):
    ctx = make_ctx(settings={"timeouts": {"clientStartSeconds": 600}})
    ctx.vars["client_preparation"] = "client-prepare"
    monkeypatch.setattr(client_steps, "_wait_exited", lambda name, timeout: None)
    monkeypatch.setattr(client_steps, "_inspect_container", lambda _name: {"State": {"ExitCode": 1}})
    monkeypatch.setattr(client_steps, "_container_logs", lambda _name: "boom")
    monkeypatch.setattr(client_steps, "_record_prepared_client_profile", lambda _ctx: None)
    monkeypatch.setattr(client_steps, "_remove_container", lambda _name: None)
    launches = []
    monkeypatch.setattr(client_steps, "_launch_preparation_container", lambda _ctx: launches.append(1) or "client-prepare")

    with pytest.raises(RuntimeError, match="after 3 attempts"):
        client_steps._await_client_preparation(ctx)

    assert launches == [1, 1]


def test_wait_bridge_recovers_a_poisoned_cache_once(make_ctx, monkeypatch):
    ctx = make_ctx()
    bridge_state = client_steps._bridge_state(ctx)
    bridge_state.parent.mkdir(parents=True, exist_ok=True)
    bridge_state.write_text(json.dumps({"status": "ready"}), encoding="utf-8")
    ctx.bridge = types.SimpleNamespace(request=lambda *_args, **_kwargs: None)
    crash = ("Exception in thread main java.io.IOException: Invalid paths argument, "
             "contained no existing paths: [/work/hmc-cache/libraries/a/b.jar]")
    running_calls = []

    def assert_running(_name):
        running_calls.append(1)
        if len(running_calls) == 1:
            raise RuntimeError("exited")

    recovered = []
    monkeypatch.setattr(client_steps, "_assert_running", assert_running)
    monkeypatch.setattr(client_steps, "_container_logs", lambda _name: crash)
    monkeypatch.setattr(client_steps, "_recover_client_cache", lambda _ctx, missing: recovered.append(missing))
    monkeypatch.setattr(client_steps, "_remove_container", lambda _name: None)
    monkeypatch.setattr(client_steps, "_launch_client", lambda _ctx: None)
    monkeypatch.setattr(client_steps, "_record_prepared_client_profile", lambda _ctx: None)

    client_steps._v_wait_bridge(ctx, {"timeout": "30s"})

    assert recovered == [["/work/hmc-cache/libraries/a/b.jar"]]
    assert ctx.vars["client_cache_recovered"] is True


def test_wait_bridge_does_not_recover_the_same_crash_twice(make_ctx, monkeypatch):
    ctx = make_ctx()
    ctx.vars["client_cache_recovered"] = True
    ctx.bridge = types.SimpleNamespace(request=lambda *_args, **_kwargs: (_ for _ in ()).throw(AssertionError("no bridge")))
    crash = "Invalid paths argument, contained no existing paths: [/work/hmc-cache/libraries/a/b.jar]"
    monkeypatch.setattr(client_steps, "_assert_running", lambda _name: (_ for _ in ()).throw(RuntimeError("exited")))
    monkeypatch.setattr(client_steps, "_container_logs", lambda _name: crash)

    with pytest.raises(TimeoutError, match="Client exited before bridge"):
        client_steps._v_wait_bridge(ctx, {"timeout": "1s"})
