"""Typed Python scenario registry and shared AutoModpack flow helpers."""
from __future__ import annotations

import re
import time
from dataclasses import dataclass, field
from pathlib import Path
from typing import Callable

from ..bridge import BridgeClient, Selector
from ..config import Target
from ..harness import TestCase
from ..provider import Capabilities


@dataclass(frozen=True)
class ScenarioSpec:
    id: str
    fn: Callable[[TestCase], None]
    description: str
    groups: tuple[str, ...] = ()
    requires_windows_ntfs: bool = False
    loaders: tuple[str, ...] = ()
    targets: tuple[str, ...] = ()

    def matches_target(self, target: Target) -> bool:
        if self.loaders and target.loader not in self.loaders:
            return False
        if self.targets and target.id not in self.targets:
            return False
        return True

    def skip_reason(self, caps: Capabilities) -> str | None:
        if self.requires_windows_ntfs and not caps.native_windows_ntfs:
            return f"requires native Windows NTFS (current provider: {caps.name}/{caps.os_family})"
        return None


REGISTRY: dict[str, ScenarioSpec] = {}


def scenario(
    scenario_id: str,
    *,
    description: str,
    groups: tuple[str, ...] = (),
    requires_windows_ntfs: bool = False,
    loaders: tuple[str, ...] = (),
    targets: tuple[str, ...] = (),
):
    def _wrap(fn: Callable[[TestCase], None]) -> Callable[[TestCase], None]:
        REGISTRY[scenario_id] = ScenarioSpec(
            id=scenario_id,
            fn=fn,
            description=description,
            groups=groups,
            requires_windows_ntfs=requires_windows_ntfs,
            loaders=loaders,
            targets=targets,
        )
        return fn

    return _wrap


def resolve_scenarios(selection: str) -> list[ScenarioSpec]:
    """Resolve comma-separated scenario IDs, group names (e.g. 'transport'), or 'all'."""
    _load_all()
    if selection.strip().lower() == "all":
        return list(REGISTRY.values())
    chosen: list[ScenarioSpec] = []
    seen: set[str] = set()
    for token in (t.strip() for t in selection.split(",") if t.strip()):
        if token in REGISTRY:
            if token not in seen:
                chosen.append(REGISTRY[token])
                seen.add(token)
            continue
        group_matches = [spec for spec in REGISTRY.values() if token in spec.groups]
        if group_matches:
            for spec in group_matches:
                if spec.id not in seen:
                    chosen.append(spec)
                    seen.add(spec.id)
            continue
        raise KeyError(f"Unknown scenario or group: {token!r} (available: {', '.join(sorted(REGISTRY))})")
    return chosen


def list_scenarios() -> list[ScenarioSpec]:
    _load_all()
    return list(REGISTRY.values())


def _load_all() -> None:
    from . import compat, edge, faults, gate, groups, offline, release, transport  # noqa: F401


# --- Shared AutoModpack UI / sync flow helpers ---

_FINGERPRINT_RE = re.compile(r"AutoModpack host certificate fingerprint:\s*([0-9a-fA-F]{64})")


def wait_for_tls_fingerprint(case: TestCase, timeout: float = 90.0) -> str:
    match = case.server.wait_for_log(_FINGERPRINT_RE.pattern, timeout=timeout)
    return match.group(1).lower()


def accept_certificate_and_confirm(case: TestCase, bridge: BridgeClient, *, check_layout: bool = True, timeout: float = 60.0) -> None:
    """Walk through FingerprintVerificationScreen, UpdatePreviewScreen, and PackConfirmScreen."""
    fingerprint: str | None = None
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        st = bridge.state()
        name = st.get("screenName") or ""
        if st.get("inWorld"):
            return
        if name == "FingerprintVerificationScreen":
            if check_layout:
                bridge.assert_layout_clean()
            if fingerprint is None:
                fingerprint = wait_for_tls_fingerprint(case, timeout=30.0)
            bridge.type_text(Selector(role="textfield"), fingerprint, timeout=10.0)
            bridge.click(Selector(role="button", key="automodpack.validation.verify", enabled=True), timeout=10.0)
            bridge.wait_until(lambda s: s.get("screenName") != "FingerprintVerificationScreen", timeout=20.0, use_gui=False, desc="leave FingerprintVerificationScreen")
            continue
        if name == "UpdatePreviewScreen":
            if check_layout:
                bridge.assert_layout_clean()
            for label in ("continue", "update", "apply"):
                g = bridge.gui()
                if any(label in str(b.get("text", "")).lower() and b.get("enabled") for b in (g.get("buttons") or ())):
                    bridge.click(Selector(role="button", text=label, enabled=True), timeout=10.0)
                    break
            time.sleep(0.1)
            continue
        if name == "PackConfirmScreen":
            if check_layout:
                bridge.assert_layout_clean()
            g = bridge.gui()
            all_widgets = list(g.get("other") or ()) + list(g.get("buttons") or ())
            for w in all_widgets:
                keys = {w.get("key")} | set(w.get("keys") or ())
                if "automodpack.confirm.ack" in keys and not w.get("checked"):
                    bridge.click(Selector(key="automodpack.confirm.ack", enabled=True), timeout=20.0)
                    break
            for key in ("automodpack.firstConnect.download", "automodpack.update.apply", "automodpack.confirm"):
                g = bridge.gui()
                if any(key in ({b.get("key")} | set(b.get("keys") or ())) and b.get("enabled") for b in (g.get("buttons") or ())):
                    bridge.click(Selector(role="button", key=key, enabled=True), timeout=10.0)
                    return
            for label in ("download", "update", "apply", "continue"):
                g = bridge.gui()
                if any(label in str(b.get("text", "")).lower() and b.get("enabled") for b in (g.get("buttons") or ())):
                    bridge.click(Selector(role="button", text=label, enabled=True), timeout=10.0)
                    return
        time.sleep(0.05)
    raise TimeoutError(f"Did not complete cert/confirm screens within {timeout}s; last screen={bridge.state().get('screenName')!r}")


def wait_for_client_file(client, rel_path: str, *, expected_text: str | None = None, timeout: float = 120.0) -> None:
    """Wait for a synced file inside ``client``'s game dir (works on both local OS and remote Windows VM)."""
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        text = client.read_game_file(rel_path)
        if text is not None:
            if expected_text is None or text == expected_text:
                return
        time.sleep(0.1)
    raise TimeoutError(f"Client file {rel_path!r} did not appear with expected content within {timeout}s")
