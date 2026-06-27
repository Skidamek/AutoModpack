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
