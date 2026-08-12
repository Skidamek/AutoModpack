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
        self.details_pack = "A"
        self.pack_a_installed = False
        self.pending_pack: str | None = None
        self.pack_b_files: list[tuple[Path, bytes | str]] = []
        self.update_available = False
        self.bootstrap = False
        self.history_parent = "restart"
        self.dependency = False
        self.conflict = False
        self.preservation_restored = False
        self.preservation_copy_saved = False
        self.vault_claim_selected = False
        self.storage_running = False
        self.vault_claim_available = False
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
							{"id": 29, "text": ("[+] Visuals (required by Addon)" if self.dependency else "Visuals"), "enabled": True, "visible": True},
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
            "feature_conflict": {
                "screenClass": "FeatureConflictScreen",
                "buttons": [{"id": 67, "text": "Keep current", "enabled": True, "visible": True},
                            {"id": 68, "text": "Use Alternative", "enabled": True, "visible": True}],
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
            "details": {
                "screenClass": "ModpackDetailsScreen",
                "buttons": self._details_buttons(),
                "textFields": [],
            },
            "pack_storage": {
                "screenClass": "ModpackStorageScreen",
                "buttons": [*([{"id": 79, "text": "Preserved files", "enabled": True, "visible": True}] if self.vault_claim_available else []),
                            {"id": 81, "text": "Clean local storage", "enabled": True, "visible": True},
                            {"id": 82, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "preservation": {
                "screenClass": "PreservationVaultScreen",
                "buttons": [{"id": 83, "text": "config/amp-autotest-gamma.cfg", "enabled": True, "visible": self.vault_claim_available},
                            {"id": 84, "text": "Restore", "enabled": self.vault_claim_selected, "visible": True},
                            {"id": 85, "text": "Save copy", "enabled": self.vault_claim_selected, "visible": True},
                            {"id": 86, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "storage": {
                "screenClass": "ClientStorageMaintenanceScreen",
                "buttons": [
                    {"id": 46, "text": "Clean local storage", "enabled": not self.storage_running, "visible": True},
                    {"id": 47, "text": "Back", "enabled": True, "visible": True},
                ],
                "textFields": [],
            },
            "settings": {
                "screenClass": "ModpackSelectionScreen",
                "buttons": [{"id": 10, "text": "Pack manager", "enabled": True, "visible": True},
                            {"id": 13, "text": "Save", "enabled": True, "visible": True},
                            {"id": 57, "text": "History", "enabled": True, "visible": True},
                            {"id": 66, "text": "Files", "enabled": True, "visible": True},
                            {"id": 88, "text": "Back", "enabled": True, "visible": True},
                            *([{"id": 43, "text": "Preserved files", "enabled": True, "visible": True}] if self._vault_conflict_available() else [])],
                "textFields": [],
            },
            "patch_history": {
                "screenClass": "PatchNotesHistoryScreen",
                "buttons": [{"id": 14, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "content_history": {
                "screenClass": "ContentHistoryScreen",
                "buttons": [{"id": 60, "text": "Generation 1", "enabled": True, "visible": True},
                            {"id": 61, "text": "Cached Pack B fixture.", "enabled": False, "visible": True},
                            {"id": 62, "text": "Generation 2", "enabled": True, "visible": True},
                            {"id": 63, "text": "Pack B v2 removes the incompatible mod.", "enabled": False, "visible": True},
                            {"id": 58, "text": "Back", "enabled": True, "visible": True},
                            {"id": 59, "text": "View all patch notes", "enabled": True, "visible": True},
                            {"id": 66, "text": "Files", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "content_patch_history": {
                "screenClass": "PatchNotesHistoryScreen",
                "buttons": [{"id": 64, "text": "Cached Pack B fixture.", "enabled": False, "visible": True},
                            {"id": 65, "text": "Pack B v2 removes the incompatible mod.", "enabled": False, "visible": True},
                            {"id": 14, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "changelog": {
                "screenClass": "ChangelogScreen",
                "buttons": [{"id": 15, "text": "View all patch notes", "enabled": True, "visible": True},
                            {"id": 16, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "vault_conflict": {
                "screenClass": "PreservationVaultScreen",
                "buttons": [{"id": 49, "text": "mods/amp-autotest-conflict.jar", "enabled": True, "visible": True},
                            {"id": 44, "text": "Restore", "enabled": self.vault_claim_selected, "visible": True},
                            {"id": 45, "text": "Save copy", "enabled": self.vault_claim_selected, "visible": True},
                            {"id": 48, "text": "Back", "enabled": True, "visible": True}],
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
            self.screen = "feature_conflict"
        elif element_id == 67:
            self.screen = "group2"
        elif element_id == 68:
            self.dependency = False
            self.screen = "group2"
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
            self.details_pack = "A"
            self.screen = "details"
        elif element_id == 11:
            self.details_pack = "B"
            self.screen = "details"
        elif element_id == 10:
            self.screen = "manager"
        elif element_id == 69:
            if self.details_pack != self.selected_pack:
                self.pending_pack = self.details_pack
            self.screen = "preview"
        elif element_id == 14:
            self.screen = self.history_parent
        elif element_id == 17:
            self.history_parent = "preview"
            self.screen = "patch_history"
        elif element_id == 15:
            self.history_parent = "changelog"
            self.screen = "patch_history"
        elif element_id == 57:
            self.screen = "content_history"
        elif element_id == 58:
            self.screen = "settings"
        elif element_id == 59:
            self.history_parent = "content_history"
            self.screen = "content_patch_history"
        elif element_id == 16:
            self.screen = "restart"
        elif element_id == 75:
            self.screen = "pack_storage"
        elif element_id == 77:
            self.screen = "removal_preview"
        elif element_id == 42:
            self._remove_active_pack()
            self.screen = "manager"
        elif element_id == 43:
            self.vault_claim_selected = False
            self.screen = "vault_conflict"
        elif element_id == 49:
            self.vault_claim_selected = True
            self.screen = "vault_conflict"
        elif element_id == 44:
            self._restore_preservation_original()
            self.screen = "vault_conflict"
        elif element_id == 45:
            self._save_preservation_copy()
            self.screen = "vault_conflict"
        elif element_id == 48:
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
        elif element_id == 78:
            self.screen = "manager"
        elif element_id == 79:
            self.vault_claim_selected = False
            self.screen = "preservation"
        elif element_id == 81:
            self.screen = "storage"
        elif element_id == 82:
            self.screen = "details"
        elif element_id == 83:
            self.vault_claim_selected = True
            self.screen = "preservation"
        elif element_id == 84:
            self._restore_pack_a_preservation_original()
            self.screen = "preservation"
        elif element_id == 85:
            self._save_preservation_copy()
            self.screen = "preservation"
        elif element_id == 86:
            self.screen = "pack_storage"
        elif element_id == 87:
            self.screen = "settings"
        elif element_id == 88:
            self.screen = "multiplayer"
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
        self.pack_a_installed = False

    def _compact_local_storage(self) -> None:
        """Keep the fake bridge focused on the UI; scenario assertions prove preservation."""

    def _reset_client_generation(self) -> None:
        self.secondary_pack = False
        self.pending_pack = None
        self.pack_b_files = []
        self.pack_a_installed = False
        self.vault_claim_available = False
        self.preservation_restored = False
        self.preservation_copy_saved = False
        self.vault_claim_selected = False

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
        marker.write_text("{}", encoding="utf-8")
        files = self.ctx.scenario_files
        fixture_files: list[tuple[Path, bytes]] = []
        if self.selected_pack == "B":
            files = self.pack_b_files
        elif self.update_available:
            self.vault_claim_available = True
            self._vault_claim("packaaa", "config/amp-autotest-gamma.cfg", b"amp-autotest-gamma-v1\n", "SERVER_REMOVAL")
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
                f.write_text(content, encoding="utf-8")
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
            connection_path.write_text(json.dumps(connection), encoding="utf-8")
            server_secrets = self.ctx.server_dir / "automodpack" / "server" / "secrets.json"
            server_secrets.parent.mkdir(parents=True, exist_ok=True)
            server_secrets.write_text(json.dumps({"secrets": {"fake-player": {"secret": secret, "timestamp": 1}}}), encoding="utf-8")
        self.synced = True
        if self.selected_pack == "A":
            self.pack_a_installed = True
        self.update_available = False
        if self.selected_pack == "B" and self._pack_b_owns_conflict() and not self.preservation_restored:
            payload = valid_mod_jar_bytes(self.ctx.vars["same_path_conflict_fixture"], self.ctx.target.minecraft)
            self._vault_claim("packbbb", self.ctx.vars["same_path_conflict_path"], payload, "LOCAL_CONFLICT")
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

    def _vault_claim(self, pack_id: str, original_path: str, payload: bytes, reason: str) -> Path:
        import hashlib
        digest = hashlib.sha1(payload).hexdigest()
        objects = self.ctx.game_dir / "automodpack" / "client" / "data" / "objects"
        objects.mkdir(parents=True, exist_ok=True)
        (objects / digest).write_bytes(payload)
        root = self.ctx.game_dir / "automodpack" / "client" / "preservation" / pack_id
        root.mkdir(parents=True, exist_ok=True)
        manifest = root / "claims.json"
        claims = json.loads(manifest.read_text(encoding="utf-8")).get("claims", []) if manifest.is_file() else []
        generation_id = "a" * 40
        identity = (f"automodpack-preservation-v1\nmodpack={pack_id}\ngeneration={generation_id}\nreason={reason}\n"
                    f"root=GAME_DIR\npath={original_path}\nhash={digest}\nsize={len(payload)}\n")
        claim_id = hashlib.sha1(identity.encode()).hexdigest()
        claims = [claim for claim in claims if claim.get("claimId") != claim_id]
        claims.append({"claimId": claim_id, "originalPath": original_path, "sourceRoot": "GAME_DIR", "objectHash": digest, "size": len(payload), "modpackId": pack_id, "generationId": generation_id, "reason": reason, "preservedAt": "2026-01-01T00:00:00Z", "status": "AVAILABLE"})
        manifest.write_text(json.dumps({"schemaVersion": 1, "modpackId": pack_id, "claims": sorted(claims, key=lambda claim: claim["claimId"])}), encoding="utf-8")
        return objects / digest

    def _vault_conflict_available(self) -> bool:
        return self.selected_pack == "B" and self._conflict_claim_path().is_file()

    def _conflict_claim_path(self) -> Path:
        return self.ctx.game_dir / "automodpack" / "client" / "preservation" / "packbbb" / "claims.json"

    def _restore_preservation_original(self) -> None:
        conflict_path = self._conflict_path()
        if conflict_path is None or not self._conflict_claim_path().is_file():
            raise AssertionError("fake preservation restore requested without an available claim")
        claim = json.loads(self._conflict_claim_path().read_text(encoding="utf-8"))["claims"][0]
        payload = self.ctx.game_dir / "automodpack" / "client" / "data" / "objects" / claim["objectHash"]
        target = self.ctx.path(conflict_path)
        target.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(payload, target)
        self.preservation_restored = True

    def _restore_pack_a_preservation_original(self) -> None:
        manifest = self.ctx.game_dir / "automodpack" / "client" / "preservation" / "packaaa" / "claims.json"
        if not manifest.is_file():
            raise AssertionError("fake preservation restore requested without an available Pack A claim")
        claim = json.loads(manifest.read_text(encoding="utf-8"))["claims"][0]
        source = self.ctx.game_dir / "automodpack" / "client" / "data" / "objects" / claim["objectHash"]
        destination = self.ctx.path(claim["originalPath"])
        destination.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, destination)
        self.preservation_restored = True

    def _save_preservation_copy(self) -> None:
        for pack_id in ("packaaa", "packbbb"):
            manifest = self.ctx.game_dir / "automodpack" / "client" / "preservation" / pack_id / "claims.json"
            if not manifest.is_file():
                continue
            claims = json.loads(manifest.read_text(encoding="utf-8")).get("claims", [])
            for claim in claims:
                root = self.ctx.game_dir / "automodpack" / "client" / "restored" / pack_id / claim["generationId"] / claim["claimId"]
                destination = root / claim["originalPath"]
                destination.parent.mkdir(parents=True, exist_ok=True)
                source = self.ctx.game_dir / "automodpack" / "client" / "data" / "objects" / claim["objectHash"]
                shutil.copy2(source, destination)
                claim["status"] = "SAVED_COPY"
            manifest.write_text(json.dumps({"schemaVersion": 1, "modpackId": pack_id, "claims": claims}), encoding="utf-8")
        self.preservation_copy_saved = True

    def _manager_buttons(self) -> list[dict]:
        buttons = []
        if self.pack_a_installed:
            state = "active" if self.selected_pack == "A" else "switch"
            buttons.append({"id": 9, "text": f"Pack A  [{state}]  connected", "enabled": True, "visible": True})
        if self.secondary_pack:
            state = "active" if self.selected_pack == "B" else "switch"
            buttons.append({"id": 11, "text": f"Pack B  [{state}]  local record", "enabled": True, "visible": True})
        buttons.extend([{"id": 46, "text": "Local storage", "enabled": True, "visible": True},
                        {"id": 87, "text": "Back", "enabled": True, "visible": True}])
        return buttons

    def _details_buttons(self) -> list[dict]:
        active = self.details_pack == self.selected_pack
        return [{"id": 69, "text": "Update" if active else "Activate", "enabled": True, "visible": True},
                {"id": 72, "text": "Features", "enabled": True, "visible": True},
                {"id": 73, "text": "History", "enabled": True, "visible": True},
                {"id": 74, "text": "Files", "enabled": True, "visible": True},
                {"id": 75, "text": "Storage", "enabled": True, "visible": True},
                *([{"id": 76, "text": "Deactivate", "enabled": True, "visible": True}] if active else []),
                {"id": 77, "text": "Remove", "enabled": True, "visible": True},
                {"id": 78, "text": "Back", "enabled": True, "visible": True}]

    def _write_manifest(self) -> None:
        groups = self.ctx.scenario.get("topology", {}).get("server", {}).get("automodpack", {}).get("config", {}).get("groups", {})
        generation_id = "a" * 40 if not self.ctx.vars.get("published_server_generation") else "b" * 40
        notes = "Initial release: core content and optional client groups." if generation_id[0] == "a" else "Update 2: changed alpha, added delta, and removed gamma."
        manifest_groups = {}
        for group_id, declaration in groups.items():
            manifest_groups[group_id] = dict(declaration)
            manifest_groups[group_id].setdefault("required", False)
            manifest_groups[group_id].setdefault("defaultSelected", False)
            manifest_groups[group_id].setdefault("category", "")
            manifest_groups[group_id].setdefault("icon", "")
            manifest_groups[group_id].setdefault("breaksWith", [])
            manifest_groups[group_id].setdefault("requires", [])
            manifest_groups[group_id].setdefault("compatiblePlatforms", [])
        client = self.ctx.game_dir / "automodpack" / "client"
        record = client / "records" / generation_id
        record.mkdir(parents=True, exist_ok=True)
        (record / "manifest.json").write_text(json.dumps({
            "modpackName": "Pack A", "modpackId": "packaaa", "groups": manifest_groups,
            "generation": {"generationId": generation_id, "patchNotes": notes},
        }), encoding="utf-8")
        (client / "active-state.json").write_text(json.dumps({"modpackId": "packaaa", "generationId": generation_id, "status": "ACTIVE"}), encoding="utf-8")
