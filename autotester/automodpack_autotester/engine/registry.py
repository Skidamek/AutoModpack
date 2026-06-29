"""Verb registry. Step verbs register here and the executor looks them up by name."""
from __future__ import annotations

from collections.abc import Callable

VERBS: dict[str, Callable] = {}


def verb(*names: str) -> Callable:
    """Register a step handler under one or more verb names.

    A handler has the signature ``fn(ctx, step) -> None`` where ``step`` is the
    raw step mapping from the scenario.
    """

    def deco(fn: Callable) -> Callable:
        for name in names:
            VERBS[name] = fn
        return fn

    return deco


def get(name: str) -> Callable | None:
    return VERBS.get(name)


def names() -> list[str]:
    """All registered verb names, sorted."""
    return sorted(VERBS)


def describe() -> list[dict]:
    """One entry per handler: its name aliases and first docstring line.

    Drives ``autotester verbs`` so the available verbs are discoverable without
    grepping ``@verb(`` across the engine and runner.
    """
    by_fn: dict[Callable, list[str]] = {}
    for name, fn in VERBS.items():
        by_fn.setdefault(fn, []).append(name)
    out = []
    for fn, aliases in by_fn.items():
        doc = (fn.__doc__ or "").strip().splitlines()
        out.append({
            "names": sorted(aliases),
            "doc": doc[0].strip() if doc else "",
        })
    return sorted(out, key=lambda e: e["names"][0])
