"""Boolean conditions, shared by ``wait_for``, ``assert`` and ``when``.

A condition is a mapping; all keys must hold (AND). Keys:

  screen        screenClass/title contains (str or list -> any)
  screen_not    none of these
  screen_none   true -> in-game (no screen open)
  element       a selector that must match at least one element
  no_element    a selector that must match nothing
  file          path (relative to the client game dir) exists
  file_gone     path does not exist
  log           {container: server|client, matches: <regex>, capture: {var: group}}
  all / any     combine a list of sub-conditions
  not           negate a sub-condition
"""
from __future__ import annotations

import re

from . import selectors

_GUI_KEYS = {"screen", "screen_not", "screen_none", "element", "no_element"}


def evaluate(ctx, cond: dict, gui: dict | None = None) -> bool:
    if not cond:
        return True
    if gui is None and _needs_gui(cond):
        gui = ctx.gui()
    return all(_check(ctx, key, val, gui) for key, val in cond.items())


def describe(cond: dict) -> str:
    return repr(cond)


def _needs_gui(cond: dict) -> bool:
    for key, val in cond.items():
        if key in _GUI_KEYS:
            return True
        if key in ("all", "any") and any(_needs_gui(s) for s in val):
            return True
        if key == "not" and _needs_gui(val):
            return True
    return False


def _check(ctx, key, val, gui) -> bool:
    if key == "all":
        return all(evaluate(ctx, sub, gui) for sub in val)
    if key == "any":
        return any(evaluate(ctx, sub, gui) for sub in val)
    if key == "not":
        return not evaluate(ctx, val, gui)
    if key == "screen":
        return _screen(gui, val)
    if key == "screen_not":
        return not _screen(gui, val)
    if key == "screen_none":
        return (gui.get("screenClass") is None) == bool(val)
    if key == "element":
        return selectors.find_one(gui, ctx.resolve(val)) is not None
    if key == "no_element":
        return selectors.find_one(gui, ctx.resolve(val)) is None
    if key == "file":
        return ctx.path(val).exists()
    if key == "file_gone":
        return not ctx.path(val).exists()
    if key == "log":
        return _log(ctx, val)
    raise ValueError(f"unknown condition key: {key!r}")


def _screen(gui: dict, val) -> bool:
    sc = str(gui.get("screenClass") or "")
    title = str(gui.get("title") or "")
    needles = [val] if isinstance(val, str) else list(val)
    return any(str(n) in sc or str(n) in title for n in needles)


def _log(ctx, spec: dict) -> bool:
    which = str(spec.get("container", "client"))
    pattern = ctx.resolve(str(spec["matches"]))
    logs = ctx.container_logs(which, spec.get("tail", 400))
    m = re.search(pattern, logs, re.IGNORECASE | re.MULTILINE)
    if not m:
        return False
    for var, group in (spec.get("capture") or {}).items():
        try:
            ctx.vars[str(var)] = m.group(int(group))
        except (IndexError, ValueError):
            pass
    return True
