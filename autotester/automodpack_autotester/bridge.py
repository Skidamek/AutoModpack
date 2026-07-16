from __future__ import annotations

import json
import random
import time
from dataclasses import dataclass
from pathlib import Path


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
        tmp.rename(cmd)
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if rsp.exists():
                data = json.loads(rsp.read_text(encoding="utf-8"))
                rsp.unlink(missing_ok=True)
                if not data.get("ok"):
                    raise RuntimeError(f"Bridge error on '{op}': {data.get('error', data)}")
                return data
            time.sleep(random.uniform(0.03, 0.07))
        raise TimeoutError(f"Bridge did not respond to '{op}' after {timeout}s")

    def gui(self, timeout: float = 30) -> dict:
        return self.request("gui", timeout=timeout)

    def click(self, element_id: int, timeout: float = 30, **payload) -> dict:
        return self.request("click", timeout=timeout, id=element_id, **payload)

    def text(self, element_id: int, value: str, timeout: float = 30) -> dict:
        return self.request("text", timeout=timeout, id=element_id, text=value)

    def connect(self, host: str, timeout: float = 30) -> dict:
        return self.request("connect", timeout=timeout, host=host)
