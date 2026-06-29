"""Docker-free tests for the meta layer: scenario validation, target scoping,
transport/mode selection, and verb discovery."""
from __future__ import annotations

import types

from automodpack_autotester import runner
from automodpack_autotester.config import (
    load_macros,
    load_scenarios,
    scenario_matches_target,
)
from automodpack_autotester.engine.registry import describe, names
from automodpack_autotester.validate import validate_scenario


def _target(**kw):
    base = dict(id="1.21.1-neoforge", minecraft="1.21.1", loader="neoforge", java=21)
    base.update(kw)
    return types.SimpleNamespace(**base)


# ── validation ─────────────────────────────────────────────────────────────


def test_shipped_scenarios_validate():
    macros = load_macros()
    for name, scenario in load_scenarios().items():
        assert validate_scenario(scenario, macros) == [], name


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


def test_validate_handles_recursive_macros():
    # Mutually recursive macros must not blow the stack.
    macros = {"a": [{"use": "b"}], "b": [{"use": "a"}]}
    assert validate_scenario({"flow": [{"use": "a"}]}, macros) == []


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
