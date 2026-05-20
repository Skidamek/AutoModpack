from __future__ import annotations

import json
import time
from dataclasses import dataclass
from pathlib import Path


@dataclass
class BridgeClient:
    game_dir: Path
    token: str

    def request(self, op: str, **payload) -> dict:
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
        deadline = time.monotonic() + 30
        while time.monotonic() < deadline:
            if rsp.exists():
                data = json.loads(rsp.read_text(encoding="utf-8"))
                rsp.unlink(missing_ok=True)
                if not data.get("ok"):
                    raise RuntimeError(f"Bridge error on '{op}': {data.get('error', data)}")
                return data
            time.sleep(0.05)
        raise TimeoutError(f"Bridge did not respond to '{op}' after 30s")
