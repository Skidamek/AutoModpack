"""Static scenario validation — catch typos before a multi-minute Docker run.

Walks a scenario's ``flow`` (expanding macros), and checks that every verb and
macro name resolves and that every condition uses known keys. Returns a list of
human-readable problems; an empty list means the scenario is well-formed.
"""
from __future__ import annotations

from .engine import conditions
from .engine.registry import get as get_verb

_VALID_MODES = {"full", "client-only"}
_VALID_NETWORKS = {"bridge", "host"}
_COND_FIELDS = ("when", "until", "that")


def validate_scenario(scenario: dict, macros: dict) -> list[str]:
    problems: list[str] = []

    mode = str(scenario.get("mode", "full")).lower()
    if mode not in _VALID_MODES:
        problems.append(f"unknown mode {mode!r} (expected one of {sorted(_VALID_MODES)})")

    net = scenario.get("network")
    if net is not None and str(net).lower() not in _VALID_NETWORKS:
        problems.append(f"unknown network {net!r} (expected one of {sorted(_VALID_NETWORKS)})")

    known_macros = dict(macros)
    known_macros.update(scenario.get("sequences") or {})

    flow = scenario.get("flow")
    if not flow:
        problems.append("scenario has no 'flow'")
        return problems

    _walk(flow, known_macros, problems, seen=set())
    return problems


def _walk(steps, macros, problems, seen, depth=0):
    if depth > 25:
        problems.append("macro expansion too deep (cycle in sequences?)")
        return
    for raw in steps:
        if isinstance(raw, str):
            step = {"use": raw} if raw in macros else {"do": raw}
        elif isinstance(raw, dict):
            step = dict(raw)
        else:
            problems.append(f"invalid step: {raw!r}")
            continue

        for field in _COND_FIELDS:
            if field in step:
                _check_condition(step[field], problems, where=f"{_label(step)}.{field}")

        if "use" in step:
            name = step["use"]
            if name not in macros:
                problems.append(f"unknown macro: {name!r}")
            elif name not in seen:  # guard against macro recursion blowing the stack
                _walk(macros[name], macros, problems, seen | {name}, depth + 1)
        elif "group" in step:
            _walk(step.get("steps", []), macros, problems, seen, depth + 1)
        else:
            verb = step.get("do")
            if get_verb(verb) is None:
                problems.append(f"unknown verb: {verb!r}")


def _check_condition(cond, problems, where):
    if not isinstance(cond, dict):
        problems.append(f"{where}: condition must be a mapping, got {cond!r}")
        return
    for key, val in cond.items():
        if key in ("all", "any"):
            for sub in val if isinstance(val, list) else []:
                _check_condition(sub, problems, where)
        elif key == "not":
            _check_condition(val, problems, where)
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


def _label(step):
    return step.get("name") or step.get("do") or step.get("use") or "?"
