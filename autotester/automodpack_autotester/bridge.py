"""Fast persistent TCP JSON-RPC client for AutoTestBridge (with request IDs and UI helpers)."""
from __future__ import annotations

import json
import socket
import threading
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Any, Callable


class BridgeError(RuntimeError):
    def __init__(self, message: str, code: str = ""):
        super().__init__(message)
        self.code = code


@dataclass(frozen=True)
class Selector:
    role: str = "any"  # button | textfield | other | any
    key: str | None = None
    text: str | None = None
    enabled: bool | None = None
    visible: bool | None = True
    checked: bool | None = None
    index: int = 0


def _matches(element: dict[str, Any], sel: Selector) -> bool:
    if sel.visible is not None and bool(element.get("visible", True)) != sel.visible:
        return False
    if sel.enabled is not None and bool(element.get("enabled", True)) != sel.enabled:
        return False
    if sel.checked is not None and element.get("checked") != sel.checked:
        return False
    if sel.key is not None:
        keys = set(element.get("keys") or ())
        if element.get("key"):
            keys.add(str(element["key"]))
        if sel.key not in keys:
            return False
    if sel.text is not None:
        text = str(element.get("text") or "")
        if sel.text.lower() not in text.lower():
            return False
    return True


def select_elements(gui_state: dict[str, Any], sel: Selector) -> list[dict[str, Any]]:
    role = sel.role.lower()
    if role == "button":
        pool = list(gui_state.get("buttons") or ())
    elif role == "textfield":
        pool = list(gui_state.get("textFields") or ())
    elif role == "other":
        pool = list(gui_state.get("other") or ())
    else:
        pool = list(gui_state.get("buttons") or ()) + list(gui_state.get("textFields") or ()) + list(gui_state.get("other") or ())
    matched = [e for e in pool if _matches(e, sel)]
    if sel.text is not None and len(matched) > 1:
        exact = [e for e in matched if str(e.get("text") or "").lower() == sel.text.lower()]
        if exact:
            matched = exact
    return matched


class BridgeClient:
    """Line-delimited JSON-RPC over loopback TCP to AutoTestBridge."""

    def __init__(self, host: str, port: int, token: str, timeout: float = 20.0):
        self.host = host
        self.port = port
        self.token = token
        self.timeout = timeout
        self._lock = threading.Lock()
        self._sock: socket.socket | None = None
        self._rfile = None
        self._next_id = 1

    @classmethod
    def from_state_file(cls, game_dir: Path, token: str, host: str = "127.0.0.1", timeout: float = 20.0) -> "BridgeClient | None":
        state_file = game_dir / "automodpack" / "autotest" / "bridge-state.json"
        if not state_file.is_file():
            return None
        try:
            data = json.loads(state_file.read_text(encoding="utf-8"))
        except (OSError, json.JSONDecodeError):
            return None
        if data.get("status") != "ready" or not data.get("port"):
            return None
        return cls(host=host, port=int(data["port"]), token=token, timeout=timeout)

    def close(self) -> None:
        with self._lock:
            self._reset_locked()

    def _reset_locked(self) -> None:
        if self._rfile is not None:
            try:
                self._rfile.close()
            except OSError:
                pass
            self._rfile = None
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
            self._sock = None

    def _ensure_connected_locked(self, timeout: float) -> None:
        if self._sock is not None:
            self._sock.settimeout(timeout)
            return
        sock = socket.create_connection((self.host, self.port), timeout=timeout)
        sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        sock.settimeout(timeout)
        self._sock = sock
        self._rfile = sock.makefile("r", encoding="utf-8", newline="\n")

    def call(self, op: str, timeout: float | None = None, **params: Any) -> dict[str, Any]:
        call_timeout = timeout if timeout is not None else self.timeout
        with self._lock:
            req_id = self._next_id
            self._next_id += 1
            payload = {"reqId": req_id, "token": self.token, "op": op, **params}
            raw = (json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8")
            last_error: Exception | None = None
            for attempt in range(2):
                try:
                    self._ensure_connected_locked(call_timeout)
                    assert self._sock is not None and self._rfile is not None
                    self._sock.sendall(raw)
                    while True:
                        line = self._rfile.readline()
                        if not line:
                            raise ConnectionError("bridge closed connection")
                        resp = json.loads(line)
                        if resp.get("reqId") is not None and resp.get("reqId") != req_id:
                            continue
                        if not resp.get("ok"):
                            raise BridgeError(str(resp.get("error", "bridge call failed")), code=str(resp.get("code", "")))
                        return resp
                except (OSError, ConnectionError, json.JSONDecodeError) as e:
                    last_error = e
                    self._reset_locked()
                    if attempt == 0:
                        continue
            raise BridgeError(f"bridge {op!r} failed: {last_error}") from last_error

    def ping(self) -> bool:
        self.call("ping")
        return True

    def state(self) -> dict[str, Any]:
        return self.call("state")

    def gui(self) -> dict[str, Any]:
        return self.call("gui")

    def connect(self, host: str) -> None:
        self.call("connect", host=host)

    def disconnect(self) -> None:
        self.call("disconnect")

    def quit(self) -> None:
        self.call("quit")

    def screenshot(self, name: str) -> dict[str, Any]:
        return self.call("screenshot", name=name, timeout=35.0)

    def key(self, key_code: int, scan_code: int = 0, modifiers: int = 0) -> None:
        self.call("key", keyCode=key_code, scanCode=scan_code, modifiers=modifiers)

    def scroll(self, dy: float, dx: float = 0.0, element_id: int | None = None) -> None:
        params: dict[str, Any] = {"dx": dx, "dy": dy}
        if element_id is not None:
            params["id"] = element_id
        self.call("scroll", **params)

    def click(self, sel: Selector, *, button: int = 0, timeout: float = 15.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        last_err: Exception | None = None
        while time.monotonic() < deadline:
            g = self.gui()
            matches = select_elements(g, sel)
            if matches:
                idx = sel.index if sel.index >= 0 else len(matches) + sel.index
                if 0 <= idx < len(matches):
                    el = matches[idx]
                    try:
                        self.call("click", id=el["id"], button=button, screenRevision=g["screenRevision"])
                        return el
                    except BridgeError as e:
                        if e.code == "stale_screen":
                            last_err = e
                            continue
                        raise
            time.sleep(0.05)
        raise TimeoutError(f"Element matching {sel} not clickable within {timeout}s (last={last_err})")

    def type_text(self, sel: Selector, value: str, *, timeout: float = 15.0) -> dict[str, Any]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            g = self.gui()
            matches = select_elements(g, sel)
            if matches:
                el = matches[sel.index]
                try:
                    self.call("text", id=el["id"], text=value, screenRevision=g["screenRevision"])
                    return el
                except BridgeError as e:
                    if e.code == "stale_screen":
                        continue
                    raise
            time.sleep(0.05)
        raise TimeoutError(f"TextField matching {sel} not found within {timeout}s")

    def wait_until(self, predicate: Callable[[dict[str, Any]], Any], *, timeout: float = 30.0, poll: float = 0.05, use_gui: bool = True, desc: str = "condition") -> Any:
        deadline = time.monotonic() + timeout
        last_snapshot: dict[str, Any] = {}
        while time.monotonic() < deadline:
            last_snapshot = self.gui() if use_gui else self.state()
            val = predicate(last_snapshot)
            if val:
                return val
            time.sleep(poll)
        raise TimeoutError(f"Timed out after {timeout}s waiting for {desc}; last screen={last_snapshot.get('screenName')!r}")

    def wait_screen(self, name: str, *, timeout: float = 30.0) -> dict[str, Any]:
        return self.wait_until(
            lambda s: s if s.get("screenName") == name or (s.get("screenClass") or "").endswith("." + name) else None,
            timeout=timeout,
            use_gui=False,
            desc=f"screen {name!r}",
        )

    def wait_in_world(self, *, timeout: float = 60.0) -> dict[str, Any]:
        return self.wait_until(lambda s: s if s.get("inWorld") else None, timeout=timeout, use_gui=False, desc="client in world")

    def assert_layout_clean(self) -> None:
        """Verify visible buttons/textfields stay within screen bounds and do not overlap."""
        g = self.gui()
        sw, sh = int(g.get("screenWidth") or 0), int(g.get("screenHeight") or 0)
        if sw <= 0 or sh <= 0:
            return
        widgets = [e for e in (list(g.get("buttons") or ()) + list(g.get("textFields") or ())) if e.get("visible", True)]
        for w in widgets:
            x, y, ww, hh = int(w["x"]), int(w["y"]), int(w["width"]), int(w["height"])
            if x < -2 or y < -2 or x + ww > sw + 2 or y + hh > sh + 2:
                raise AssertionError(f"Widget {w.get('text')!r} out of bounds ({x},{y},{ww}x{hh}) on {sw}x{sh} screen {g.get('screenName')}")
        for i, a in enumerate(widgets):
            for b in widgets[i + 1 :]:
                ax1, ay1, ax2, ay2 = int(a["x"]), int(a["y"]), int(a["x"]) + int(a["width"]), int(a["y"]) + int(a["height"])
                bx1, by1, bx2, by2 = int(b["x"]), int(b["y"]), int(b["x"]) + int(b["width"]), int(b["y"]) + int(b["height"])
                if ax1 < bx2 and ax2 > bx1 and ay1 < by2 and ay2 > by1:
                    raise AssertionError(f"Overlapping widgets on {g.get('screenName')}: {a.get('text')!r} and {b.get('text')!r}")
