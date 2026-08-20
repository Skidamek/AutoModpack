"""Static scenario validation — catch typos before a multi-minute Docker run."""
from __future__ import annotations

import re
from pathlib import Path
from urllib.parse import urlparse

from .config import scenario_matches_target
from .engine import conditions
from .engine.registry import get as get_verb
from .engine.util import parse_duration

_VALID_MODES = {"full", "client-only"}
_VALID_NETWORKS = {"bridge", "host"}
_COND_FIELDS = ("when", "until", "that")
_DURATION_FIELDS = ("timeout", "poll", "duration")
_REGEX_FIELDS = ("matches", "matches_all", "matches_any", "not_matches")
_COUNT_FIELDS = ("count", "min_count", "max_count")
_REMOTE_MOD_FIELDS = {"url", "sha512", "name"}
_SHA512 = re.compile(r"[0-9a-fA-F]{128}")
_PRESERVATION_REASONS = {"SERVER_REMOVAL", "MODPACK_REMOVAL", "MODPACK_DEACTIVATION", "LOCAL_CONFLICT", "STRICT_INSTALL", "STRICT_REPAIR", "EDITABLE_RESET"}
_PRESERVATION_STATUSES = {"AVAILABLE", "RESTORED", "SAVED_COPY"}
_RELEASE_GATE_CAPABILITIES = frozenset({
    "bootstrap",
    "groups",
    "patch-notes",
    "multiplayer-settings",
    "pack-switching",
    "generation-update",
    "conflict-preservation",
    "preservation-vault",
    "storage-maintenance",
    "server-history-compaction",
    "server-generation-rollback",
    "server-object-gc",
    "fresh-generation-deletion",
    "removal",
    "secure-bootstrap",
    "offline-repair",
    "repair-cas-integrity",
    "first-install-cleanup-consent",
    "repair-ui-navigation",
    "storage-verification",
})
_RELEASE_GATE_REQUIRED_VERBS = {
    "server-generation-rollback": "rollback_server_generation",
    "server-object-gc": "collect_server_objects",
    "offline-repair": "mutate_client_file",
    "repair-cas-integrity": "mutate_active_object",
    "storage-verification": "assert_client_object",
}


def validate_scenario(scenario: dict, macros: dict, targets: dict | None = None) -> list[str]:
    problems: list[str] = []

    if not isinstance(scenario.get("id"), str) or not scenario["id"].strip():
        problems.append("scenario needs a non-empty string 'id'")
    if scenario.get("id") == "all":
        release_macros = dict(macros)
        release_macros.update(scenario.get("sequences") or {})
        declared = scenario.get("releaseGate", {}).get("covers", [])
        if not isinstance(declared, list) or len(declared) != len(_RELEASE_GATE_CAPABILITIES) or set(declared) != _RELEASE_GATE_CAPABILITIES:
            problems.append(f"release-gate scenario must declare exactly these capabilities: {sorted(_RELEASE_GATE_CAPABILITIES)}")
        generations = (scenario.get("serverFiles", {}) or {}).get("generations")
        if not isinstance(generations, list) or len(generations) < 2:
            problems.append("release-gate scenario needs at least two serverFiles.generations entries")
        for capability, required_verb in _RELEASE_GATE_REQUIRED_VERBS.items():
            if not _contains_verb(scenario.get("flow", []), required_verb, release_macros):
                problems.append(f"release-gate scenario must cover {capability} with verb {required_verb!r}")
    generations = (scenario.get("serverFiles", {}) or {}).get("generations")
    if generations is not None:
        if not isinstance(generations, list):
            problems.append("serverFiles.generations must be a list")
        else:
            for index, generation in enumerate(generations):
                _check_generation_files(generation, problems, f"serverFiles.generations[{index}]")

    mode = str(scenario.get("mode", "full")).lower()
    if mode not in _VALID_MODES:
        problems.append(f"unknown mode {mode!r} (expected one of {sorted(_VALID_MODES)})")

    net = scenario.get("network")
    if net is not None and str(net).lower() not in _VALID_NETWORKS:
        problems.append(f"unknown network {net!r} (expected one of {sorted(_VALID_NETWORKS)})")

    for name, value in (scenario.get("timeouts") or {}).items():
        _check_duration(value, problems, f"timeouts.{name}")

    scoped_targets = []
    if targets is not None:
        declared = scenario.get("targets")
        if isinstance(declared, list):
            duplicates = sorted({x for x in declared if declared.count(x) > 1})
            if duplicates:
                problems.append(f"duplicate scenario target(s): {duplicates}")
            for pattern in declared:
                if not any(_target_pattern_matches(str(pattern), target_id) for target_id in targets):
                    problems.append(f"scenario target pattern matches nothing: {pattern!r}")
        scoped_targets = [t.id for t in targets.values() if scenario_matches_target(scenario, t)]
        if not scoped_targets:
            problems.append("scenario has no targets in scope")

    known_macros = dict(macros)
    known_macros.update(scenario.get("sequences") or {})

    flow = scenario.get("flow")
    if not isinstance(flow, list) or not flow:
        problems.append("scenario needs a non-empty list 'flow'")
        return problems

    _walk(flow, known_macros, problems, stack=(), scoped_targets=scoped_targets)
    return problems


def _target_pattern_matches(pattern: str, target_id: str) -> bool:
    from fnmatch import fnmatch

    return fnmatch(target_id, pattern)


def _walk(steps, macros, problems, stack, scoped_targets):
    if not isinstance(steps, list):
        problems.append(f"steps must be a list, got {steps!r}")
        return
    for raw in steps:
        if isinstance(raw, str):
            step = {"use": raw} if raw in macros else {"do": raw}
        elif isinstance(raw, dict):
            step = dict(raw)
        else:
            problems.append(f"invalid step: {raw!r}")
            continue

        label = _label(step)
        repeat = step.get("repeat", 1)
        if not isinstance(repeat, int) or isinstance(repeat, bool) or repeat < 1:
            problems.append(f"{label}.repeat: expected a positive integer, got {repeat!r}")
        for field in _DURATION_FIELDS:
            if field in step:
                _check_duration(step[field], problems, f"{label}.{field}")
        for field in _COND_FIELDS:
            if field in step:
                _check_condition(step[field], problems, where=f"{label}.{field}")

        if "use" in step:
            name = step["use"]
            if name not in macros:
                problems.append(f"unknown macro: {name!r}")
            elif name in stack:
                cycle = " -> ".join((*stack, name))
                problems.append(f"macro cycle: {cycle}")
            else:
                _walk(macros[name], macros, problems, (*stack, name), scoped_targets)
        elif "group" in step:
            _walk(step.get("steps", []), macros, problems, stack, scoped_targets)
        else:
            verb = step.get("do")
            if get_verb(verb) is None:
                problems.append(f"unknown verb: {verb!r}")
            if verb == "stage_modpack":
                _check_stage_modpack(step, problems, scoped_targets, label)
            elif verb == "publish_server_generation":
                _check_publish_generation(step, problems, label)
            elif verb == "assert_generation":
                _check_generation_assertion(step, problems, label)
            elif verb in ("assert_file_content", "wait_file_content", "write_file", "mutate_client_file", "mutate_active_object", "assert_client_object", "mutate_preservation_object", "seed_unowned_local_file", "seed_same_path_conflict", "seed_mod_fixture", "assert_mod_fixture", "assert_preservation_claim"):
                if not isinstance(step.get("path"), str) or not step["path"].strip():
                    if verb not in ("assert_preservation_claim", "mutate_preservation_object"):
                        problems.append(f"{label}.path: expected a non-empty relative path")
                if verb in ("wait_file_content", "write_file") and not isinstance(step.get("content"), str):
                    problems.append(f"{label}.content: expected a string")
                if verb in ("seed_unowned_local_file", "seed_same_path_conflict", "seed_mod_fixture", "assert_mod_fixture", "assert_preservation_claim", "mutate_preservation_object") and step.get("fixture") is not None:
                    _check_mod_fixture(step.get("fixture"), problems, f"{label}.fixture")
                if verb in ("seed_unowned_local_file", "seed_same_path_conflict", "seed_mod_fixture", "assert_mod_fixture") and step.get("fixture") is not None and isinstance(step.get("path"), str) and not step["path"].lower().endswith(".jar"):
                    problems.append(f"{label}.path: valid mod fixtures must use a .jar path")
                if verb == "seed_unowned_local_file" and step.get("fixture") is None and isinstance(step.get("path"), str) and step["path"].lower().endswith(".jar"):
                    problems.append(f"{label}.fixture: .jar paths require a valid mod fixture mapping")
                if verb == "seed_mod_fixture" and step.get("fixture") is None:
                    problems.append(f"{label}.fixture: this verb requires a valid mod fixture mapping")
                if verb in ("assert_preservation_claim", "mutate_preservation_object") and (not isinstance(step.get("packId"), str) or not step["packId"].strip()):
                    problems.append(f"{label}.packId: expected a non-empty pack ID")
                if verb in ("assert_preservation_claim", "mutate_preservation_object") and "content" in step and not isinstance(step["content"], str):
                    problems.append(f"{label}.content: expected a string")
                if verb in ("assert_preservation_claim", "mutate_preservation_object") and "originalPath" in step and (not isinstance(step["originalPath"], str) or not step["originalPath"].strip()):
                    problems.append(f"{label}.originalPath: expected a non-empty relative path")
                if verb in ("assert_preservation_claim", "mutate_preservation_object") and "reason" in step and step["reason"] not in _PRESERVATION_REASONS:
                    problems.append(f"{label}.reason: unknown preservation reason {step['reason']!r}")
                if verb in ("assert_preservation_claim", "mutate_preservation_object") and "status" in step and step["status"] not in _PRESERVATION_STATUSES:
                    problems.append(f"{label}.status: unknown preservation status {step['status']!r}")
                if verb in ("mutate_client_file", "mutate_active_object", "mutate_preservation_object") and step.get("action") not in ("corrupt", "delete"):
                    problems.append(f"{label}.action: expected 'corrupt' or 'delete'")
                for field in ("present", "valid", "objectValid"):
                    if field in step and not isinstance(step[field], bool):
                        problems.append(f"{label}.{field}: expected a boolean")
                if "count" in step and (not isinstance(step["count"], int) or isinstance(step["count"], bool) or step["count"] < 0):
                    problems.append(f"{label}.count: expected a non-negative integer")


def _contains_verb(steps, wanted, macros, stack=()):
    if not isinstance(steps, list):
        return False
    for raw in steps:
        if isinstance(raw, str):
            if raw == wanted:
                return True
            if raw in macros and raw not in stack and _contains_verb(macros[raw], wanted, macros, (*stack, raw)):
                return True
        elif isinstance(raw, dict):
            if raw.get("do") == wanted:
                return True
            name = raw.get("use")
            if name == wanted:
                return True
            if name in macros and name not in stack and _contains_verb(macros[name], wanted, macros, (*stack, name)):
                return True
            if _contains_verb(raw.get("steps"), wanted, macros, stack):
                return True
    return False


def _check_generation_files(generation, problems, where):
    if not isinstance(generation, dict):
        problems.append(f"{where}: expected a mapping")
        return
    files = generation.get("files", [])
    if not isinstance(files, list):
        problems.append(f"{where}.files: expected a list")
        return
    for index, item in enumerate(files):
        location = f"{where}.files[{index}]"
        if not isinstance(item, dict) or not isinstance(item.get("path"), str) or not item["path"].strip():
            problems.append(f"{location}: expected a mapping with a non-empty path")
            continue
        path = Path(item["path"])
        if path.is_absolute() or ".." in path.parts:
            problems.append(f"{location}.path: path must stay inside the server modpack")
        fixture = item.get("fixture")
        if fixture is not None:
            _check_mod_fixture(fixture, problems, f"{location}.fixture")
            if not item["path"].lower().endswith(".jar"):
                problems.append(f"{location}.path: valid mod fixtures must use a .jar path")


def _check_duration(value, problems, where):
    parsed = parse_duration(value)
    if parsed is None or parsed <= 0:
        problems.append(f"{where}: invalid positive duration {value!r}")


def _check_condition(cond, problems, where):
    if not isinstance(cond, dict):
        problems.append(f"{where}: condition must be a mapping, got {cond!r}")
        return
    for key, val in cond.items():
        if key in ("all", "any"):
            if not isinstance(val, list) or not val:
                problems.append(f"{where}.{key}: expected a non-empty list")
                continue
            for sub in val:
                _check_condition(sub, problems, f"{where}.{key}")
        elif key == "not":
            _check_condition(val, problems, f"{where}.not")
        elif key == "log":
            _check_log(val, problems, where)
        elif key not in conditions.KEYS:
            problems.append(f"{where}: unknown condition key {key!r}")


def _check_log(spec, problems, where):
    if not isinstance(spec, dict):
        problems.append(f"{where}: 'log' must be a mapping, got {spec!r}")
        return
    for key in spec:
        if key not in conditions.LOG_KEYS:
            problems.append(f"{where}.log: unknown key {key!r}")
    if not any(k in spec for k in conditions._LOG_MATCHERS):
        problems.append(f"{where}.log: needs at least one matcher {list(conditions._LOG_MATCHERS)}")

    for field in _REGEX_FIELDS:
        if field not in spec:
            continue
        values = spec[field] if isinstance(spec[field], list) else [spec[field]]
        for pattern in values:
            try:
                re.compile(str(pattern))
            except re.error as exc:
                problems.append(f"{where}.log.{field}: invalid regex {pattern!r}: {exc}")
    for field in _COUNT_FIELDS:
        if field not in spec:
            continue
        value = spec[field]
        if not isinstance(value, int) or isinstance(value, bool) or value < 0:
            problems.append(f"{where}.log.{field}: expected a non-negative integer")
        if "matches" not in spec:
            problems.append(f"{where}.log.{field}: requires 'matches'")


def _check_stage_modpack(step, problems, scoped_targets, where):
    if "manifest" in step and not isinstance(step["manifest"], bool):
        problems.append(f"{where}.manifest: expected a boolean")
    mods = step.get("mods", [])
    if not isinstance(mods, list):
        problems.append(f"{where}.mods: expected a list")
        return
    for index, entry in enumerate(mods):
        loc = f"{where}.mods[{index}]"
        if isinstance(entry, str):
            if not entry.strip():
                problems.append(f"{loc}: local path must not be empty")
            continue
        if not isinstance(entry, dict):
            problems.append(f"{loc}: expected a local path or remote mod mapping")
            continue
        unknown = sorted(set(entry) - _REMOTE_MOD_FIELDS)
        if unknown:
            problems.append(f"{loc}: unknown field(s) {unknown}")
        if "url" not in entry or "sha512" not in entry:
            problems.append(f"{loc}: remote mod needs 'url' and 'sha512'")
            continue
        _check_per_target(entry["url"], scoped_targets, problems, f"{loc}.url", _check_url)
        _check_per_target(entry["sha512"], scoped_targets, problems, f"{loc}.sha512", _check_sha512)
        name = entry.get("name")
        if name is not None and (not isinstance(name, str) or not name or "/" in name or "\\" in name):
            problems.append(f"{loc}.name: expected a plain filename")
    if "recordOnly" in step and not isinstance(step["recordOnly"], bool):
        problems.append(f"{where}.recordOnly: expected a boolean")
    if "packId" in step and (not isinstance(step["packId"], str) or not step["packId"].strip()):
        problems.append(f"{where}.packId: expected a non-empty string")
    files = step.get("files", [])
    if not isinstance(files, list):
        problems.append(f"{where}.files: expected a list")
    else:
        for index, item in enumerate(files):
            if not isinstance(item, dict) or not isinstance(item.get("path"), str) or not item["path"].strip():
                problems.append(f"{where}.files[{index}]: expected a mapping with a non-empty path")
            elif "fixture" in item:
                _check_mod_fixture(item["fixture"], problems, f"{where}.files[{index}].fixture")
                if not item["path"].lower().endswith(".jar"):
                    problems.append(f"{where}.files[{index}].path: valid mod fixtures must use a .jar path")
            if isinstance(item, dict) and "editable" in item and not isinstance(item["editable"], bool):
                problems.append(f"{where}.files[{index}].editable: expected a boolean")


def _check_mod_fixture(value, problems, where):
    if not isinstance(value, dict):
        problems.append(f"{where}: expected a mapping")
        return
    for field in ("modId", "version", "marker"):
        if not isinstance(value.get(field), str) or not value[field].strip():
            problems.append(f"{where}.{field}: expected a non-empty string")


def _check_publish_generation(step, problems, where):
    value = step.get("generation", 1)
    if not isinstance(value, int) or isinstance(value, bool) or value < 1:
        problems.append(f"{where}.generation: expected a positive integer")


def _check_generation_assertion(step, problems, where):
    groups = step.get("groups", {})
    if not isinstance(groups, dict):
        problems.append(f"{where}.groups: expected a mapping")
        return
    for group_id, requirements in groups.items():
        if not isinstance(group_id, str) or not group_id.strip() or not isinstance(requirements, dict):
            problems.append(f"{where}.groups: expected string IDs mapped to field mappings")


def _check_per_target(value, scoped_targets, problems, where, validator):
    if isinstance(value, dict):
        missing = sorted(set(scoped_targets) - set(value))
        if missing:
            problems.append(f"{where}: missing target entries {missing}")
        for target, item in value.items():
            validator(item, problems, f"{where}.{target}")
    else:
        validator(value, problems, where)


def _check_url(value, problems, where):
    parsed = urlparse(str(value))
    if parsed.scheme != "https" or not parsed.netloc:
        problems.append(f"{where}: expected an HTTPS URL")


def _check_sha512(value, problems, where):
    if _SHA512.fullmatch(str(value)) is None:
        problems.append(f"{where}: expected 128 hexadecimal characters")


def _label(step):
    return step.get("name") or step.get("do") or step.get("use") or "?"
