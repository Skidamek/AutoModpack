"""UI verbs: click, type/paste, screenshot, wait_for, assert, sleep."""
from __future__ import annotations

import time
from dataclasses import dataclass

from ..bridge import BridgeError
from . import conditions, selectors
from .registry import verb
from .util import ClientExited, await_condition, parse_duration

_SKIP_CLICK = object()


@dataclass(frozen=True)
class _ElementMatch:
    element: dict
    screen_revision: int | None


def _await_element(ctx, selector, step, not_found, skip_if=None, timeout=None):
    """Poll the GUI until ``selector`` matches an element, or time out."""
    def candidate():
        gui = ctx.gui()
        if skip_if and conditions.evaluate(ctx, skip_if, gui):
            return _SKIP_CLICK
        element = selectors.find_one(gui, selector)
        return _ElementMatch(element, gui.get("screenRevision")) if element is not None else None

    return await_condition(
        candidate,
        parse_duration(step.get("timeout"), default=30) if timeout is None else timeout,
        step.get("poll"),
        not_found,
    )


def _interact(ctx, selector, step, not_found, action, skip_if=None):
    deadline = time.monotonic() + parse_duration(step.get("timeout"), default=30)
    while True:
        remaining = deadline - time.monotonic()
        if remaining <= 0:
            raise TimeoutError(not_found)
        match = _await_element(ctx, selector, step, not_found, skip_if, remaining)
        if match is _SKIP_CLICK:
            return
        try:
            return action(match)
        except BridgeError as error:
            if error.code != "stale_screen":
                raise


def _gui_diagnostic(gui):
    visible = []
    for role in ("buttons", "textFields", "other"):
        for element in gui.get(role, []):
            if element.get("visible", True) and element.get("text"):
                visible.append({
                    "role": role,
                    "id": element.get("id"),
                    "text": str(element.get("text")),
                    "key": element.get("key"),
                    "enabled": bool(element.get("enabled", False)),
                    **({"checked": bool(element["checked"])} if "checked" in element else {}),
                })
    return f"current screen: {gui.get('screenClass')!r}; title: {gui.get('title')!r}; visible elements: {visible!r}"


@verb("click")
def click(ctx, step):
    selector = dict(ctx.resolve(step.get("select") or {}))
    selector.setdefault("enabled", True)  # by default only click clickable elements
    selector.setdefault("visible", True)
    skip_if = ctx.resolve(step.get("skip_if") or {})
    try:
        def action(match):
            payload = {"screen_revision": match.screen_revision}
            if step.get("enable"):
                payload["enable"] = True
            return ctx.bridge.click(int(match.element["id"]), **payload)

        _interact(ctx, selector, step, f"no element matched {selector!r}", action, skip_if)
    except TimeoutError as error:
        if ctx.bridge is None:
            raise
        try:
            gui = ctx.gui()
        except (ClientExited, RuntimeError, TimeoutError) as snapshot_error:
            raise error from snapshot_error
        raise TimeoutError(f"{error}; {_gui_diagnostic(gui)}") from error


@verb("type", "paste")
def type_(ctx, step):
    selector = dict(ctx.resolve(step.get("select") or {"role": "textfield"}))
    value = str(ctx.resolve(step.get("value", "")))
    _interact(ctx, selector, step, f"no text field matched {selector!r}", lambda match: ctx.bridge.text(int(match.element["id"]), value, screen_revision=match.screen_revision))


@verb("screenshot")
def screenshot(ctx, step):
    """Capture the current Minecraft framebuffer into the case artifacts."""
    ctx.assert_client_running()
    if ctx.bridge is None:
        raise RuntimeError("bridge not ready (run wait_bridge first)")
    name = str(ctx.resolve(step.get("file") or "screen"))
    response = ctx.bridge.screenshot(name)
    if response.get("skipped"):
        # The client reports this when the captured screen closed before a frame
        # could settle; there is nothing left to capture, so the step moves on.
        return
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
        raise TimeoutError(f"{error}; {_gui_diagnostic(gui)}") from error


@verb("assert")
def assert_(ctx, step):
    cond = step.get("that") or step.get("until") or {}
    if not conditions.evaluate(ctx, cond):
        raise AssertionError(f"assertion failed: {conditions.describe(cond)}")


@verb("sleep")
def sleep(ctx, step):
    dur = parse_duration(step.get("duration") or step.get("seconds"), default=1)
    time.sleep(dur or 0)
