"""Static scenario validation — catch typos before a multi-minute Docker run."""
from __future__ import annotations

import re
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


def validate_scenario(scenario: dict, macros: dict, targets: dict | None = None) -> list[str]:
    problems: list[str] = []

    if not isinstance(scenario.get("id"), str) or not scenario["id"].strip():
        problems.append("scenario needs a non-empty string 'id'")

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
