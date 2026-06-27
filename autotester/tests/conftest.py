"""Shared fixtures: a Docker-free Context factory and a scriptable fake bridge.

These let the engine (selectors, conditions, templating, verbs, executor) be
exercised end to end without Docker, HeadlessMC, or a real Minecraft server.
"""
from __future__ import annotations

import types
from pathlib import Path

import pytest

from automodpack_autotester.engine import Context


@pytest.fixture
def make_ctx(tmp_path):
    """Build a Context backed by tmp dirs. Override any field via kwargs."""

    def _make(**overrides) -> Context:
        game_dir = overrides.pop("game_dir", tmp_path / "game")
        game_dir.mkdir(parents=True, exist_ok=True)
        defaults = dict(
            target=types.SimpleNamespace(
                id="1.21-fabric", minecraft="1.21", loader="fabric", java=21
            ),
            scenario={},
            settings={},
            game_dir=game_dir,
            server_dir=tmp_path / "server",
            out_dir=tmp_path / "out",
            client_image="img",
            srv_name="srv-container",
            cli_name="cli-container",
            net_name="net",
            token="tok",
            artifact=tmp_path / "automodpack.jar",
            modpack_name="amp-autotest",
            marker_rel=Path("config/amp-autotest-marker.json"),
            scenario_files=[],
            expected_mods=[],
        )
        defaults.update(overrides)
        ctx = Context(**defaults)
        ctx.running_provider = lambda: None  # client is always "running" in tests
        return ctx

    return _make


class FakeBridge:
    """A tiny GUI state machine that mimics the real client over the file bridge.

    Screens: title -> cert -> download -> restart -> (relaunch) -> ingame.
    Clicking the download button writes the modpack files into the game dir, so
    the filesystem verbs see real files appear exactly as they would in Docker.
    """

    def __init__(self, ctx: Context):
        self.ctx = ctx
        self.screen = "title"
        self.fingerprint: str | None = None
        self.synced = False
        self.exited = False
        self.clicks: list[int] = []
        self.typed: dict[int, str] = {}

    # --- snapshot ---------------------------------------------------------
    def gui(self, timeout: float = 30) -> dict:
        snapshots = {
            "title": {"screenClass": "TitleScreen", "buttons": [], "textFields": []},
            "cert": {
                "screenClass": "CertScreen",
                "buttons": [{"id": 2, "text": "Verify", "enabled": True, "visible": True}],
                "textFields": [{"id": 1, "text": "", "enabled": True, "visible": True}],
            },
            "download": {
                "screenClass": "DownloadScreen",
                "buttons": [{"id": 3, "text": "Download", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "restart": {
                "screenClass": "RestartScreen",
                "buttons": [{"id": 4, "text": "Close the game", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "ingame": {"screenClass": None, "buttons": [], "textFields": []},
        }
        return snapshots[self.screen]

    # --- actions ----------------------------------------------------------
    def text(self, element_id: int, value: str, timeout: float = 30) -> dict:
        self.typed[element_id] = value
        if element_id == 1:
            self.fingerprint = value
        return {"ok": True}

    def click(self, element_id: int, timeout: float = 30, **payload) -> dict:
        self.clicks.append(element_id)
        if element_id == 2 and self.fingerprint:
            self.screen = "download"
        elif element_id == 3:
            self._write_modpack()
            self.screen = "restart"
        elif element_id == 4:
            self.exited = True
        return {"ok": True}

    def connect(self, host: str, port: int = 25565, timeout: float = 30) -> dict:
        # Already-synced clients drop straight in-game; first contact hits the cert prompt.
        self.screen = "ingame" if self.synced else "cert"
        return {"ok": True}

    def request(self, op: str, timeout: float = 30, **payload) -> dict:
        if op == "disconnect":
            self.screen = "title"
        elif op == "quit":
            self.exited = True
        return {"ok": True}

    # --- helpers ----------------------------------------------------------
    def _write_modpack(self) -> None:
        root = self.ctx.game_dir / "automodpack" / "modpacks" / self.ctx.modpack_name
        marker = root / self.ctx.marker_rel
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text("{}")
        for rel, content in self.ctx.scenario_files:
            f = root / rel
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(content)
        self.synced = True
