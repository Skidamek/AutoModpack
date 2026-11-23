from __future__ import annotations

import json
import os
import random
import time
from dataclasses import dataclass
from pathlib import Path


class BridgeError(RuntimeError):
    def __init__(self, op: str, code: str | None, message: str):
        super().__init__(f"Bridge error on '{op}': {message}")
        self.code = code


# AutoTestBridge.SCREENSHOT_SETTLE_TIMEOUT_SECONDS: the client gives up on a screenshot that has not settled by then.
_CLIENT_SETTLE_SECONDS = 30
# Strictly larger, so the client's named settle error arrives instead of this side timing out first.
SCREENSHOT_TIMEOUT_SECONDS = _CLIENT_SETTLE_SECONDS + 15


@dataclass
class BridgeClient:
    game_dir: Path
    token: str

    def request(self, op: str, timeout: float = 30, **payload) -> dict:
        autotest_dir = self.game_dir / "automodpack" / "autotest"
        autotest_dir.mkdir(parents=True, exist_ok=True)
        cmd = autotest_dir / "bridge-command.json"
        rsp = autotest_dir / "bridge-response.json"
        rsp.unlink(missing_ok=True)
        tmp = cmd.with_suffix(".tmp")
        tmp.write_text(
            json.dumps({"token": self.token, "op": op, **payload}), encoding="utf-8"
        )
        deadline = time.monotonic() + timeout
        # Windows refuses rename/replace over a file the client still has open.
        # The bridge reads the command promptly, so a short retry is enough.
        while True:
            try:
                os.replace(tmp, cmd)
                break
            except PermissionError:
                if time.monotonic() >= deadline:
                    raise TimeoutError(f"Bridge command file stayed locked for '{op}' after {timeout}s") from None
                time.sleep(0.05)
        while time.monotonic() < deadline:
            if rsp.exists():
                try:
                    data = json.loads(rsp.read_text(encoding="utf-8"))
                except PermissionError:
                    time.sleep(0.05)
                    continue
                except json.JSONDecodeError:
                    time.sleep(0.05)
                    continue
                try:
                    rsp.unlink(missing_ok=True)
                except PermissionError:
                    pass
                if not data.get("ok"):
                    raise BridgeError(op, data.get("code"), str(data.get("error", data)))
                return data
            time.sleep(random.uniform(0.03, 0.07))
        raise TimeoutError(f"Bridge did not respond to '{op}' after {timeout}s")

    def gui(self, timeout: float = 30) -> dict:
        return self.request("gui", timeout=timeout)

    def click(self, element_id: int, timeout: float = 30, screen_revision: int | None = None, **payload) -> dict:
        if screen_revision is not None:
            payload["screenRevision"] = screen_revision
        return self.request("click", timeout=timeout, id=element_id, **payload)

    def text(self, element_id: int, value: str, timeout: float = 30, screen_revision: int | None = None) -> dict:
        payload = {"id": element_id, "text": value}
        if screen_revision is not None:
            payload["screenRevision"] = screen_revision
        return self.request("text", timeout=timeout, **payload)

    def screenshot(self, name: str, timeout: float = SCREENSHOT_TIMEOUT_SECONDS) -> dict:
        return self.request("screenshot", timeout=timeout, name=name)

    def connect(self, host: str, timeout: float = 30) -> dict:
        return self.request("connect", timeout=timeout, host=host)
