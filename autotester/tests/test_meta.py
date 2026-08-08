"""Docker-free tests for the meta layer: scenario validation, target scoping,
transport/mode selection, and verb discovery."""
from __future__ import annotations

import hashlib
import json
import threading
import time
import types
from pathlib import Path

import pytest

from automodpack_autotester import cli, runner
from automodpack_autotester.config import (
    load_macros,
    load_scenarios,
    load_targets,
    parse_server_files,
    scenario_matches_target,
)
from automodpack_autotester.generation_identity import CanonicalEncoder
from automodpack_autotester.engine.registry import describe, names
from automodpack_autotester.engine.steps_io import seed_unowned_local_file, wait_file_content
from automodpack_autotester.mod_fixtures import assert_valid_mod_fixture, valid_mod_jar_bytes
from automodpack_autotester.validate import validate_scenario


def _target(**kw):
    base = dict(id="1.21.1-neoforge", minecraft="1.21.1", loader="neoforge", java=21)
    base.update(kw)
    return types.SimpleNamespace(**base)


def test_targets_command_uses_configured_defaults(monkeypatch, capsys):
    target = _target(id="selected")
    monkeypatch.setattr(cli, "load_settings", lambda: {"run": {"scenario": "custom", "target": "selected"}})
    monkeypatch.setattr(cli, "load_scenarios", lambda: {"custom": {}})
    monkeypatch.setattr(cli, "load_targets", lambda: {"selected": target})
    monkeypatch.setattr(cli, "load_macros", lambda: {})
    monkeypatch.setattr(cli, "validate_scenario", lambda *_: [])
    monkeypatch.setattr(cli, "scenario_matches_target", lambda *_: True)

    assert cli._cmd_targets(None, None) == 0
    assert json.loads(capsys.readouterr().out) == ["selected"]


# ── validation ─────────────────────────────────────────────────────────────


def test_shipped_scenarios_validate():
    macros = load_macros()
    targets = load_targets()
    for name, scenario in load_scenarios().items():
        assert validate_scenario(scenario, macros, targets) == [], name


def test_release_fixture_uses_server_config_and_declared_group_directories(make_ctx):
    scenario = load_scenarios()["all"]
    server_files = parse_server_files(scenario)
    ctx = make_ctx(scenario=scenario, modpack_name=server_files.modpack_name, marker_rel=server_files.marker,
                   scenario_files=server_files.files)
    ctx.artifact.write_bytes(b"autotest-artifact")
    runner._prepare_server(ctx)

    config_path = ctx.server_dir / "automodpack" / "server-config.json"
    assert config_path.is_file()
    assert not (ctx.server_dir / "automodpack" / "automodpack-server.json").exists()
    assert set(json.loads(config_path.read_text())["groups"]) == {"main", "visual", "addon", "alternative", "windows"}

    host_root = ctx.server_dir / "automodpack" / "host-modpack"
    assert (host_root / "main" / "config/amp-autotest-alpha.txt").is_file()
    assert (host_root / "visual" / "config/amp-autotest-visual.txt").is_file()
    assert (host_root / "addon" / "config/amp-autotest-addon.txt").is_file()
    assert (host_root / "alternative" / "config/amp-autotest-alternative.txt").is_file()
    assert (host_root / "windows" / "config/amp-autotest-windows.txt").is_file()
    assert_valid_mod_fixture(
        (host_root / "main" / "mods/amp-autotest-removed.jar").read_bytes(),
        {"modId": "amp_autotest_removed", "version": "1.0.0-published", "marker": "published"},
    )
    assert not (host_root / "main" / "config/amp-autotest-visual.txt").exists()


def test_reset_client_generation_preserves_ordinary_mods(make_ctx):
    ctx = make_ctx()
    client = ctx.game_dir / "automodpack/client"
    (client / "records/old").mkdir(parents=True)
    (client / "records/old/manifest.json").write_text("{}")
    (client / "active/config").mkdir(parents=True)
    (client / "active/config/old.txt").write_text("old")
    (client / "data/objects").mkdir(parents=True)
    (client / "data/objects" / ("a" * 40)).write_bytes(b"cached")
    (client / "data/known-hosts.json").write_text('{"hosts": {}}')
    (client / "data/packs/packaaa").mkdir(parents=True)
    (client / "data/packs/packaaa/connection.json").write_text('{"connection": {}}')
    (client / "active-state.json").write_text("{}")
    (ctx.game_dir / "automodpack/client-config.json").write_text('{"selectedModpackId": "packaaa"}')
    fixture = {"modId": "amp_autotest_removed", "version": "1.0.0-published", "marker": "published"}
    (ctx.game_dir / "mods/old.jar").write_bytes(valid_mod_jar_bytes(fixture))
    runner._v_reset_client_generation(ctx, {})

    assert not (client / "records").exists()
    assert not (client / "active").exists()
    assert not (client / "data/objects").exists()
    assert not (client / "active-state.json").exists()
    assert_valid_mod_fixture((ctx.game_dir / "mods/old.jar").read_bytes(), fixture)
    assert (client / "data/known-hosts.json").read_text() == '{"hosts": {}}'
    assert (client / "data/packs/packaaa/connection.json").read_text() == '{"connection": {}}'
    assert (ctx.game_dir / "automodpack/client-config.json").read_text() == '{"selectedModpackId": "packaaa"}'


def test_validation_rejects_generation_fixture_on_non_jar_path():
    scenario = {
        "id": "fixture-validation",
        "serverFiles": {"generations": [{"files": [{"path": "config/old.txt", "fixture": {"modId": "old", "version": "1", "marker": "old"}}]}]},
        "flow": [{"do": "quit"}],
    }
    problems = validate_scenario(scenario, load_macros(), load_targets())
    assert any("valid mod fixtures must use a .jar path" in problem for problem in problems)


def test_validation_requires_seed_mod_fixture_payload():
    scenario = {"id": "missing-fixture", "flow": [{"do": "seed_mod_fixture", "path": "mods/old.jar"}]}
    problems = validate_scenario(scenario, load_macros(), load_targets())
    assert any("seed_mod_fixture.fixture" in problem for problem in problems)


def test_unowned_local_fixture_writes_a_valid_cross_loader_archive(make_ctx):
    fixture = {"modId": "amp_autotest_unowned", "version": "1.0.0-local-unowned", "marker": "unowned-local"}
    ctx = make_ctx()

    seed_unowned_local_file(ctx, {"path": "mods/local-unowned.jar", "fixture": fixture})

    assert_valid_mod_fixture((ctx.game_dir / "mods/local-unowned.jar").read_bytes(), fixture)


def test_validation_rejects_plain_text_unowned_jar_seed():
    scenario = load_scenarios()["all"]
    seed = next(step for step in scenario["flow"] if isinstance(step, dict) and step.get("do") == "seed_unowned_local_file")
    seed.pop("fixture")

    problems = validate_scenario(scenario, load_macros(), load_targets())

    assert any(".jar paths require a valid mod fixture mapping" in problem for problem in problems)


def test_release_gate_cannot_drop_a_required_capability():
    scenario = load_scenarios()["all"]
    scenario["releaseGate"]["covers"] = list(scenario["releaseGate"]["covers"][:-1])
    problems = validate_scenario(scenario, load_macros(), load_targets())
    assert any("release-gate scenario must declare exactly" in problem for problem in problems)


def test_canonical_encoder_has_java_parity_vector():
    assert CanonicalEncoder().string("parity").integer(7).long(11).boolean(True).digest() == "74298b52636c03aab0beb88c118b33b03343fd30"


def test_validate_flags_unknown_verb_and_macro():
    scenario = {"flow": [{"do": "nope"}, {"use": "no_such_macro"}]}
    problems = validate_scenario(scenario, {})
    assert any("unknown verb" in p for p in problems)
    assert any("unknown macro" in p for p in problems)


def test_validate_flags_bad_condition_and_log():
    scenario = {
        "flow": [
            {"do": "assert", "that": {"bogus_key": 1}},
            {"do": "wait_for", "until": {"log": {"container": "client"}}},
        ]
    }
    problems = validate_scenario(scenario, {})
    assert any("unknown condition key" in p for p in problems)
    assert any("needs at least one matcher" in p for p in problems)


def test_validate_flags_bad_mode_and_network():
    problems = validate_scenario({"mode": "weird", "network": "carrier-pigeon", "flow": ["quit"]}, {})
    assert any("unknown mode" in p for p in problems)
    assert any("unknown network" in p for p in problems)


def test_validate_rejects_recursive_macros():
    macros = {"a": [{"use": "b"}], "b": [{"use": "a"}]}
    problems = validate_scenario({"id": "cycle", "flow": [{"use": "a"}]}, macros)
    assert any("macro cycle: a -> b -> a" in p for p in problems)


def test_validate_rejects_bad_duration_regex_and_repeat():
    scenario = {
        "id": "bad-fields",
        "flow": [{
            "do": "wait_for",
            "timeout": "soon",
            "repeat": 0,
            "until": {"log": {"matches": "["}},
        }],
    }
    problems = validate_scenario(scenario, {})
    assert any("invalid positive duration" in p for p in problems)
    assert any("invalid regex" in p for p in problems)
    assert any("positive integer" in p for p in problems)


def test_validate_requires_remote_mod_maps_for_every_scoped_target():
    targets = {
        "a": _target(id="a"),
        "b": _target(id="b"),
    }
    scenario = {
        "id": "remote-map",
        "targets": ["a", "b"],
        "flow": [{
            "do": "stage_modpack",
            "mods": [{
                "url": {"a": "https://example.invalid/a.jar"},
                "sha512": {"a": "a" * 128},
            }],
        }],
    }
    problems = validate_scenario(scenario, {}, targets)
    assert any("missing target entries ['b']" in p for p in problems)


# ── target scoping ──────────────────────────────────────────────────────────


def test_scope_no_keys_matches_everything():
    assert scenario_matches_target({}, _target()) is True


def test_scope_by_loader():
    sc = {"loaders": ["neoforge"]}
    assert scenario_matches_target(sc, _target(loader="neoforge")) is True
    assert scenario_matches_target(sc, _target(loader="fabric")) is False


def test_scope_by_target_glob_and_minecraft():
    assert scenario_matches_target({"targets": ["1.21.1-*"]}, _target(id="1.21.1-neoforge")) is True
    assert scenario_matches_target({"targets": ["1.20.*"]}, _target(id="1.21.1-neoforge")) is False
    assert scenario_matches_target({"minecraft": ["1.21.*"]}, _target(minecraft="1.21.1")) is True
    assert scenario_matches_target({"minecraft": "1.20.1"}, _target(minecraft="1.21.1")) is False


# ── transport / mode ────────────────────────────────────────────────────────


def test_transport_precedence():
    assert runner.transport({}, {}) == "bridge"
    assert runner.transport({}, {"network": "host"}) == "host"
    assert runner.transport({"network": "host"}, {"network": "bridge"}) == "host"


def test_scenario_mode():
    assert runner.scenario_mode({}) == "full"
    assert runner.scenario_mode({"mode": "client-only"}) == "client-only"


def test_seed_client_options_preserves_existing_settings(tmp_path):
    options_path = tmp_path / "options.txt"
    options_path.write_text("narrator:0\nfoo:bar\nskipMultiplayerWarning:false\n", encoding="utf-8")

    runner._seed_client_options(tmp_path)

    assert options_path.read_text(encoding="utf-8") == "narrator:0\nfoo:bar\nskipMultiplayerWarning:true\n"


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
        wait_file_content(ctx, {"path": "automodpack/client/active/config/update.txt", "content": "new\n", "timeout": "1s", "poll": "10ms"})
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


def test_staged_generation_uses_actual_file_metadata(make_ctx):
    ctx = make_ctx()
    root = ctx.game_dir / "staged"
    marker = root / ctx.marker_rel
    marker.parent.mkdir(parents=True)
    marker.write_text("marker\n")
    mod = root / "mods" / "fixture.jar"
    mod.parent.mkdir()
    mod.write_bytes(b"fixture")

    data_root = root.parent / "data"
    generation = runner._write_staged_generation(ctx, root, "fixture7", data_root)

    manifest = json.loads((root.parent / "records" / generation["generationId"] / "manifest.json").read_text())
    by_path = manifest["groups"]["main"]["files"]
    assert by_path["mods/fixture.jar"]["size"] == str(len(b"fixture"))
    assert by_path["mods/fixture.jar"]["sha1"] == hashlib.sha1(b"fixture").hexdigest()
    assert by_path["mods/fixture.jar"]["editable"] is False
    assert by_path["config/amp-autotest-marker.json"]["editable"] is True


def test_record_only_staging_does_not_replace_active_state(make_ctx):
    ctx = make_ctx(
        modpack_name="Pack A",
        marker_rel=Path("config/marker.json"),
        scenario_files=[(Path("config/a.txt"), "a")],
    )
    active_state = ctx.game_dir / "automodpack/client/active-state.json"
    active_state.parent.mkdir(parents=True, exist_ok=True)
    active_state.write_text('{"modpackId":"packaaa"}')

    runner._v_stage_modpack(ctx, {
        "recordOnly": True,
        "packId": "packbbb",
        "packName": "Pack B",
        "files": [{"path": "config/b.txt", "content": "b"}],
    })

    assert json.loads(active_state.read_text())["modpackId"] == "packaaa"
    records = list((ctx.game_dir / "automodpack/client/records").glob("*/manifest.json"))
    assert len(records) == 1
    assert json.loads(records[0].read_text())["modpackName"] == "Pack B"


def test_client_data_root_stays_pinned_across_relaunch_staging(make_ctx, monkeypatch):
    ctx = make_ctx()
    ctx.artifact.write_bytes(b"autotest-artifact")
    monkeypatch.setattr(runner, "_run_container", lambda **_kwargs: None)
    monkeypatch.setattr(runner, "_assert_running", lambda _name: None)
    monkeypatch.setattr(runner, "_jitter_sleep", lambda *_args, **_kwargs: None)

    runner._launch_client(ctx)
    marker = ctx.game_dir / "automodpack/data-root.json"
    before = json.loads(marker.read_text())

    runner._v_stage_modpack(ctx, {
        "recordOnly": True,
        "packId": "packbbb",
        "packName": "Pack B",
        "files": [{"path": "config/b.txt", "content": "b"}],
    })

    assert before == {"root": "/work/game/automodpack/client/data", "shared": False}
    assert json.loads(marker.read_text()) == before


def test_seed_bootstrap_writes_live_fields(make_ctx):
    ctx = make_ctx()
    server_state = ctx.server_dir / "automodpack" / "server"
    server_state.mkdir(parents=True, exist_ok=True)
    (server_state / "current-projection.json").write_text(json.dumps({"modpackId": "packaaa"}), encoding="utf-8")
    (ctx.server_dir / "automodpack" / "server-config.json").write_text(json.dumps({"connectionMode": "HOLEPUNCH"}), encoding="utf-8")
    ctx.server_host = "amp-server"
    ctx.vars["fingerprint"] = "01:23:45"

    runner._v_seed_bootstrap(ctx, {})

    assert json.loads((ctx.game_dir / "automodpack-bootstrap.json").read_text(encoding="utf-8")) == {
        "origin": "amp-server:25565",
        "fingerprint": "01:23:45",
        "modpackId": "packaaa",
        "endpoint": "amp-server:25565",
        "connectionMode": "HOLEPUNCH",
    }
    assert ctx.vars["bootstrap_modpack_id"] == "packaaa"


def test_connect_screen_classifier_does_not_loop_on_first_connection():
    assert runner._is_connecting_screen("net.minecraft.client.gui.screens.ConnectScreen")
    assert not runner._is_connecting_screen("pl.skidam.automodpack.client.ui.FirstConnectScreen")


def test_assert_preload_acquired_checks_complete_projection(make_ctx):
    ctx = make_ctx()
    payloads = {"a" * 40: b"first", "b" * 40: b"second"}
    projection = {"groups": {"main": {"files": {
        "config/a.txt": {"sha1": hashlib.sha1(payloads["a" * 40]).hexdigest(), "size": str(len(payloads["a" * 40]))},
        "config/b.txt": {"sha1": hashlib.sha1(payloads["b" * 40]).hexdigest(), "size": str(len(payloads["b" * 40]))},
        "config/a-copy.txt": {"sha1": hashlib.sha1(payloads["a" * 40]).hexdigest(), "size": str(len(payloads["a" * 40]))},
    }}}}
    projection_path = ctx.server_dir / "automodpack" / "server" / "current-projection.json"
    projection_path.parent.mkdir(parents=True, exist_ok=True)
    projection_path.write_text(json.dumps(projection), encoding="utf-8")
    objects = runner._ensure_client_data_root(ctx.game_dir) / "objects"
    objects.mkdir(parents=True, exist_ok=True)
    for payload in payloads.values():
        (objects / hashlib.sha1(payload).hexdigest()).write_bytes(payload)
    ctx.logs_provider = lambda _which, _tail=None: "Preloaded 2 complete modpack objects in 1ms"

    runner._v_assert_preload_acquired(ctx, {})

    assert ctx.vars["preloaded_object_count"] == 2


def test_record_only_stages_a_valid_cross_loader_mod_fixture(make_ctx):
    ctx = make_ctx()
    local = {"modId": "amp_autotest_conflict", "version": "1.0.0-local", "marker": "local"}
    server = {"modId": "amp_autotest_conflict", "version": "2.0.0-server", "marker": "server"}

    assert valid_mod_jar_bytes(local) != valid_mod_jar_bytes(server)
    runner._v_stage_modpack(ctx, {
        "recordOnly": True,
        "packId": "packbbb",
        "files": [{"path": "mods/amp-autotest-conflict.jar", "fixture": server}],
    })

    records = list((ctx.game_dir / "automodpack/client/records").glob("*/manifest.json"))
    assert len(records) == 1
    manifest = json.loads(records[0].read_text())
    metadata = manifest["groups"]["main"]["files"]["mods/amp-autotest-conflict.jar"]
    object_path = ctx.game_dir / "automodpack/client/data/objects" / metadata["sha1"]
    assert_valid_mod_fixture(object_path.read_bytes(), server)


def test_record_only_generation_state_digest_matches_its_manifest(make_ctx):
    ctx = make_ctx(modpack_name="Pack B", marker_rel=Path("config/marker.json"))
    runner._v_stage_modpack(ctx, {
        "recordOnly": True,
        "packId": "packbbb",
        "packName": "Pack B",
        "files": [{"path": "config/b.txt", "content": "b"}],
    })

    manifest_path = next((ctx.game_dir / "automodpack/client/records").glob("*/manifest.json"))
    manifest = json.loads(manifest_path.read_text())
    encoder = (CanonicalEncoder().string("automodpack-state-v1").string(manifest["modpackId"]).string(manifest["modpackName"])
               .string(manifest["automodpackVersion"]).string(manifest["loader"]).string(manifest["loaderVersion"])
               .string(manifest["mcVersion"]).integer(len(manifest["groups"])))
    for group_id, group in sorted(manifest["groups"].items()):
        encoder.string(group_id).string(group["displayName"]).string(group["description"]).string(group["tag"])
        encoder.boolean(group["required"]).boolean(group["defaultSelected"])
        for values in (group["breaksWith"], group["requires"]):
            encoder.integer(len(values))
            for value in sorted(values):
                encoder.string(value)
        encoder.integer(len(group["compatiblePlatforms"]))
        for platform in sorted(group["compatiblePlatforms"]):
            encoder.string(platform)
        encoder.integer(len(group["files"]))
        for logical_path, file in sorted(group["files"].items()):
            encoder.string(logical_path).long(int(file["size"])).string(file["type"]).boolean(file["editable"])
            encoder.boolean(file["overwriteEditable"]).string(file["sha1"]).string(file["murmur"])

    assert manifest["generation"]["stateDigest"] == encoder.digest()


# ── wait_exit (Docker calls stubbed) ─────────────────────────────────────────


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


def test_describe_lists_real_and_new_verbs():
    # Importing runner registers the Docker lifecycle verbs.
    all_names = set(names())
    assert {"stage_modpack", "wait_exit", "click", "assert"}.issubset(all_names)
    entries = describe()
    by_name = {n: e for e in entries for n in e["names"]}
    # Aliased handler groups its names together.
    assert "wait_client_exit" in by_name["wait_exit"]["names"]
    assert by_name["stage_modpack"]["doc"]  # has a docstring summary
