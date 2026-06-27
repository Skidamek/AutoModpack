"""Run a scenario flow: expand macros, gate on ``when``, execute verbs, record results."""
from __future__ import annotations

import logging
import time

from . import conditions
from .registry import get as get_verb

logger = logging.getLogger(__name__)


def run_flow(ctx, scenario: dict, *, lib: dict | None = None, results: list | None = None) -> list[dict]:
    """Execute ``scenario['flow']`` and return a per-step result list.

    Pass ``results`` to collect step records into a caller-owned list, so partial
    results survive when the flow raises partway through.
    """
    macros = dict(lib or {})
    macros.update(scenario.get("sequences") or {})
    flow = scenario.get("flow")
    if not flow:
        raise ValueError("scenario has no 'flow'")
    if results is None:
        results = []
    _run_steps(ctx, flow, macros, results)
    return results


def _run_steps(ctx, steps, macros, results, depth=0):
    if depth > 25:
        raise RuntimeError("macro expansion too deep (cycle in sequences?)")
    for raw in steps:
        # Normalize to a mapping; a bare string is a macro name or a verb name.
        if isinstance(raw, str):
            step = {"use": raw} if raw in macros else {"do": raw}
        elif isinstance(raw, dict):
            step = dict(raw)
        else:
            raise ValueError(f"invalid step: {raw!r}")

        # `when` and `repeat` apply uniformly to every step kind (verb, use, group).
        when = step.get("when")
        if when is not None and not conditions.evaluate(ctx, ctx.resolve(when)):
            logger.info("[%s] skip (when not met): %s", _tid(ctx), _label(step))
            continue

        for _ in range(int(step.get("repeat", 1))):
            if "use" in step:
                name = step["use"]
                if name not in macros:
                    raise ValueError(f"unknown macro: {name!r}")
                _run_steps(ctx, macros[name], macros, results, depth + 1)
            elif "group" in step:
                _run_steps(ctx, step.get("steps", []), macros, results, depth + 1)
            else:
                _run_one(ctx, step, results)


def _run_one(ctx, step, results):
    verb_name = step.get("do")
    fn = get_verb(verb_name)
    if fn is None:
        raise ValueError(f"unknown step verb: {verb_name!r}")
    label = step.get("name") or verb_name
    started = time.monotonic()
    logger.info("[%s] step: %s", _tid(ctx), label)
    try:
        fn(ctx, step)
    except Exception as e:
        results.append({
            "name": label, "verb": verb_name, "ok": False,
            "duration": time.monotonic() - started, "error": str(e),
        })
        if step.get("optional"):
            logger.warning("[%s] optional step '%s' failed: %s", _tid(ctx), label, e)
            return
        raise RuntimeError(f"step '{label}' failed: {e}") from e
    results.append({
        "name": label, "verb": verb_name, "ok": True,
        "duration": time.monotonic() - started,
    })


def _tid(ctx):
    return getattr(ctx.target, "id", "?")


def _label(step):
    return step.get("name") or step.get("do") or step.get("use") or "?"
