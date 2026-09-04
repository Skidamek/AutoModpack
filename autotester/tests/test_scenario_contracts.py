"""Contracts for shipped scenarios, macros, targets, and engine verbs."""

from __future__ import annotations

import types

from automodpack_autotester import runner
from automodpack_autotester.config import (
    connection_path_variants,
    load_macros,
    load_scenarios,
    load_targets,
    scenario_matches_target,
)
from automodpack_autotester.engine.registry import VERBS, describe, names
from automodpack_autotester.generation_identity import CanonicalEncoder
from automodpack_autotester.validate import validate_scenario


def _target(**kw):
    base = {
        "id": "1.21.1-neoforge",
        "minecraft": "1.21.1",
        "loader": "neoforge",
        "java": 21,
    }
    base.update(kw)
    return types.SimpleNamespace(**base)


def test_shipped_scenarios_validate():
    macros = load_macros()
    targets = load_targets()
    for name, scenario in load_scenarios().items():
        assert validate_scenario(scenario, macros, targets) == [], name


def test_connection_path_variants_keep_modes_independent():
    scenario = {
        "id": "paths",
        "connectionPaths": [
            {"mode": "DIRECT", "bindPort": 25566, "endpointPort": 25566},
            {"mode": "MAGIC", "bindPort": -1, "endpointPort": 25565},
        ],
    }

    variants = connection_path_variants(scenario)

    assert [variant["id"] for variant in variants] == ["paths-direct", "paths-magic"]
    assert [variant["connectionPath"]["mode"] for variant in variants] == ["DIRECT", "MAGIC"]
    assert "connectionPath" not in scenario


def test_validation_rejects_generation_fixture_on_non_jar_path():
    scenario = {
        "id": "fixture-validation",
        "serverFiles": {
            "generations": [
                {
                    "files": [
                        {
                            "path": "config/old.txt",
                            "fixture": {
                                "modId": "old",
                                "version": "1",
                                "marker": "old",
                            },
                        }
                    ]
                }
            ]
        },
        "flow": [{"do": "quit"}],
    }
    problems = validate_scenario(scenario, load_macros(), load_targets())
    assert any(
        "valid mod fixtures must use a .jar path" in problem for problem in problems
    )


def test_validation_requires_seed_mod_fixture_payload():
    scenario = {
        "id": "missing-fixture",
        "flow": [{"do": "seed_mod_fixture", "path": "mods/old.jar"}],
    }
    problems = validate_scenario(scenario, load_macros(), load_targets())
    assert any("seed_mod_fixture.fixture" in problem for problem in problems)


def test_validation_requires_write_file_content_string():
    scenario = {
        "id": "write-file-validation",
        "flow": [{"do": "write_file", "path": "config/editable.txt", "content": 7}],
    }

    problems = validate_scenario(scenario, load_macros(), load_targets())

    assert any(
        "write_file.content: expected a string" in problem for problem in problems
    )


def test_validation_rejects_invalid_runtime_mutation():
    scenario = {
        "id": "bad-runtime-mutation",
        "flow": [{"do": "mutate_active_object", "path": "config/owned.txt", "action": "rename"}],
    }

    problems = validate_scenario(scenario, load_macros(), load_targets())

    assert any("mutate_active_object.action: expected 'corrupt' or 'delete'" in problem for problem in problems)


def test_validation_requires_preservation_mutation_identity():
    scenario = {
        "id": "bad-preservation-mutation",
        "flow": [{"do": "mutate_preservation_object", "action": "corrupt"}],
    }

    problems = validate_scenario(scenario, load_macros(), load_targets())

    assert any("mutate_preservation_object.packId: expected a non-empty pack ID" in problem for problem in problems)


def test_validation_rejects_plain_text_unowned_jar_seed():
    scenario = load_scenarios()["all"]
    seed = next(
        step
        for step in scenario["flow"]
        if isinstance(step, dict) and step.get("do") == "seed_unowned_local_file"
    )
    seed.pop("fixture")

    problems = validate_scenario(scenario, load_macros(), load_targets())

    assert any(
        ".jar paths require a valid mod fixture mapping" in problem
        for problem in problems
    )


def test_release_gate_cannot_drop_a_required_capability():
    scenario = load_scenarios()["all"]
    scenario["releaseGate"]["covers"] = list(scenario["releaseGate"]["covers"][:-1])
    problems = validate_scenario(scenario, load_macros(), load_targets())
    assert any(
        "release-gate scenario must declare exactly" in problem for problem in problems
    )


def test_release_gate_cannot_duplicate_a_capability():
    scenario = load_scenarios()["all"]
    scenario["releaseGate"]["covers"].append("storage-maintenance")
    problems = validate_scenario(scenario, load_macros(), load_targets())
    assert any(
        "release-gate scenario must declare exactly" in problem for problem in problems
    )


def test_release_gate_requires_real_server_maintenance_coverage():
    scenario = load_scenarios()["all"]
    scenario["flow"] = [
        step for step in scenario["flow"]
        if not isinstance(step, dict) or step.get("do") != "collect_server_objects"
    ]

    problems = validate_scenario(scenario, load_macros(), load_targets())

    assert any("server-object-gc" in problem for problem in problems)


def test_release_gate_runs_server_maintenance_after_client_removal_and_in_order():
    flow = load_scenarios()["all"]["flow"]
    rollback = next(index for index, step in enumerate(flow) if isinstance(step, dict) and step.get("do") == "rollback_server_generation")
    collect = next(index for index, step in enumerate(flow) if isinstance(step, dict) and step.get("do") == "collect_server_objects")
    removal = max(index for index, step in enumerate(flow) if isinstance(step, dict) and "Pack A removal" in str(step.get("name", "")))

    assert removal < rollback < collect


def test_release_gate_exercises_content_history_from_management_settings():
    flow = load_scenarios()["all"]["flow"]
    history = next(index for index, step in enumerate(flow) if isinstance(step, dict) and step.get("do") == "click" and "generation history" in step.get("name", ""))
    content_wait = next(index for index, step in enumerate(flow) if index > history and isinstance(step, dict) and step.get("do") == "wait_for" and (step.get("until", {}).get("screen") == "ContentHistoryScreen" or any(condition.get("screen") == "ContentHistoryScreen" for condition in step.get("until", {}).get("all", []))))
    screenshot = next(index for index, step in enumerate(flow) if isinstance(step, dict) and step.get("do") == "screenshot" and step.get("file") == "all-content-history")
    entry_click = next(index for index, step in enumerate(flow) if isinstance(step, dict) and step.get("do") == "click" and "Pack B v2 removes" in step.get("select", {}).get("text", "") and index > screenshot)
    detail_wait = next(index for index, step in enumerate(flow) if isinstance(step, dict) and step.get("do") == "wait_for" and (step.get("until", {}).get("screen") == "ChangeBrowserScreen" or any(condition.get("screen") == "ChangeBrowserScreen" for condition in step.get("until", {}).get("all", []))) and index > entry_click)
    return_content = next(index for index, step in enumerate(flow) if isinstance(step, dict) and step.get("do") == "wait_for" and step.get("until", {}).get("screen") == "ContentHistoryScreen" and index > detail_wait)

    assert history < content_wait < screenshot < entry_click < detail_wait < return_content
    assert flow[content_wait]["until"].get("screen") == "ContentHistoryScreen"
    assert flow[detail_wait]["until"].get("screen") == "ChangeBrowserScreen"


def test_canonical_encoder_has_java_parity_vector():
    assert (
        CanonicalEncoder().string("parity").integer(7).long(11).boolean(True).digest()
        == "74298b52636c03aab0beb88c118b33b03343fd30"
    )


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
    problems = validate_scenario(
        {"mode": "weird", "network": "carrier-pigeon", "flow": ["quit"]}, {}
    )
    assert any("unknown mode" in p for p in problems)
    assert any("unknown network" in p for p in problems)


def test_validate_rejects_recursive_macros():
    macros = {"a": [{"use": "b"}], "b": [{"use": "a"}]}
    problems = validate_scenario({"id": "cycle", "flow": [{"use": "a"}]}, macros)
    assert any("macro cycle: a -> b -> a" in p for p in problems)


def test_validate_rejects_bad_duration_regex_and_repeat():
    scenario = {
        "id": "bad-fields",
        "flow": [
            {
                "do": "wait_for",
                "timeout": "soon",
                "repeat": 0,
                "until": {"log": {"matches": "["}},
            }
        ],
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
        "flow": [
            {
                "do": "stage_modpack",
                "mods": [
                    {
                        "url": {"a": "https://example.invalid/a.jar"},
                        "sha512": {"a": "a" * 128},
                    }
                ],
            }
        ],
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
    assert (
        scenario_matches_target(
            {"targets": ["1.21.1-*"]}, _target(id="1.21.1-neoforge")
        )
        is True
    )
    assert (
        scenario_matches_target({"targets": ["1.20.*"]}, _target(id="1.21.1-neoforge"))
        is False
    )
    assert (
        scenario_matches_target({"minecraft": ["1.21.*"]}, _target(minecraft="1.21.1"))
        is True
    )
    assert (
        scenario_matches_target({"minecraft": "1.20.1"}, _target(minecraft="1.21.1"))
        is False
    )


# ── transport / mode ────────────────────────────────────────────────────────


def test_transport_precedence():
    assert runner.transport({}, {}) == "bridge"
    assert runner.transport({}, {"network": "host"}) == "host"
    assert runner.transport({"network": "host"}, {"network": "bridge"}) == "host"


def test_scenario_mode():
    assert runner.scenario_mode({}) == "full"
    assert runner.scenario_mode({"mode": "client-only"}) == "client-only"


def test_describe_lists_real_and_new_verbs():
    # Importing runner registers the Docker lifecycle verbs.
    all_names = set(names())
    assert {"stage_modpack", "wait_exit", "click", "assert"}.issubset(all_names)
    entries = describe()
    by_name = {n: e for e in entries for n in e["names"]}
    # Aliased handler groups its names together.
    assert "wait_client_exit" in by_name["wait_exit"]["names"]
    assert by_name["stage_modpack"]["doc"]  # has a docstring summary


def test_scenarios_only_reference_known_verbs():
    """Static guard: every verb named in the shipped scenarios/macros exists."""
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
                if isinstance(raw.get("steps"), list):
                    yield from verbs_in(raw["steps"])

    used: set[str] = set()
    for seq in macros.values():
        used.update(verbs_in(seq))
    for scenario in scenarios.values():
        used.update(verbs_in(scenario.get("flow", [])))
        for step in scenario.get("flow", []):
            if isinstance(step, dict) and "use" in step:
                used.update(verbs_in(macros.get(step["use"], [])))

    unknown = {verb_name for verb_name in used if verb_name not in VERBS}
    assert not unknown, f"scenarios reference unregistered verbs: {unknown}"
