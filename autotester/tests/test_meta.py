"""Docker-free tests for the meta layer: scenario validation, target scoping,
transport/mode selection, and verb discovery."""
from __future__ import annotations

import hashlib
import json
import types

import pytest

from automodpack_autotester import cli, runner
from automodpack_autotester.config import (
    load_macros,
    load_scenarios,
    load_targets,
    scenario_matches_target,
)
from automodpack_autotester.engine.registry import describe, names
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


def test_staged_manifest_uses_actual_file_metadata(make_ctx):
    ctx = make_ctx()
    root = ctx.game_dir / "staged"
    marker = root / ctx.marker_rel
    marker.parent.mkdir(parents=True)
    marker.write_text("marker\n")
    mod = root / "mods" / "fixture.jar"
    mod.parent.mkdir()
    mod.write_bytes(b"fixture")

    runner._write_staged_manifest(ctx, root, "fixture-id")

    manifest = json.loads((root / "automodpack-content.json").read_text())
    by_path = {entry["file"]: entry for entry in manifest["list"]}
    assert by_path["/mods/fixture.jar"]["size"] == str(len(b"fixture"))
    assert by_path["/mods/fixture.jar"]["sha1"] == hashlib.sha1(b"fixture").hexdigest()
    assert by_path["/mods/fixture.jar"]["editable"] is False
    assert by_path["/config/amp-autotest-marker.json"]["editable"] is True


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
