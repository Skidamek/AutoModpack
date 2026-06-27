"""Shared helpers: duration parsing and the polling primitive used by every wait."""
from __future__ import annotations

import random
import time
from collections.abc import Callable
from typing import Any


class ClientExited(RuntimeError):
    """The client container is gone. Raised through waits instead of being retried."""


def parse_duration(value: Any, default: float | None = None) -> float | None:
    """Parse ``90s`` / ``3m`` / ``500ms`` / ``180`` into seconds."""
    if value is None:
        return float(default) if default is not None else None
    if isinstance(value, (int, float)):
        return float(value)
    s = str(value).strip().lower()
    try:
        if s.endswith("ms"):
            return float(s[:-2]) / 1000.0
        if s.endswith("s"):
            return float(s[:-1])
        if s.endswith("m"):
            return float(s[:-1]) * 60.0
        if s.endswith("h"):
            return float(s[:-1]) * 3600.0
        return float(s)
    except ValueError:
        return float(default) if default is not None else None


def await_condition(
    pred: Callable[[], Any],
    timeout: float | None,
    poll: Any = None,
    msg: str = "condition not met",
) -> Any:
    """Poll ``pred`` until it returns a non-None value or the timeout elapses.

    Transient bridge errors (``TimeoutError`` / ``RuntimeError``) are swallowed and
    retried; :class:`ClientExited` is always re-raised so a dead client fails fast.
    Scenario errors (``ValueError``/``AssertionError``) propagate immediately.
    """
    interval = parse_duration(poll, default=0.5) or 0.5
    deadline = time.monotonic() + (timeout if timeout is not None else 60.0)
    last_err: Exception | None = None
    while time.monotonic() < deadline:
        try:
            result = pred()
            if result is not None:
                return result
        except ClientExited:
            raise
        except (TimeoutError, RuntimeError) as e:
            last_err = e
        time.sleep(interval * random.uniform(0.8, 1.2))
    suffix = f" (last error: {last_err})" if last_err else ""
    raise TimeoutError(f"{msg} within {timeout}s{suffix}")
