"""UI verbs: click, type/paste, wait_for, assert, sleep."""
from __future__ import annotations

import time

from . import conditions, selectors
from .registry import verb
from .util import await_condition, parse_duration


@verb("click")
def click(ctx, step):
    selector = dict(ctx.resolve(step.get("select") or {}))
    if "enabled" not in selector:
        selector["enabled"] = True  # by default only click clickable elements
    timeout = parse_duration(step.get("timeout"), default=30)
    el = await_condition(
        lambda: selectors.find_one(ctx.gui(), selector),
        timeout,
        step.get("poll"),
        f"no element matched {selector!r}",
    )
    if step.get("enable"):
        ctx.bridge.click(int(el["id"]), enable=True)
    else:
        ctx.bridge.click(int(el["id"]))


@verb("type", "paste")
def type_(ctx, step):
    selector = dict(ctx.resolve(step.get("select") or {"role": "textfield"}))
    value = str(ctx.resolve(step.get("value", "")))
    timeout = parse_duration(step.get("timeout"), default=30)
    el = await_condition(
        lambda: selectors.find_one(ctx.gui(), selector),
        timeout,
        step.get("poll"),
        f"no text field matched {selector!r}",
    )
    ctx.bridge.text(int(el["id"]), value)


@verb("wait_for")
def wait_for(ctx, step):
    cond = step.get("until") or {}
    timeout = parse_duration(step.get("timeout"), default=60)
    await_condition(
        lambda: True if conditions.evaluate(ctx, cond) else None,
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
