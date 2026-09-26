"""The scriptable fake bridge: a GUI state machine that mimics the real client.

Clicking the update button writes the active projection files into the game dir, so
the filesystem verbs see real files appear exactly as they would in Docker. Tests
attach it to a Context directly (``ctx.bridge = FakeBridge(ctx)``) and drive engine
verbs against it without Docker, HeadlessMC, or a real Minecraft server.
"""
from __future__ import annotations

import hashlib
import json
import shutil
from datetime import datetime, timezone
from pathlib import Path

from automodpack_autotester.engine import Context
from automodpack_autotester.generation_identity import content_token
from automodpack_autotester.mod_fixtures import valid_mod_jar_bytes
from automodpack_autotester.client_steps import cas_object
from automodpack_autotester.staging_steps import append_staged_mirror, canonical_timestamp, policy_bytes, staged_ledger


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
        self.detail_pack: str | None = None
        self.pack_removed = False
        self.settings_parent = "multiplayer"
        self.storage_parent = "manager"
        self.pending_pack: str | None = None
        self.pack_b_files: list[tuple[Path, bytes | str]] = []
        self.update_available = False
        self.bootstrap = False
        self.history_parent = "restart"
        self.groups_parent = "first_connection"  # screen that opened group customization
        self.dependency = False
        self.conflict = False
        self.visuals_selected = True  # default-selected group in the pack manifest
        self.visuals_excluded = False  # explicit category exclusion reached the child
        self.alternative_selected = False
        self.chosen_visuals = False  # Visuals was explicitly picked, not default-selected
        self.acknowledged = False  # unverified-risk ack checkbox state on the confirm screens
        self.storage_running = False
        self.storage_compact_armed = False
        self.timeline_forget_armed = False
        self.baseline_snapshots: dict[Path, bytes] = {}
        self.first_install_archive_existing = False
        self.storage_verified = False
        self.repair_mutations: set[tuple[str, str]] = set()
        self.repair_editable_reset = True
        self.repair_keep_unowned = False
        self.repair_applied = False
        self.error_parent = "details"
        self.repair_expected: dict[str, bytes] = {}
        self.selected_snapshot: int | None = None

    # --- snapshot ---------------------------------------------------------
    def render_frame(self) -> None:
        self.rendered_screen = self.screen

    def gui(self, timeout: float = 30) -> dict:
        snapshots = {
            "title": {"screenClass": "TitleScreen", "title": "Title Screen", "buttons": [{"id": 6, "text": "Singleplayer", "enabled": True, "visible": True, "key": "menu.singleplayer"}, {"id": 8, "text": "Multiplayer", "enabled": True, "visible": True, "key": "menu.multiplayer"}], "textFields": []},
            "cert": {
                "screenClass": "CertScreen",
                "buttons": [{"id": 2, "text": "Verify", "enabled": True, "visible": True, "key": "automodpack.validation.verify"},
                            {"id": 9, "text": "Skip", "enabled": True, "visible": True, "key": "automodpack.skip"}],
                "textFields": [{"id": 1, "text": "", "enabled": True, "visible": True}],
            },
            "skip_verification": {
                "screenClass": "SkipVerificationScreen",
                "buttons": [{"id": 10, "text": "Back", "enabled": True, "visible": True, "key": "automodpack.back"}],
                "textFields": [],
            },
            "preparing": {"screenClass": "PreparingScreen", "buttons": [], "textFields": []},
            "download": {"screenClass": "DownloadScreen", "buttons": [], "textFields": []},
            "first_connection": {
                "screenClass": "PackConfirmScreen",
                "buttons": [{"id": 3, "text": "Download", "enabled": self.acknowledged, "visible": True, "key": "automodpack.firstConnect.download"},
                            {"id": 17, "text": "History", "enabled": True, "visible": True},
                            {"id": 18, "text": "Customize groups", "enabled": True, "visible": True, "key": "automodpack.confirm.customize"},
                            *([{"id": 89, "text": f"Keep {len(self._first_install_local_mods())} existing mod files", "enabled": True, "visible": True, "checked": self.first_install_archive_existing, "key": "automodpack.confirm.keepExistingMods"}] if self._first_install_local_mods() else []),
                            {"id": 26, "text": "Do not download", "enabled": True, "visible": True}],
                "textFields": [],
                "other": [{"id": 90, "text": "I understand the risk and trust this server.", "enabled": True, "visible": True, "key": "automodpack.confirm.ack"}],
            },
            # The strict fresh-install reconciliation: Download approved the cleanup,
            # but the jar-level review still requires its own acknowledgement.
            "strict_confirm": {
                "screenClass": "PackConfirmScreen",
                "buttons": [{"id": 5, "text": "Update", "enabled": self.acknowledged, "visible": True, "key": "automodpack.update.apply"},
                            {"id": 26, "text": "Do not download", "enabled": True, "visible": True}],
                "textFields": [],
                "other": [{"id": 90, "text": "I understand the risk and trust this server.", "enabled": True, "visible": True, "key": "automodpack.confirm.ack"}],
            },
            "groups": {
                "screenClass": "GroupSelectionScreen",
                "buttons": [
                    {"id": 37, "text": "Defaults", "enabled": True, "visible": True},
                    {"id": 31, "text": "Continue", "enabled": True, "visible": True},
                    {"id": 102, "text": "Back", "enabled": True, "visible": True},
                    {"id": 38, "text": "?", "enabled": True, "visible": True},
                ],
                "other": self._group_list_rows(),
                "textFields": [],
            },
            "group_files": {
                "screenClass": "ChangeBrowserScreen",
                "buttons": [{"id": 39, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "preview": {
                "screenClass": "UpdatePreviewScreen",
                "buttons": [{"id": 5, "text": "Update", "enabled": True, "visible": True, "key": "automodpack.update.apply"},
                            {"id": 17, "text": "History", "enabled": True, "visible": True},
                            {"id": 104, "text": "Back", "enabled": True, "visible": True}],
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
                    {"id": 6, "text": "No, back to the game", "enabled": True, "visible": True, "key": "automodpack.restart.cancel"},
                    {"id": 4, "text": "Yes, close the game", "enabled": True, "visible": True, "key": "automodpack.restart.confirm"},
                    {"id": 40, "text": "View changelogs", "enabled": True, "visible": True},
                ],
                "textFields": [],
            },
            "multiplayer": {
                "screenClass": "JoinMultiplayerScreen",
                "title": "Play Multiplayer",
                "buttons": [{"id": 7, "text": "Modpacks", "enabled": True, "visible": True, "key": "automodpack.packManager.button"},
                            {"id": 8, "text": "Multiplayer", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "manager": {
                "screenClass": "InstalledModpacksScreen",
                "buttons": self._manager_buttons(),
                "textFields": [],
            },
            "details": {
                "screenClass": "ModpackSettingsScreen",
                "buttons": self._details_buttons(),
                "textFields": [],
            },
            "offline_repair": {
                "screenClass": "OfflineRepairScreen",
                "buttons": self._repair_buttons(),
                "textFields": [],
            },
            "error": {
                "screenClass": "ErrorScreen",
                "buttons": [{"id": 93, "text": "Copy details", "enabled": True, "visible": True},
                            {"id": 94, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "state_history": {
                "screenClass": "StateHistoryScreen",
                # Snapshot entries are selection-list rows (ListRow); the real bridge emits
                # them always enabled with the row's narration text (the snapshot kind).
                "buttons": [
                    {"id": 88, "text": "Files...", "enabled": self.selected_snapshot is not None, "visible": True},
                    {"id": 86, "text": "Click again to forget older snapshots" if self.timeline_forget_armed else "Forget older", "enabled": self.selected_snapshot is not None, "visible": True},
                    {"id": 87, "text": "Back", "enabled": True, "visible": True},
                ],
                "other": [
                    {"id": 1000 + entry["seq"], "text": entry["text"], "enabled": True, "visible": True, "type": "ListRow"}
                    for entry in self._timeline_rows()
                ],
                "textFields": [],
            },
            "storage": {
                "screenClass": "ClientStorageMaintenanceScreen",
                "buttons": [
                    *([{"id": 101, "text": "Storage verified", "enabled": False, "visible": True}] if self.storage_verified else []),
                    {"id": 91, "text": "Verify storage", "enabled": not self.storage_running, "visible": True},
                    {"id": 46, "text": "Click again to clean" if self.storage_compact_armed else "Clean local storage", "enabled": not self.storage_running, "visible": True, "key": "automodpack.storage.confirmArmed" if self.storage_compact_armed else "automodpack.storage.confirm"},
                    {"id": 47, "text": "Back", "enabled": True, "visible": True},
                ],
                "textFields": [],
            },
            "settings": {
                "screenClass": "GroupSelectionScreen",
                "buttons": [{"id": 10, "text": "Pack manager", "enabled": True, "visible": True, "key": "automodpack.packManager.switch"},
                            {"id": 13, "text": "Save", "enabled": True, "visible": True},
                            {"id": 57, "text": "History", "enabled": True, "visible": True},
                            {"id": 66, "text": "Files", "enabled": True, "visible": True},
                            {"id": 88, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "content_history": {
                "screenClass": "ContentHistoryScreen",
                # Journal entries are scrolling vanilla list rows; a row click opens the entry's detail browser.
                "buttons": [{"id": 58, "text": "Back", "enabled": True, "visible": True}],
                "other": [{"id": 60, "text": "Generation 1", "enabled": True, "visible": True, "type": "ListRow"},
                          {"id": 61, "text": "Cached Pack B fixture.", "enabled": True, "visible": True, "type": "ListRow"},
                          {"id": 62, "text": "Pack B v2 removes the incompatible mod.", "enabled": True, "visible": True, "type": "ListRow"}],
                "textFields": [],
            },
            "entry_detail": {
                "screenClass": "ChangeBrowserScreen",
                "buttons": [{"id": 67, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "changelog": {
                "screenClass": "ChangelogScreen",
                "buttons": [{"id": 15, "text": "History", "enabled": True, "visible": True},
                            {"id": 16, "text": "Back", "enabled": True, "visible": True}],
                "textFields": [],
            },
            "ingame": {"screenClass": None, "buttons": [{"id": 8, "text": "Multiplayer", "enabled": True, "visible": True}], "textFields": []},
        }
        snapshot = snapshots[self.screen]
        if self.screen == "preparing":
            self.screen = "first_connection"
        elif self.screen == "download":
            self.screen = "restart"
        return snapshot

    # --- actions ----------------------------------------------------------
    def _group_list_rows(self) -> list[dict]:
        """Rows of the checkbox group list, mirroring GroupSelectionScreen.listItems().

        Every row is a vanilla checkbox: optional groups and their category headers are
        enabled, locked rows are disabled. State lives in `checked`, never in the text,
        and status words are omitted the same way the real labels drop them when a
        row's state is the checkbox itself.
        """
        visuals_checked = self.visuals_selected and not self.visuals_excluded
        return [
            {"id": 51, "text": "Category: General", "enabled": False, "visible": True, "checked": True, "type": "ListRow"},
            {"id": 27, "text": "Core (5 files, 90 B)", "enabled": False, "visible": True, "checked": True, "type": "ListRow"},
            {"id": 49, "text": "Category: Visuals", "enabled": True, "visible": True, "checked": visuals_checked, "type": "ListRow"},
            {"id": 29, "text": "Visuals (1 file, 15 B)", "enabled": True, "visible": True, "checked": visuals_checked, "type": "ListRow"},
            {"id": 36, "text": "Category: Extras", "enabled": True, "visible": True, "checked": False, "type": "ListRow"},
            {"id": 33, "text": "Addon (1 file, 11 B)", "enabled": True, "visible": True, "checked": False, "type": "ListRow"},
            {"id": 34, "text": "Alternative (1 file, 15 B)", "enabled": True, "visible": True, "checked": self.alternative_selected, "type": "ListRow"},
            {"id": 35, "text": "Category: Platform", "enabled": True, "visible": True, "checked": False, "type": "ListRow"},
            {"id": 38, "text": "Windows-only (1 file, 11 B)", "enabled": False, "visible": True, "checked": False, "type": "ListRow"},
        ]

    def text(self, element_id: int, value: str, timeout: float = 30, **payload) -> dict:
        self.typed[element_id] = value
        if element_id == 1:
            self.fingerprint = value
        return {"ok": True}

    def click(self, element_id: int, timeout: float = 30, **payload) -> dict:
        self.clicks.append(element_id)
        if element_id == 2 and self.fingerprint:
            self.screen = "preparing"
        elif element_id == 9 and self.screen == "cert":
            self.screen = "skip_verification"
        elif element_id == 10 and self.screen == "skip_verification":
            self.screen = "cert"
        elif element_id == 38 and self.screen == "groups":
            self.screen = "group_files"
        elif element_id == 39 and self.screen == "group_files":
            self.screen = "groups"
        elif element_id == 3:
            # Download on the honesty confirm approves the plan and applies it directly.
            # The strict fresh-install reconciliation still opens the later write confirm
            # because its plan was re-derived after the reset.
            if self.screen == "first_connection":
                if self.ctx.vars.get("client_generation_reset"):
                    self.acknowledged = False
                    self.screen = "strict_confirm"
                else:
                    self._confirm_download()
            else:
                self.screen = "preview"
        elif element_id == 5 and self.screen == "strict_confirm":
            self._confirm_download()
        elif element_id == 90 and self.screen in ("first_connection", "strict_confirm"):
            self.acknowledged = not self.acknowledged
        elif element_id == 89:
            self.first_install_archive_existing = not self.first_install_archive_existing
        elif element_id == 18:
            self.groups_parent = "first_connection"
            self.screen = "groups"
        elif element_id == 19 or element_id == 28:
            self.screen = "groups"
        elif element_id == 102:
            self.screen = "first_connection"
        elif element_id == 20 or element_id == 30 or element_id == 36:
            self.screen = "groups"
        elif element_id == 21 or element_id == 31:
            # Continue returns to the confirm screen that opened customization; the
            # manager/settings entry keeps its own preview destination.
            self.screen = "first_connection" if self.groups_parent == "first_connection" else "preview"
        elif element_id == 22 or element_id == 23 or element_id == 24 or element_id == 32:
            self.screen = "groups"
        elif element_id == 33:
            self.dependency = True
            self.visuals_selected = True  # Addon requires Visuals: the resolver pulls it in
            self.visuals_excluded = False
            self.screen = "groups"
        elif element_id == 34:
            # Alternative conflicts with Visuals: the resolver's replacement applies immediately,
            # swapping Visuals out; the groups screen shows the receipt line instead of a dialog.
            self.conflict = True
            self.alternative_selected = True
            self.visuals_selected = False
            self.visuals_excluded = False
            self.screen = "groups"
        elif element_id == 25 or element_id == 35:
            self.screen = "groups"
        elif element_id == 39:
            self.screen = "groups" if self.screen == "groups" else self.screen
        elif element_id == 49 and self.screen == "groups":
            # Category header toggle: all-or-none of its optional groups.
            if self.visuals_excluded or not self.visuals_selected:
                self.visuals_selected = True
                self.visuals_excluded = False
            else:
                self.visuals_selected = False
                self.visuals_excluded = True
        elif (element_id == 29 or element_id == 48) and self.screen == "groups":
            # Direct child-group toggle: Visuals flips between excluded and selected.
            if self.visuals_selected and not self.visuals_excluded:
                self.visuals_selected = False
                self.visuals_excluded = True
            else:
                self.visuals_selected = True
                self.visuals_excluded = False
        elif element_id == 37:
            self.dependency = False
            self.conflict = False
            self.visuals_selected = True
            self.visuals_excluded = False
            self.alternative_selected = False
        elif element_id == 5:
            if self.screen == "preview":
                self._timeline_snapshot("LIVE")
                if self.pending_pack is not None:
                    self._capture_editable_overlay(self.selected_pack)
                    self.selected_pack = self.pending_pack
                    self.pending_pack = None
                self._write_modpack()
                self._restore_editable_overlay(self.selected_pack)
                self.screen = "restart"
        elif element_id == 6:
            self.screen = "multiplayer"
        elif element_id == 4:
            self.exited = True
        elif element_id == 40:
            self.history_parent = "restart"
            self.screen = "changelog"
        elif element_id == 7:
            self.settings_parent = "multiplayer"
            self.screen = "manager"
        elif element_id == 8:
            self.screen = "multiplayer"
        elif element_id == 9 and self.screen == "manager":
            self.detail_pack = "A"
            self.screen = "details"
        elif element_id == 11 and self.screen == "manager":
            self.detail_pack = "B"
            self.screen = "details"
        elif element_id == 10:
            self.screen = "manager"
        elif element_id == 69:
            if self.detail_pack != self.selected_pack:
                self.pending_pack = self.detail_pack
                if self._switch_writes_unverified_jar(self.detail_pack):
                    # A cached record carrying mod jars reviews through the unverified confirm.
                    self.acknowledged = False
                    self.screen = "strict_confirm"
                else:
                    self.screen = "preview"
            else:
                self.screen = "preview"
        elif element_id == 70:
            self.repair_editable_reset = True
            self.repair_keep_unowned = False
            self.repair_applied = False
            self.update_available = self._repair_requires_update()
            self.screen = "offline_repair"
        elif element_id == 95:
            self.repair_editable_reset = not self.repair_editable_reset
        elif element_id == 96:
            self.repair_editable_reset = False
        elif element_id == 97:
            self.repair_keep_unowned = not self.repair_keep_unowned
        elif element_id == 98:
            self._apply_offline_repair()
        elif element_id == 99:
            self._apply_offline_repair()
            self.screen = "preview"
        elif element_id == 100:
            self.screen = "details"
        elif element_id == 72:
            self.settings_parent = "details"
            self.screen = "settings"
        elif element_id == 77:
            self.screen = "removal_preview"
        elif element_id == 78:
            self.screen = "manager"
        elif element_id == 14:
            self.screen = self.history_parent
        elif element_id == 17:
            self.history_parent = self.screen
            self.screen = "content_history"
        elif element_id == 104:
            self.screen = self.settings_parent if self.screen == "preview" and self.settings_parent == "details" else "groups"
        elif element_id == 15:
            self.history_parent = "changelog"
            self.screen = "content_history"
        elif element_id == 57:
            self.history_parent = "details"
            self.screen = "content_history"
        elif element_id == 73:
            self.history_parent = self.screen
            self.screen = "content_history"
        elif element_id == 58:
            self.screen = self.history_parent if self.history_parent else "details"
        elif element_id == 62 and self.screen == "content_history":
            self.screen = "entry_detail"
        elif element_id == 67 and self.screen == "entry_detail":
            self.screen = "content_history"
        elif element_id == 16:
            self.screen = "restart"
        elif element_id == 42:
            self._timeline_snapshot("LIVE")
            self._remove_active_pack()
            self._timeline_snapshot("REMOVAL")
            self.screen = "restart"
        elif element_id == 105 and self.screen == "manager":
            self.selected_snapshot = None
            self.screen = "state_history"
        elif element_id >= 1000 and self.screen == "state_history":
            seq = element_id - 1000
            entry = next((row for row in self._timeline_entries() if row["seq"] == seq), None)
            if entry is None:
                raise AssertionError(f"timeline row for snapshot {seq} is not rendered")
            self.selected_snapshot = seq
            self.timeline_forget_armed = False
        elif element_id == 86 and self.screen == "state_history":
            if self.selected_snapshot is None:
                raise AssertionError("fake timeline forget requested without a selected snapshot")
            if self.timeline_forget_armed:
                self.timeline_forget_armed = False
                self._forget_older(self.selected_snapshot)
            else:
                self.timeline_forget_armed = True
        elif element_id == 87 and self.screen == "state_history":
            self.timeline_forget_armed = False
            self.screen = "manager"
        elif element_id == 46:
            if self.screen == "manager":
                self.storage_parent = "manager"
                self.storage_compact_armed = False
                self.screen = "storage"
            elif self.screen == "storage" and not self.storage_compact_armed:
                self.storage_compact_armed = True
            elif self.screen == "storage":
                self.storage_running = True
                self._compact_local_storage()
                self.storage_running = False
                self.storage_compact_armed = False
        elif element_id == 47:
            self.storage_compact_armed = False
            self.screen = self.storage_parent if self.screen == "storage" else "multiplayer"
        elif element_id == 91:
            self.storage_compact_armed = False
            if self._has_damaged_timeline_object():
                self.error_parent = "storage"
                self.screen = "error"
            else:
                self.storage_verified = True
        elif element_id == 93:
            self.ctx.vars["fake_error_details_copied"] = True
        elif element_id == 94:
            self.screen = self.error_parent
        elif element_id == 88:
            self.screen = self.settings_parent
        return {"ok": True}

    def connect(self, host: str, port: int = 25565, timeout: float = 30) -> dict:
        # Already-synced clients drop straight in-game; first contact hits the cert prompt.
        self.screen = "preview" if self.update_available else ("ingame" if self.synced else "first_connection" if self.bootstrap else "cert")
        if self.screen in ("first_connection", "preview"):
            self.acknowledged = False  # each confirm starts with the risk checkbox unchecked
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

    def _switch_writes_unverified_jar(self, pack: str) -> bool:
        if pack == "B":
            return any(str(name).endswith(".jar") for name, _ in self.pack_b_files)
        return True

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

    def _confirm_download(self) -> None:
        self._timeline_snapshot("LIVE")
        """Download on the first-install confirm applies the pack and restarts."""
        if self.pending_pack is not None:
            self._capture_editable_overlay(self.selected_pack)
            self.selected_pack = self.pending_pack
            self.pending_pack = None
        self._write_modpack()
        self._restore_editable_overlay(self.selected_pack)
        self.screen = "download"

    def _remove_active_pack(self) -> None:
        self.pack_removed = True
        active = self.ctx.game_dir / "automodpack" / "client" / "active"
        if active.exists():
            shutil.rmtree(active)
        client = self.ctx.game_dir / "automodpack" / "client"
        (client / "active-state.json").unlink(missing_ok=True)
        selected = self.ctx.game_dir / "automodpack" / "client" / "selected.json"
        selected.parent.mkdir(parents=True, exist_ok=True)
        selected.write_text(json.dumps({"modpackId": ""}) + "\n", encoding="utf-8")
        for hosted in self.ctx.scenario_files:
            self.ctx.path(hosted.path).unlink(missing_ok=True)
        for rel, content in self.baseline_snapshots.items():
            target = self.ctx.path(rel)
            target.parent.mkdir(parents=True, exist_ok=True)
            target.write_bytes(content)
        self._editable_overlay_path("A").unlink(missing_ok=True)

    def _first_install_local_mods(self) -> list[Path]:
        mods = self.ctx.game_dir / "mods"
        if (self.ctx.game_dir / "automodpack/client/active-state.json").is_file() or not mods.is_dir():
            return []
        return sorted(path for path in mods.iterdir() if path.is_file() and path.name != "automodpack.jar")

    def _repair_unowned_mods(self) -> list[Path]:
        mods = self.ctx.game_dir / "mods"
        if not mods.is_dir():
            return []
        owned_names = {rel.name for rel, _payload in self._generation_fixture_files(1 if self.ctx.vars.get("published_server_generation") else 0)}
        return sorted(path for path in mods.iterdir() if path.is_file() and path.name != "automodpack.jar" and path.name not in owned_names)

    def _repair_buttons(self) -> list[dict]:
        buttons: list[dict] = []
        if not self.repair_applied:
            buttons.append({"id": 97, "text": f"Keep {len(self._repair_unowned_mods())} existing mod files", "enabled": True, "visible": bool(self._repair_unowned_mods()), "checked": self.repair_keep_unowned, "key": "automodpack.confirm.keepExistingMods"})
            buttons.append({"id": 95, "text": "Keep changes in config/pack-shared-editable.txt", "enabled": True, "visible": True, "checked": not self.repair_editable_reset})
            if self.repair_editable_reset:
                buttons.append({"id": 96, "text": "Keep all editable changes", "enabled": True, "visible": True})
        repair_work = not self.repair_applied and (self.repair_editable_reset or self.repair_keep_unowned or bool(self.repair_mutations))
        buttons.append({"id": 98, "text": "Repair", "enabled": repair_work, "visible": True})
        if self.update_available:
            buttons.append({"id": 99, "text": "Update and finish repair", "enabled": True, "visible": True})
        buttons.append({"id": 100, "text": "Back", "enabled": True, "visible": True})
        return buttons

    def _repair_requires_update(self) -> bool:
        active = self.ctx.game_dir / "automodpack" / "client" / "active"
        objects = self.ctx.game_dir / "automodpack" / "client" / "data" / "objects"
        for logical_path, payload in self.repair_expected.items():
            digest = hashlib.sha1(payload).hexdigest()
            candidates = (active / logical_path, cas_object(objects, digest), self.ctx.path(logical_path))
            if not any(candidate.is_file() and candidate.read_bytes() == payload for candidate in candidates):
                return True
        return False

    def _apply_offline_repair(self) -> None:
        self._timeline_snapshot("LIVE")
        active = self.ctx.game_dir / "automodpack" / "client" / "active"
        objects = self.ctx.game_dir / "automodpack" / "client" / "data" / "objects"
        for logical_path, payload in self.repair_expected.items():
            projected = active / logical_path
            digest = hashlib.sha1(payload).hexdigest()
            object_path = cas_object(objects, digest)
            live = self.ctx.path(logical_path)
            if not any(candidate.is_file() and candidate.read_bytes() == payload for candidate in (projected, object_path, live)):
                continue
            projected.parent.mkdir(parents=True, exist_ok=True)
            projected.write_bytes(payload)
            object_path.parent.mkdir(parents=True, exist_ok=True)
            object_path.write_bytes(payload)
            if logical_path != "config/pack-shared-editable.txt":
                live.parent.mkdir(parents=True, exist_ok=True)
                live.write_bytes(payload)
        if self.repair_editable_reset:
            source = active / "config/pack-shared-editable.txt"
            if source.is_file():
                destination = self.ctx.path("config/pack-shared-editable.txt")
                destination.parent.mkdir(parents=True, exist_ok=True)
                shutil.copy2(source, destination)
        if not self.repair_keep_unowned:
            for source in self._repair_unowned_mods():
                source.unlink()
        self.repair_mutations.clear()
        self.repair_applied = True
        self.repair_editable_reset = False
        self.repair_keep_unowned = False
        self._timeline_snapshot("REPAIR")

    def _has_damaged_timeline_object(self) -> bool:
        objects = self.ctx.game_dir / "automodpack" / "client" / "data" / "objects"
        for entry in self._timeline_entries():
            tree_file = self.ctx.game_dir / "automodpack" / "client" / "state-history" / "trees" / entry["treeSha1"]
            if not tree_file.is_file():
                continue
            for file in json.loads(tree_file.read_text(encoding="utf-8")).get("files", []):
                digest = str(file.get("sha1", "")).lower()
                object_path = cas_object(objects, digest)
                if not object_path.is_file() or hashlib.sha1(object_path.read_bytes()).hexdigest() != digest:
                    return True
        return False

    def _compact_local_storage(self) -> None:
        """Keep the fake bridge focused on the UI; scenario assertions prove preservation."""

    def _reset_client_generation(self) -> None:
        self.secondary_pack = False
        self.detail_pack = None
        self.pack_removed = True
        self.pending_pack = None
        self.pack_b_files = []
        self.first_install_archive_existing = False
        self.storage_verified = False
        self.storage_compact_armed = False
        self.groups_parent = "first_connection"
        self.acknowledged = False
        self.dependency = False
        self.conflict = False
        self.visuals_selected = True
        self.visuals_excluded = False
        self.alternative_selected = False
        self.chosen_visuals = False
        self.repair_mutations.clear()
        self.repair_applied = False
        self.repair_expected.clear()

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
        # The checkbox is "Keep existing mod files": checked = keep, unchecked = removal consent.
        if not self.first_install_archive_existing:
            for source in self._first_install_local_mods():
                source.unlink()
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
            files = [(Path("config/amp-autotest-alpha.txt"), "amp-autotest-alpha-v2\n"),
                     (Path("config/amp-autotest-beta.json"), '{"id":"beta","value":43}'),
                     (Path("config/amp-autotest-baseline.json"), "server-baseline-v2\n"),
                     (Path("config/amp-autotest-visual.txt"), "visual-v2\n"),
                     (Path("config/amp-autotest-delta.txt"), "delta-v2\n"),
                     (Path("config/pack-a-only.txt"), "pack-a-v2\n"),
                     (Path("config/pack-shared-editable.txt"), "pack-a-default\n")]
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
        for entry in files:
            rel, content = (entry.path, entry.content) if hasattr(entry, "path") else entry
            f = root / rel
            f.parent.mkdir(parents=True, exist_ok=True)
            if isinstance(content, bytes):
                f.write_bytes(content)
            else:
                f.write_text(content, encoding="utf-8")
            if rel.as_posix() != "config/pack-shared-editable.txt":
                live = self.ctx.path(rel)
                live.parent.mkdir(parents=True, exist_ok=True)
                if isinstance(content, bytes):
                    live.write_bytes(content)
                else:
                    live.write_text(content, encoding="utf-8")
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
            server_secrets = self.ctx.server_dir / "automodpack" / "credentials" / "secrets.json"
            server_secrets.parent.mkdir(parents=True, exist_ok=True)
            secret_key = hashlib.sha256(secret.encode("utf-8")).hexdigest()
            server_secrets.write_text(json.dumps({"secrets": {secret_key: {"playerId": "fake-player", "timestamp": 1, "name": "fake-player"}}}), encoding="utf-8")
        self.synced = True
        if self.selected_pack == "A":
            self.pack_removed = False
        self.update_available = False
        if self.selected_pack == "B" and self._pack_b_owns_conflict():
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

    # --- instance timeline model -----------------------------------------
    _TIMELINE_TITLES = {
        "LIVE": "Your files before a change",
        "INSTALL": "Installed modpack",
        "UPDATE": "Updated modpack",
        "ROLLBACK": "Rolled back",
        "RESTORE": "Restored this instance",
        "REMOVAL": "Removed modpack",
        "DEACTIVATION": "Deactivated modpack",
        "REPAIR": "Repaired",
        "FILE_RESTORE": "Restored a file",
    }

    def _timeline_entries(self) -> list[dict]:
        journal = self.ctx.game_dir / "automodpack" / "client" / "state-history" / "journal.jsonl"
        if not journal.is_file():
            return []
        return [json.loads(line) for line in journal.read_text(encoding="utf-8").splitlines() if line.strip()]

    def _timeline_rows(self) -> list[dict]:
        return [{"seq": entry["seq"], "text": self._TIMELINE_TITLES[entry["kind"]]} for entry in reversed(self._timeline_entries())]

    def _timeline_snapshot(self, kind: str) -> None:
        """Model the client snapshotting the instance: tracked files hash into CAS, one journal line records the tree."""
        client = self.ctx.game_dir / "automodpack" / "client"
        state = client / "state-history"
        state.mkdir(parents=True, exist_ok=True)
        journal = state / "journal.jsonl"
        entries = [json.loads(line) for line in journal.read_text(encoding="utf-8").splitlines() if line.strip()] if journal.is_file() else []
        seq = (entries[-1]["seq"] + 1) if entries else 1
        game = self.ctx.game_dir
        files = []
        for base, root, pack in ((game / "mods", "GAME_DIR", ""), (game / "config", "GAME_DIR", ""), (client / "overlays", "OVERLAY", "per-pack")):
            if not base.is_dir():
                continue
            for path in sorted(base.rglob("*")):
                if not path.is_file():
                    continue
                data = path.read_bytes()
                digest = hashlib.sha1(data).hexdigest()
                object_path = cas_object(game / "automodpack/client/data/objects", digest)
                object_path.parent.mkdir(parents=True, exist_ok=True)
                object_path.write_bytes(data)
                overlay_pack = pack if pack != "per-pack" else path.relative_to(base).parts[0]
                relative = path.relative_to(game).as_posix() if root == "GAME_DIR" else path.relative_to(base / overlay_pack).as_posix()
                files.append({"root": root, "overlayPackId": overlay_pack, "path": relative, "sha1": digest, "size": len(data)})
        tree_sha1 = hashlib.sha1(json.dumps(files, sort_keys=True).encode("utf-8")).hexdigest()
        trees = state / "trees"
        trees.mkdir(parents=True, exist_ok=True)
        (trees / tree_sha1).write_text(json.dumps({"files": files}), encoding="utf-8")
        entry = {"seq": seq, "parentSeq": seq - 1 if entries else 0, "treeSha1": tree_sha1, "kind": kind, "modpackId": "packaaa", "transactionId": f"tx-{seq}", "createdAt": "2026-09-19T00:00:00Z"}
        with journal.open("a", encoding="utf-8") as out:
            out.write(json.dumps(entry) + "\n")

    def _forget_older(self, seq: int) -> None:
        state = self.ctx.game_dir / "automodpack" / "client" / "state-history"
        journal = state / "journal.jsonl"
        entries = [entry for entry in self._timeline_entries() if entry["seq"] >= seq]
        kept_trees = {entry["treeSha1"] for entry in entries}
        journal.write_text("".join(json.dumps(entry) + "\n" for entry in entries), encoding="utf-8")
        trees = state / "trees"
        if trees.is_dir():
            for tree in trees.iterdir():
                if tree.name not in kept_trees:
                    tree.unlink()

    def _manager_buttons(self) -> list[dict]:
        timeline = {"id": 105, "text": "Instance timeline", "enabled": True, "visible": True, "key": "automodpack.management.stateHistory"}
        if self.pack_removed:
            return [{"id": 47, "text": "Back", "enabled": True, "visible": True},
                    {"id": 46, "text": "Local storage", "enabled": True, "visible": True},
                    timeline]
        if not self.secondary_pack:
            rows = [{"id": 9, "text": f"Pack A  [{'active' if self.selected_pack == 'A' else 'switch'}] · server", "enabled": True, "visible": True}]
        else:
            a_state = "active" if self.selected_pack == "A" else "switch"
            b_state = "active" if self.selected_pack == "B" else "switch"
            rows = [{"id": 9, "text": f"Pack A  [{a_state}] · server", "enabled": True, "visible": True},
                    {"id": 11, "text": f"Pack B  [{b_state}] · local", "enabled": True, "visible": True}]
        return rows + [{"id": 47, "text": "Back", "enabled": True, "visible": True},
                       {"id": 46, "text": "Local storage", "enabled": True, "visible": True},
                       timeline]

    def _details_buttons(self) -> list[dict]:
        pack = self.detail_pack or self.selected_pack
        active = pack == self.selected_pack
        return [{"id": 69, "text": "Update" if active else "Activate", "enabled": True, "visible": True},
                *([{"id": 70, "text": "Repair", "enabled": True, "visible": True}] if active else []),
                {"id": 72, "text": "Features", "enabled": True, "visible": True},
                {"id": 73, "text": "History", "enabled": True, "visible": True},
                {"id": 75, "text": "Patch notes", "enabled": True, "visible": True},
                {"id": 74, "text": "Files", "enabled": True, "visible": True},
                *([{"id": 76, "text": "Deactivate", "enabled": True, "visible": True}] if active else []),
                {"id": 77, "text": "Remove", "enabled": True, "visible": True},
                {"id": 78, "text": "Back", "enabled": True, "visible": True}]

    def _write_manifest(self) -> None:
        config = self.ctx.scenario.get("topology", {}).get("server", {}).get("automodpack", {}).get("config", {})
        categories = config.get("modpack", {})
        notes = "Initial release: core content and optional client groups." if not self.ctx.vars.get("published_server_generation") else "Update 2: changed alpha, added delta, and removed gamma."
        manifest_categories = {}
        for category, groups in categories.items():
            manifest_group = manifest_categories.setdefault(str(category), {})
            for group_id, declaration in groups.items():
                group = dict(declaration)
                group.setdefault("required", False)
                group.setdefault("defaultSelected", False)
                group.setdefault("breaksWith", [])
                group.setdefault("requires", [])
                group.setdefault("compatiblePlatforms", [])
                manifest_group[str(group_id)] = group
        active = self.ctx.game_dir / "automodpack" / "client" / "active"
        active_files = {}
        self.repair_expected = {}
        if active.is_dir():
            for path in sorted(path for path in active.rglob("*") if path.is_file()):
                logical_path = path.relative_to(active).as_posix()
                payload = path.read_bytes()
                digest = hashlib.sha1(payload).hexdigest()
                active_files[logical_path] = {"sha1": digest, "size": str(len(payload))}
                self.repair_expected[logical_path] = payload
                object_path = cas_object(self.ctx.game_dir / "automodpack" / "client" / "data" / "objects", digest)
                object_path.parent.mkdir(parents=True, exist_ok=True)
                object_path.write_bytes(payload)
        manifest_categories.setdefault("General", {}).setdefault("main", {"required": True, "defaultSelected": True})["files"] = active_files
        policy = {
            "modpackId": "packaaa",
            "modpackName": "Pack A",
            "automodpackVersion": "",
            "loader": self.ctx.target.loader,
            "loaderVersion": "",
            "mcVersion": self.ctx.target.minecraft,
            "categories": manifest_categories,
        }
        file_map = {path: (file["sha1"], int(file["size"])) for path, file in active_files.items()}
        created_at = canonical_timestamp(datetime.now(timezone.utc))
        token = content_token(file_map)
        policy_sha1 = hashlib.sha1(policy_bytes(policy)).hexdigest()
        ledger = staged_ledger("packaaa", file_map)
        client = self.ctx.game_dir / "automodpack" / "client"
        objects = client / "data" / "objects"
        policy_object = cas_object(objects, policy_sha1)
        policy_object.parent.mkdir(parents=True, exist_ok=True)
        policy_object.write_bytes(policy_bytes(policy))
        append_staged_mirror(client, "packaaa", token, policy_sha1, file_map, notes, created_at)
        (client / "active-state.json").write_text(json.dumps({"modpackId": "packaaa", "contentToken": token, "status": "ACTIVE", "ownershipLedger": ledger}), encoding="utf-8")
