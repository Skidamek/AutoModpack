"""UI verbs: click, type/paste, screenshot, wait_for, assert, sleep."""
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


@verb("screenshot")
def screenshot(ctx, step):
    """Capture the current Minecraft framebuffer into the case artifacts."""
    ctx.assert_client_running()
    if ctx.bridge is None:
        raise RuntimeError("bridge not ready (run wait_bridge first)")
    name = str(ctx.resolve(step.get("file") or "screen"))
    response = ctx.bridge.screenshot(name)
    path = ctx.game_dir / str(response["path"])
    if not path.is_file():
        raise RuntimeError(f"client reported screenshot but did not create {path}")
    ctx.vars["screenshot"] = str(path)


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
        if conditions.requires_client(cond):
            try:
                ctx.assert_client_running()
            except ClientExited:
                if conditions.evaluate(ctx, cond):
                    return True
                raise
        return None

    try:
        await_condition(
            _pred,
            timeout,
            step.get("poll"),
            f"condition not met: {conditions.describe(cond)}",
        )
    except TimeoutError as error:
        if ctx.bridge is None:
            raise
        try:
            gui = ctx.gui()
        except (ClientExited, RuntimeError, TimeoutError) as snapshot_error:
            raise error from snapshot_error
        visible = list(
            dict.fromkeys(
                str(element.get("text", ""))
                for role in ("buttons", "textFields", "elements")
                for element in gui.get(role, [])
                if element.get("visible", True) and element.get("text")
            )
        )
        raise TimeoutError(f"{error}; current screen: {gui.get('screenClass')!r}; visible elements: {visible!r}") from error


@verb("assert")
def assert_(ctx, step):
    cond = step.get("that") or step.get("until") or {}
    if not conditions.evaluate(ctx, cond):
        raise AssertionError(f"assertion failed: {conditions.describe(cond)}")


@verb("sleep")
def sleep(ctx, step):
    dur = parse_duration(step.get("duration") or step.get("seconds"), default=1)
    time.sleep(dur or 0)
