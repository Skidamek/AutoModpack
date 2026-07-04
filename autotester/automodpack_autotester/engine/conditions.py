"""Boolean conditions, shared by ``wait_for``, ``assert`` and ``when``.

A condition is a mapping; all keys must hold (AND). Keys:

  screen        screenClass/title contains (str or list -> any)
  screen_not    none of these
  screen_none   true -> in-game (no screen open)
  element       a selector that must match at least one element
  no_element    a selector that must match nothing
  file          path (relative to the client game dir) exists
  file_gone     path does not exist
  only_files    { dir, patterns } - dir (relative to the client game dir) contains only
                files matching one of the glob patterns (and nothing else)
  target        the running target id equals this (str) or is one of these (list) -
                lets one scenario file gate a step to specific targets, e.g. an
                assertion that only makes sense on loader generations that need a
                GAME-classloader bridge
  target_not    the running target id is none of these
  log           a log/text match (see ``_log`` below)
  all / any     combine a list of sub-conditions
  not           negate a sub-condition

The ``log`` condition matches against either a container's stdout or a file
artifact in the client game dir, and supports positive, negative and
quantified matchers::

  log:
    container: server|client   # source: container stdout (default: client)
    file: automodpack/.../debug.log   # OR a file under the game dir
    tail: 100000               # only the last N lines (default: the WHOLE log)
    matches: <regex>           # must be present
    matches_all: [<regex>...]  # every one must be present
    matches_any: [<regex>...]  # at least one must be present
    not_matches: <regex>|[...]  # none may be present
    count: N                   # `matches` must occur exactly N times
    min_count: N               # ... at least N times
    max_count: N               # ... at most N times
    capture: { var: group }    # capture a group of `matches` into a var

By default a ``log`` condition scans the *entire* log, not just a tail, so a
target line cannot be silently scrolled out of the window by later output.
"""
from __future__ import annotations

import re

from . import selectors

_GUI_KEYS = {"screen", "screen_not", "screen_none", "element", "no_element"}

# Every valid top-level condition key (used by `do`/`until`/`when`/`that`). Kept
# in sync with `_check`; consumed by the scenario validator.
KEYS = _GUI_KEYS | {"file", "file_gone", "only_files", "target", "target_not", "log", "all", "any", "not"}


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
    if key == "target":
        needles = [val] if isinstance(val, str) else list(val)
        return getattr(ctx.target, "id", None) in needles
    if key == "target_not":
        needles = [val] if isinstance(val, str) else list(val)
        return getattr(ctx.target, "id", None) not in needles
    if key == "only_files":
        return _only_files(ctx, val)
    if key == "log":
        return _log(ctx, val)
    raise ValueError(f"unknown condition key: {key!r}")


def _only_files(ctx, spec: dict) -> bool:
    from fnmatch import fnmatch
    directory = ctx.path(spec["dir"])
    patterns = spec["patterns"]
    patterns = [patterns] if isinstance(patterns, str) else list(patterns)
    if not directory.is_dir():
        return False
    for entry in directory.iterdir():
        if not entry.is_file() or not any(fnmatch(entry.name, ctx.resolve(p)) for p in patterns):
            return False
    return True


def _screen(gui: dict, val) -> bool:
    sc = str(gui.get("screenClass") or "")
    title = str(gui.get("title") or "")
    needles = [val] if isinstance(val, str) else list(val)
    return any(str(n) in sc or str(n) in title for n in needles)


_LOG_MATCHERS = ("matches", "matches_all", "matches_any", "not_matches",
                 "count", "min_count", "max_count")

# Every valid key inside a `log` condition (source selectors + matchers).
LOG_KEYS = {"container", "file", "tail", "capture", *_LOG_MATCHERS}


def _log(ctx, spec: dict) -> bool:
    text = _log_source(ctx, spec)

    if not any(k in spec for k in _LOG_MATCHERS):
        raise ValueError(
            "log condition needs at least one matcher "
            f"({', '.join(_LOG_MATCHERS)}): {spec!r}"
        )

    # Positive: a single `matches` must be present (and may capture groups).
    if "matches" in spec and not _search(ctx, spec["matches"], text, spec.get("capture")):
        return False

    for pat in spec.get("matches_all", []) or []:
        if not _search(ctx, pat, text):
            return False

    if "matches_any" in spec and not any(_search(ctx, p, text) for p in spec["matches_any"]):
        return False

    if "not_matches" in spec:
        nots = spec["not_matches"]
        nots = [nots] if isinstance(nots, str) else list(nots)
        if any(_search(ctx, p, text) for p in nots):
            return False

    # Quantified: count occurrences of `matches` (required for these keys).
    if any(k in spec for k in ("count", "min_count", "max_count")):
        if "matches" not in spec:
            raise ValueError("count/min_count/max_count require `matches`")
        n = len(re.findall(ctx.resolve(str(spec["matches"])), text, re.IGNORECASE | re.MULTILINE))
        if "count" in spec and n != int(spec["count"]):
            return False
        if "min_count" in spec and n < int(spec["min_count"]):
            return False
        if "max_count" in spec and n > int(spec["max_count"]):
            return False

    return True


def _log_source(ctx, spec: dict) -> str:
    """The text a ``log`` condition matches against: a game-dir file or container stdout.

    Files default to the whole artifact; container stdout defaults to the whole
    log too (``tail`` is opt-in), so early lines are never scrolled out of view.
    """
    if "file" in spec:
        path = ctx.path(spec["file"])
        try:
            return path.read_text(encoding="utf-8", errors="replace")
        except (FileNotFoundError, NotADirectoryError, OSError):
            return ""
    which = str(spec.get("container", "client"))
    return ctx.container_logs(which, spec.get("tail"))


def _search(ctx, pattern, text: str, capture: dict | None = None) -> bool:
    m = re.search(ctx.resolve(str(pattern)), text, re.IGNORECASE | re.MULTILINE)
    if not m:
        return False
    for var, group in (capture or {}).items():
        try:
            ctx.vars[str(var)] = m.group(int(group))
        except (IndexError, ValueError):
            pass
    return True
