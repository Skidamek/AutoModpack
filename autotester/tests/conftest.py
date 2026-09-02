"""Shared fixtures: a Docker-free Context factory and a scriptable fake bridge.

These let the engine (selectors, conditions, templating, verbs, executor) be
exercised end to end without Docker, HeadlessMC, or a real Minecraft server.
"""
from __future__ import annotations

import json
import shutil
import types
from pathlib import Path

import pytest

from automodpack_autotester.engine import Context
from automodpack_autotester.mod_fixtures import valid_mod_jar_bytes


@pytest.fixture
def make_ctx(tmp_path):
    """Build a Context backed by tmp dirs. Override any field via kwargs."""

    def _make(**overrides) -> Context:
        game_dir = overrides.pop("game_dir", tmp_path / "game")
        game_dir.mkdir(parents=True, exist_ok=True)
        # Every real client has the loader jar in standard mods/ from the start (it's how
        # AutoModpack itself loads) - mirror that so `only_files`-style assertions on mods/
        # see the same baseline a real run would.
        (game_dir / "mods").mkdir(parents=True, exist_ok=True)
        (game_dir / "mods" / "automodpack.jar").write_bytes(b"")
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

    Screens: title -> cert -> preparing -> first connection -> preview -> restart -> (relaunch) -> ingame.
    Clicking the update button writes the active projection files into the game dir, so
    the filesystem verbs see real files appear exactly as they would in Docker.
    """

    def __init__(self, ctx: Context):
        self.ctx = ctx
        self.screen = "title"
        self.rendered_screen = self.screen
        self.fingerprint: str | None = None
        self.synced = False
        self.exited = False
        self.clicks: list[int] = []
        self.typed: dict[int, str] = {}
        self.screenshots: list[str] = []
        self.rendered_screens: dict[str, str] = {}
        self.secondary_pack = False
        self.selected_pack = "A"
        self.pending_pack: str | None = None
        self.pack_b_files: list[tuple[Path, bytes | str]] = []
        self.update_available = False
        self.bootstrap = False
        self.history_parent = "restart"
        self.dependency = False
        self.conflict = False
        self.quarantine_restored = False
        self.storage_running = False
        self.storage_complete = False
        self.baseline_snapshots: dict[Path, bytes] = {}

    # --- snapshot ---------------------------------------------------------
    def render_frame(self) -> None:
        self.rendered_screen = self.screen

    def gui(self, timeout: float = 30) -> dict:
        snapshots = {
            "title": {"screenClass": "TitleScreen", "title": "Title Screen", "buttons": [{"id": 6, "text": "Singleplayer", "enabled": True, "visible": True}, {"id": 8, "text": "Multiplayer", "enabled": True, "visible": True}], "textFields": []},
            "cert": {
                "screenClass": "CertScreen",
                "buttons": [{"id": 2, "text": "Verify", "enabled": True, "visible": True}],
                "textFields": [{"id": 1, "text": "", "enabled": True, "visible": True}],
            },
            "preparing": {"screenClass": "PreparingScreen", "buttons": [], "textFields": []},
            "first_connection": {
                "screenClass": "FirstConnectScreen",
                "buttons": [{"id": 3, "text": "Continue", "enabled": True, "visible": True},
                            {"id": 18, "text": "Customize groups", "enabled": True, "visible": True},
                            {"id": 26, "text": "Do not download", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "group0": {
                "screenClass": "ModpackSelectionScreen",
                "buttons": [{"id": 27, "text": "Core", "enabled": False, "visible": True},
                            {"id": 28, "text": "Client", "enabled": True, "visible": True},
                            {"id": 29, "text": ("[+] Visuals (required by selection)" if self.dependency else "Visuals"), "enabled": True, "visible": True},
                            {"id": 30, "text": "Next >", "enabled": True, "visible": True},
                            {"id": 31, "text": "Preview target", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "group1": {
                "screenClass": "ModpackSelectionScreen",
                "buttons": [{"id": 32, "text": "Extras", "enabled": True, "visible": True},
                            {"id": 33, "text": "Addon", "enabled": True, "visible": True},
                            {"id": 34, "text": ("Alternative (conflict)" if self.conflict else "Alternative"), "enabled": True, "visible": True},
                            {"id": 35, "text": "Platform", "enabled": True, "visible": True},
                            {"id": 39, "text": "< Prev", "enabled": True, "visible": True},
                            {"id": 36, "text": "Next >", "enabled": True, "visible": True},
                            {"id": 37, "text": "Defaults", "enabled": True, "visible": True},
                            {"id": 31, "text": "Preview target", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "group2": {
                "screenClass": "ModpackSelectionScreen",
                "buttons": [{"id": 38, "text": "Windows-only (unavailable)", "enabled": False, "visible": True},
                            {"id": 37, "text": "Defaults", "enabled": True, "visible": True},
                            {"id": 31, "text": "Preview target", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "preview": {
                "screenClass": "UpdatePreviewScreen",
                "buttons": [{"id": 5, "text": "Update", "enabled": True, "visible": True},
                            {"id": 17, "text": "View all patch notes", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "removal_preview": {
                "screenClass": "UpdatePreviewScreen",
                "buttons": [{"id": 42, "text": "Remove", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "restart": {
                "screenClass": "RestartScreen",
                "buttons": [
                    {"id": 6, "text": "No, back to the game", "enabled": True, "visible": True},
                    {"id": 4, "text": "Yes, close the game", "enabled": True, "visible": True},
                    {"id": 40, "text": "View changelogs", "enabled": True, "visible": True},
                ],
                "textFields": [],
            },
            "multiplayer": {
                "screenClass": "JoinMultiplayerScreen",
                "title": "Play Multiplayer",
                "buttons": [{"id": 7, "text": "Modpack settings", "enabled": True, "visible": True},
                            {"id": 8, "text": "Multiplayer", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "manager": {
                "screenClass": "InstalledModpacksScreen",
                "buttons": self._manager_buttons(),
                "textFields": [],
            },
            "storage": {
                "screenClass": "ClientStorageMaintenanceScreen",
                "buttons": [
                    {"id": 46, "text": "Cleanup complete" if self.storage_complete else "Clean local storage", "enabled": not self.storage_running and not self.storage_complete, "visible": True},
                    {"id": 47, "text": "Back", "enabled": True, "visible": True},
                ],
                "textFields": [],
            },
            "settings": {
                "screenClass": "ModpackSelectionScreen",
                "buttons": [{"id": 10, "text": "Pack manager", "enabled": True, "visible": True},
                            {"id": 13, "text": "Save", "enabled": True, "visible": True},
                            *([{"id": 43, "text": "Quarantine", "enabled": True, "visible": True}] if self._quarantine_available() else []),
                            *([{"id": 41, "text": "Remove", "enabled": True, "visible": True}] if self.selected_pack == "A" else [])],
                "textFields": [],
            },
            "selection": {
                "screenClass": "ModpackSelectionScreen",
                "buttons": [{"id": 12, "text": "Review pack switch", "enabled": True, "visible": True},
                            {"id": 13, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "patch_history": {
                "screenClass": "PatchNotesHistoryScreen",
                "buttons": [{"id": 14, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "changelog": {
                "screenClass": "ChangelogScreen",
                "buttons": [{"id": 15, "text": "View all patch notes", "enabled": True, "visible": True},
                            {"id": 16, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "quarantine": {
                "screenClass": "QuarantineArchiveScreen",
                "buttons": [{"id": 44, "text": "Restore", "enabled": not self.quarantine_restored, "visible": True},
                            {"id": 45, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "ingame": {"screenClass": None, "buttons": [{"id": 8, "text": "Multiplayer", "enabled": True, "visible": True}], "textFields": []},
        }
        snapshot = snapshots[self.screen]
        if self.screen == "preparing":
            self.screen = "first_connection"
        return snapshot

    # --- actions ----------------------------------------------------------
    def text(self, element_id: int, value: str, timeout: float = 30) -> dict:
        self.typed[element_id] = value
        if element_id == 1:
            self.fingerprint = value
        return {"ok": True}

    def click(self, element_id: int, timeout: float = 30, **payload) -> dict:
        self.clicks.append(element_id)
        if element_id == 2 and self.fingerprint:
            self.screen = "preparing"
        elif element_id == 3:
            self.screen = "preview"
        elif element_id == 18:
            self.screen = "group0"
        elif element_id == 19 or element_id == 28:
            self.screen = "group0"
        elif element_id == 20 or element_id == 30 or element_id == 36:
            self.screen = "group1" if self.screen == "group0" else "group2"
        elif element_id == 21 or element_id == 31:
            self.screen = "preview"
        elif element_id == 22:
            self.screen = "group1"
        elif element_id == 23:
            self.screen = "group1"
        elif element_id == 24 or element_id == 32:
            self.screen = "group1"
        elif element_id == 33:
            self.dependency = True
            self.screen = "group1"
        elif element_id == 34:
            self.conflict = True
            self.screen = "group1"
        elif element_id == 25 or element_id == 35:
            self.screen = "group1" if self.screen == "group2" else self.screen
        elif element_id == 39:
            self.screen = "group0" if self.screen == "group1" else self.screen
        elif element_id == 5:
            if self.screen == "preview":
                if self.pending_pack is not None:
                    self._capture_editable_overlay(self.selected_pack)
                    self.selected_pack = self.pending_pack
                    self.pending_pack = None
                self._write_modpack()
                self._restore_editable_overlay(self.selected_pack)
                self.screen = "restart"
        elif element_id == 4:
            self.exited = True
        elif element_id == 40:
            self.history_parent = "restart"
            self.screen = "changelog"
        elif element_id == 7:
            self.screen = "settings"
        elif element_id == 8:
            self.screen = "multiplayer"
        elif element_id == 9:
            if self.selected_pack == "A":
                self.screen = "settings"
            else:
                self.pending_pack = "A"
                self.screen = "selection"
        elif element_id == 11:
            self.pending_pack = "B"
            self.screen = "selection"
        elif element_id == 10:
            self.screen = "manager"
        elif element_id == 12:
            self.screen = "preview"
        elif element_id == 13:
            self.screen = "manager"
        elif element_id == 14:
            self.screen = self.history_parent
        elif element_id == 17:
            self.history_parent = "preview"
            self.screen = "patch_history"
        elif element_id == 15:
            self.history_parent = "changelog"
            self.screen = "patch_history"
        elif element_id == 16:
            self.screen = "restart"
        elif element_id == 41:
            self.screen = "removal_preview"
        elif element_id == 42:
            self._remove_active_pack()
            self.screen = "title"
        elif element_id == 43:
            self.screen = "quarantine"
        elif element_id == 44:
            self._restore_quarantine()
            self.screen = "quarantine"
        elif element_id == 45:
            self.screen = "settings"
        elif element_id == 46:
            if self.screen == "manager":
                self.screen = "storage"
            else:
                self.storage_running = True
                self._compact_local_storage()
                self.storage_running = False
        elif element_id == 47:
            self.screen = "manager"
        return {"ok": True}

    def connect(self, host: str, port: int = 25565, timeout: float = 30) -> dict:
        # Already-synced clients drop straight in-game; first contact hits the cert prompt.
        self.screen = "preview" if self.update_available else ("ingame" if self.synced else "first_connection" if self.bootstrap else "cert")
        return {"ok": True}

    def screenshot(self, name: str, timeout: float = 30) -> dict:
        self.render_frame()
        self.rendered_screens[name] = self.rendered_screen
        relative = Path("automodpack") / "autotest" / "screenshots" / f"{name}.png"
        path = self.ctx.game_dir / relative
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"png")
        self.screenshots.append(name)
        return {"ok": True, "path": str(relative), "width": 640, "height": 480}

    def request(self, op: str, timeout: float = 30, **payload) -> dict:
        if op == "disconnect":
            self.screen = "title"
        elif op == "quit":
            self.exited = True
        return {"ok": True}

    # --- helpers ----------------------------------------------------------
    def _pack_id(self, pack: str) -> str:
        return {"A": "packaaa", "B": "packbbb"}[pack]

    def _editable_overlay_path(self, pack: str) -> Path:
        return self.ctx.game_dir / "automodpack" / "client" / "overlays" / self._pack_id(pack) / "config" / "pack-shared-editable.txt"

    def _capture_editable_overlay(self, pack: str) -> None:
        source = self.ctx.path("config/pack-shared-editable.txt")
        if not source.is_file():
            return
        overlay = self._editable_overlay_path(pack)
        overlay.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, overlay)

    def _restore_editable_overlay(self, pack: str) -> None:
        overlay = self._editable_overlay_path(pack)
        if not overlay.is_file():
            return
        target = self.ctx.path("config/pack-shared-editable.txt")
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(overlay, target)

    def _remove_active_pack(self) -> None:
        active = self.ctx.game_dir / "automodpack" / "client" / "active"
        if active.exists():
            shutil.rmtree(active)
        client = self.ctx.game_dir / "automodpack" / "client"
        (client / "active-state.json").unlink(missing_ok=True)
        client_config = self.ctx.game_dir / "automodpack" / "client-config.json"
        try:
            config = json.loads(client_config.read_text(encoding="utf-8")) if client_config.is_file() else {}
        except (OSError, TypeError, ValueError, json.JSONDecodeError):
            config = {}
        config["selectedModpackId"] = ""
        client_config.parent.mkdir(parents=True, exist_ok=True)
        client_config.write_text(json.dumps(config), encoding="utf-8")
        for rel, _content in self.ctx.scenario_files:
            self.ctx.path(rel).unlink(missing_ok=True)
        for rel, content in self.baseline_snapshots.items():
            target = self.ctx.path(rel)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        self._editable_overlay_path("A").unlink(missing_ok=True)

    def _compact_local_storage(self) -> None:
        """Keep the fake bridge focused on the UI; scenario assertions prove preservation."""
        self.storage_complete = True

    def _reset_client_generation(self) -> None:
        self.secondary_pack = False
        self.pending_pack = None
        self.pack_b_files = []

    def _generation_fixture_files(self, index: int) -> list[tuple[Path, bytes]]:
        generations = self.ctx.scenario.get("serverFiles", {}).get("generations", [])
        if index >= len(generations):
            return []
        return [
            (Path(str(item["path"])), valid_mod_jar_bytes(item["fixture"], self.ctx.target.minecraft))
            for item in generations[index].get("files", [])
            if isinstance(item, dict) and item.get("fixture") is not None
        ]

    def _remove_generation_fixture_files(self, index: int, root: Path) -> None:
        for rel, _payload in self._generation_fixture_files(index):
            (root / rel).unlink(missing_ok=True)
            self.ctx.path(rel).unlink(missing_ok=True)

    def _write_modpack(self) -> None:
        root = self.ctx.game_dir / "automodpack" / "client" / "active"
        if root.exists():
            shutil.rmtree(root)
        root.mkdir(parents=True, exist_ok=True)
        marker = root / self.ctx.marker_rel
        marker.parent.mkdir(parents=True, exist_ok=True)
        marker.write_text("{}")
        files = self.ctx.scenario_files
        fixture_files: list[tuple[Path, bytes]] = []
        if self.selected_pack == "B":
            files = self.pack_b_files
        elif self.update_available:
            files = [(Path("config/amp-autotest-alpha.txt"), "amp-autotest-alpha-v2\n"),
                     (Path("config/amp-autotest-beta.json"), '{"id":"beta","value":43}'),
                     (Path("config/amp-autotest-baseline.json"), "server-baseline-v2\n"),
                     (Path("config/amp-autotest-visual.txt"), "visual-v2\n"),
                     (Path("config/amp-autotest-delta.txt"), "delta-v2\n"),
                     (Path("config/pack-a-only.txt"), "pack-a-v2\n")]
            (root / "config/amp-autotest-gamma.cfg").unlink(missing_ok=True)
            self._remove_generation_fixture_files(0, root)
        elif self.ctx.vars.get("client_generation_reset"):
            files = [
                (Path(str(item["path"])), str(item.get("content", "")))
                for item in self.ctx.scenario.get("serverFiles", {}).get("generations", [])[1].get("files", [])
                if isinstance(item, dict) and item.get("fixture") is None
            ]
            (root / "config/amp-autotest-gamma.cfg").unlink(missing_ok=True)
            self._remove_generation_fixture_files(0, root)
            fixture_files = self._generation_fixture_files(1)
        elif self.selected_pack == "A":
            fixture_files = self._generation_fixture_files(0)
        if self.selected_pack == "A":
            self._apply_server_owned_baselines(1 if self.update_available or self.ctx.vars.get("client_generation_reset") else 0)
        for rel, content in files:
            f = root / rel
            f.parent.mkdir(parents=True, exist_ok=True)
            if isinstance(content, bytes):
                f.write_bytes(content)
            else:
                f.write_text(content)
        for rel, payload in fixture_files:
            f = root / rel
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_bytes(payload)
            if not self.ctx.vars.get("client_generation_reset"):
                game_path = self.ctx.path(rel)
                game_path.parent.mkdir(parents=True, exist_ok=True)
                game_path.write_bytes(payload)
        connection_path = self.ctx.game_dir / "automodpack" / "client" / "data" / "packs" / "packaaa" / "connection.json"
        if self.ctx.vars.get("bootstrap_origin"):
            connection = json.loads(connection_path.read_text(encoding="utf-8")) if connection_path.is_file() else {}
            secret = "fake-authenticated-secret"
            connection.setdefault("secrets", {})[self.ctx.vars["bootstrap_origin"]] = {"secret": secret, "timestamp": 1}
            connection_path.parent.mkdir(parents=True, exist_ok=True)
            connection_path.write_text(json.dumps(connection))
            server_secrets = self.ctx.server_dir / "automodpack" / "server" / "secrets.json"
            server_secrets.parent.mkdir(parents=True, exist_ok=True)
            server_secrets.write_text(json.dumps({"secrets": {"fake-player": {"secret": secret, "timestamp": 1}}}))
        self.synced = True
        self.update_available = False
        if self.selected_pack == "B" and self._pack_b_owns_conflict() and not self.quarantine_restored:
            payload = self.ctx.game_dir / "automodpack" / "client" / "quarantine" / "packbbb" / "conflicts" / "fake-conflict" / "payload"
            payload.parent.mkdir(parents=True, exist_ok=True)
            payload.write_bytes(valid_mod_jar_bytes(self.ctx.vars["same_path_conflict_fixture"], self.ctx.target.minecraft))
            source = self.ctx.path(self.ctx.vars["same_path_conflict_path"])
            source.unlink(missing_ok=True)
        self._write_manifest()

    def _apply_server_owned_baselines(self, generation_index: int) -> None:
        generations = self.ctx.scenario.get("serverFiles", {}).get("generations", [])
        if not isinstance(generations, list) or generation_index >= len(generations):
            return
        generation = generations[generation_index]
        for item in generation.get("files", []):
            if not isinstance(item, dict) or item.get("fixture") is not None or "content" not in item:
                continue
            relative = Path(str(item["path"]))
            target = self.ctx.path(relative)
            if relative not in self.baseline_snapshots and target.is_file():
                self.baseline_snapshots[relative] = target.read_bytes()
            if relative in self.baseline_snapshots:
                target.parent.mkdir(parents=True, exist_ok=True)
                target.write_text(str(item["content"]), encoding="utf-8")

    def _conflict_path(self) -> str | None:
        value = self.ctx.vars.get("same_path_conflict_path")
        return str(value) if value else None

    def _pack_b_owns_conflict(self) -> bool:
        conflict_path = self._conflict_path()
        return conflict_path is not None and any(str(rel) == conflict_path for rel, _content in self.pack_b_files)

    def _quarantine_payload_path(self) -> Path:
        return self.ctx.game_dir / "automodpack" / "client" / "quarantine" / "packbbb" / "conflicts" / "fake-conflict" / "payload"

    def _quarantine_available(self) -> bool:
        return self.selected_pack == "B" and self._quarantine_payload_path().is_file()

    def _restore_quarantine(self) -> None:
        payload = self._quarantine_payload_path()
        conflict_path = self._conflict_path()
        if not payload.is_file() or conflict_path is None:
            raise AssertionError("fake quarantine restore requested without an available conflict payload")
        target = self.ctx.path(conflict_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(payload, target)
        shutil.rmtree(payload.parent, ignore_errors=False)
        self.quarantine_restored = True

    def _manager_buttons(self) -> list[dict]:
        if not self.secondary_pack:
            state = "active" if self.selected_pack == "A" else "switch"
            return [{"id": 9, "text": f"Pack A  [{state}]  connected", "enabled": True, "visible": True},
                    {"id": 46, "text": "Local storage", "enabled": True, "visible": True}]
        a_state = "active" if self.selected_pack == "A" else "switch"
        b_state = "active" if self.selected_pack == "B" else "switch"
        return [{"id": 9, "text": f"Pack A  [{a_state}]  connected", "enabled": True, "visible": True},
                {"id": 10, "text": "Pack manager", "enabled": True, "visible": True},
                {"id": 11, "text": f"Pack B  [{b_state}]  local record", "enabled": True, "visible": True},
                {"id": 46, "text": "Local storage", "enabled": True, "visible": True}]

    def _write_manifest(self) -> None:
        groups = self.ctx.scenario.get("topology", {}).get("server", {}).get("automodpack", {}).get("config", {}).get("groups", {})
        generation_id = "a" * 40 if not self.ctx.vars.get("published_server_generation") else "b" * 40
        notes = "Initial release: core content and optional client groups." if generation_id[0] == "a" else "Update 2: changed alpha, added delta, and removed gamma."
        manifest_groups = {}
        for group_id, declaration in groups.items():
            manifest_groups[group_id] = dict(declaration)
            manifest_groups[group_id].setdefault("required", False)
            manifest_groups[group_id].setdefault("defaultSelected", False)
            manifest_groups[group_id].setdefault("tag", "")
            manifest_groups[group_id].setdefault("breaksWith", [])
            manifest_groups[group_id].setdefault("requires", [])
            manifest_groups[group_id].setdefault("compatiblePlatforms", [])
        client = self.ctx.game_dir / "automodpack" / "client"
        record = client / "records" / generation_id
        record.mkdir(parents=True, exist_ok=True)
        (record / "manifest.json").write_text(json.dumps({
            "modpackName": "Pack A", "modpackId": "packaaa", "groups": manifest_groups,
            "generation": {"generationId": generation_id, "patchNotes": notes},
        }))
        (client / "active-state.json").write_text(json.dumps({"modpackId": "packaaa", "generationId": generation_id, "status": "ACTIVE"}))
