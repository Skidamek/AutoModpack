"""UI verbs: click, type/paste, wait_for, assert, sleep."""
from __future__ import annotations

import time

from . import conditions, selectors
from .registry import verb
from .util import ClientExited, await_condition, parse_duration


def _await_element(ctx, selector, step, not_found):
    """Poll the GUI until ``selector`` matches an element, or time out."""
    timeout = parse_duration(step.get("timeout"), default=30)
    return await_condition(
        lambda: selectors.find_one(ctx.gui(), selector),
        timeout,
        step.get("poll"),
        not_found,
    )


@verb("click")
def click(ctx, step):
    selector = dict(ctx.resolve(step.get("select") or {}))
    selector.setdefault("enabled", True)  # by default only click clickable elements
    el = _await_element(ctx, selector, step, f"no element matched {selector!r}")
    if step.get("enable"):
        ctx.bridge.click(int(el["id"]), enable=True)
    else:
        ctx.bridge.click(int(el["id"]))


@verb("type", "paste")
def type_(ctx, step):
    selector = dict(ctx.resolve(step.get("select") or {"role": "textfield"}))
    value = str(ctx.resolve(step.get("value", "")))
    el = _await_element(ctx, selector, step, f"no text field matched {selector!r}")
    ctx.bridge.text(int(el["id"]), value)


@verb("wait_for")
def wait_for(ctx, step):
    cond = step.get("until") or {}
    timeout = parse_duration(step.get("timeout"), default=60)

    def _pred():
        if conditions.evaluate(ctx, cond):
            return True
        # Not met yet. If the client container has already exited, the condition can never
        # become true - fail fast (raise ClientExited) instead of polling to the timeout.
        # Re-check the condition after detecting the exit so a marker the container printed
        # in its final, now-complete logs still counts (exit-right-after-marker race).
        try:
            ctx.assert_client_running()
        except ClientExited:
            if conditions.evaluate(ctx, cond):
                return True
            raise
        return None

    await_condition(
        _pred,
        timeout,
        step.get("poll"),
        f"condition not met: {conditions.describe(cond)}",
    )


@verb("assert")
def assert_(ctx, step):
    cond = step.get("that") or step.get("until") or {}
    if not conditions.evaluate(ctx, cond):
        raise AssertionError(f"assertion failed: {conditions.describe(cond)}")


@verb("sleep")
def sleep(ctx, step):
    dur = parse_duration(step.get("duration") or step.get("seconds"), default=1)
    time.sleep(dur or 0)
