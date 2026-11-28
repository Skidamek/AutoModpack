"""Group selection UI and category/platform/dependency scenario."""
from __future__ import annotations

import time
from pathlib import Path

from ..bridge import BridgeClient, Selector, select_elements
from ..harness import TestCase
from . import scenario, wait_for_tls_fingerprint


def _stage_group_file(server_dir: Path, group_id: str, text: str) -> None:
    path = server_dir / "automodpack" / "host-modpack" / group_id / "config" / f"{group_id}.txt"
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(text, encoding="utf-8")


def _row_texts(gui: dict) -> list[str]:
    return [str(e.get("text") or "") for e in (gui.get("other") or ())]


def _row_checked(gui: dict, title: str) -> bool | None:
    for e in gui.get("other") or ():
        text = str(e.get("text") or "")
        if text.lower().startswith(title.lower()):
            return e.get("checked")
    return None


def _viewport_bounds(gui: dict) -> tuple[int, int]:
    lists = [e for e in (gui.get("other") or ()) if e.get("type") == "GroupSelectionList"]
    if lists:
        el = lists[0]
        top = int(el["y"])
        return top, top + int(el["height"])
    return 74, int(gui["screenHeight"]) - 40


def _scroll_row_into_view(bridge: BridgeClient, needle: str, *, max_steps: int = 12) -> dict:
    for _ in range(max_steps + 1):
        gui = bridge.gui()
        view_top, view_bottom = _viewport_bounds(gui)
        matches = [e for e in (gui.get("other") or ()) if needle.lower() in str(e.get("text") or "").lower()]
        if matches:
            el = matches[0]
            if int(el["y"]) >= view_top and int(el["y"]) + int(el["height"]) <= view_bottom:
                return el
        bridge.call("scroll", x=int(gui["screenWidth"]) // 2, y=int(gui["screenHeight"]) // 2, dx=0.0, dy=-3.0)
        time.sleep(0.1)
    raise AssertionError(f"Row containing {needle!r} never scrolled cleanly into view; gui={bridge.gui()}")


@scenario(
    "groups-ui",
    description="Verify category pick modes, dependency badges, platform dropdown visibility, and optional UI screenshots",
    groups=("ui", "groups"),
)
def groups_ui(case: TestCase) -> None:
    rich_overrides = {
        "modpack": {"name": "Skyward SMP", "auto-exclude-server-side-mods": "METADATA"},
        "categories": {
            "shaders": {"name": "Shaders", "description": "Pick one shader pack or leave disabled", "pick": "AT_MOST_ONE"},
            "lighting": {"name": "Lighting Style", "pick": "EXACTLY_ONE"},
            "extras": {"name": "Visual & QoL Extras", "description": "Optional client-side enhancements", "pick": "ANY"},
        },
        "groups": {
            "main": {"name": "Core", "description": "Core modpack files", "selection": "REQUIRED", "from-server": []},
            "iris": {"name": "Iris Shaders Engine", "selection": "AUTOMATIC"},
            "complementary": {
                "name": "Complementary Reimagined",
                "description": "Balanced lighting and reflections",
                "category": "shaders",
                "selection": "RECOMMENDED",
                "requires": ["iris"],
            },
            "bsl": {
                "name": "BSL Shaders",
                "description": "Warm stylized shaders",
                "category": "shaders",
                "selection": "OPTIONAL",
                "requires": ["iris"],
            },
            "warm": {"name": "Warm Torchlight", "description": "Cozy golden torches", "category": "lighting", "selection": "RECOMMENDED"},
            "crisp": {"name": "Crisp Daylight", "description": "Neutral daylight palette", "category": "lighting", "selection": "OPTIONAL"},
            "minimap": {"name": "Xaero's Minimap", "description": "HUD map and waypoints", "category": "extras", "selection": "RECOMMENDED"},
            "zoom": {"name": "Zoomify", "description": "Smooth spyglass zoom", "category": "extras", "selection": "OPTIONAL"},
            "waypoints": {
                "name": "Shared Waypoints",
                "description": "Sync waypoints with teammates",
                "category": "extras",
                "selection": "OPTIONAL",
                "requires": ["minimap"],
            },
            "audio": {"name": "Ambient Sounds", "description": "Biome soundscape overhaul", "selection": "OPTIONAL", "conflicts": ["zoom"]},
        },
    }

    with case.step("stage multi-category server.toml"):
        case.configure_server(connection_mode="HOLEPUNCH", overrides=rich_overrides)
        for gid in ("main", "iris", "complementary", "bsl", "warm", "crisp", "minimap", "zoom", "waypoints", "audio"):
            _stage_group_file(case.server.server_dir, gid, f"{gid}-content\n")

    render = case.provider.capabilities.rendered_ui
    client = case.client("default")
    with case.step("boot server and client and open GroupSelectionScreen"):
        case.server.start()
        client.start(fast_timers=case.fast_timers, render=render)
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))
        fp = wait_for_tls_fingerprint(case, timeout=60.0)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        bridge.connect(f"{case.server.host}:{case.server.game_port}#amp1={fp}")
        bridge.wait_screen("PackConfirmScreen", timeout=60.0)
        bridge.assert_layout_clean()
        bridge.click(Selector(role="button", key="automodpack.confirm.customize", enabled=True), timeout=15.0)
        bridge.wait_screen("GroupSelectionScreen", timeout=30.0)
        bridge.assert_layout_clean()

    with case.step("verify included-automatically section, hidden OS dropdown, dependency badges, and pairwise conflict"):
        gui = bridge.gui()
        texts = "\n".join(_row_texts(gui))
        assert "Included automatically" in texts, f"REQUIRED/AUTOMATIC groups must surface under their own section: {texts}"
        assert "Core" in texts, "REQUIRED group must be listed under Included automatically"
        assert "Iris Shaders Engine" in texts, "AUTOMATIC group required by the default shader must be listed as included"
        assert "Required by: Complementary Reimagined" in texts, "active AUTOMATIC group must name its dependents"
        assert any(t.startswith("Core") and t.endswith("Required") for t in _row_texts(gui)), "REQUIRED group must say it is required"
        assert "Complementary Reimagined" in texts
        assert not select_elements(gui, Selector(key="automodpack.selection.platformButton")), "OS dropdown must hide when no platforms are declared"
        assert any("None" in t for t in _row_texts(gui)), "AT_MOST_ONE category must expose None radio option"
        if render:
            time.sleep(0.25)
            bridge.screenshot("group_selection_top")

        bridge.click(Selector(role="other", text="None"), timeout=10.0)
        bridge.wait_until(
            lambda g: "Iris Shaders Engine" not in "\n".join(_row_texts(g)),
            timeout=10.0,
            desc="AUTOMATIC group hidden once nothing requires it",
        )
        bridge.click(Selector(role="other", text="Complementary Reimagined"), timeout=10.0)
        bridge.wait_until(
            lambda g: any(t.startswith("Iris Shaders Engine") for t in _row_texts(g)),
            timeout=10.0,
            desc="AUTOMATIC group back once its dependent is selected again",
        )

        _scroll_row_into_view(bridge, "Zoomify")
        bridge.click(Selector(role="other", text="Zoomify"), timeout=10.0)
        bridge.wait_until(lambda g: _row_checked(g, "Zoomify") is True, timeout=10.0, desc="Zoomify selected")

        _scroll_row_into_view(bridge, "Ambient Sounds")
        bridge.click(Selector(role="other", text="Ambient Sounds"), timeout=10.0)
        bridge.wait_until(
            lambda g: _row_checked(g, "Ambient Sounds") is True and _row_checked(g, "Zoomify") is False,
            timeout=10.0,
            desc="declared conflict turns the other selected group off",
        )

        _scroll_row_into_view(bridge, "Shared Waypoints")
        bridge.click(Selector(role="other", text="Shared Waypoints"), timeout=10.0)
        bridge.wait_until(
            lambda g: any("Required by: Shared Waypoints" in t for t in _row_texts(g)),
            timeout=10.0,
            desc="dependency badge after selecting Shared Waypoints",
        )

        _scroll_row_into_view(bridge, "Included automatically")
        _scroll_row_into_view(bridge, "Iris Shaders Engine")
        bridge.assert_layout_clean()
        if render:
            time.sleep(0.25)
            bridge.screenshot("group_selection_bottom")

    with case.step("reload with platform-restricted group and verify OS dropdown + unavailability"):
        bridge.disconnect()
        bridge.wait_screen("TitleScreen", timeout=20.0)
        rich_overrides["groups"]["win_only"] = {
            "name": "Windows Audio Bridge",
            "description": "Windows-only spatial audio integration",
            "category": "extras",
            "selection": "OPTIONAL",
            "compatible-platforms": ["windows"],
        }
        rich_overrides["groups"]["win_core"] = {
            "name": "Windows Runtime",
            "description": "Required only on Windows",
            "selection": "REQUIRED",
            "compatible-platforms": ["windows"],
        }
        case.configure_server(connection_mode="HOLEPUNCH", overrides=rich_overrides)
        _stage_group_file(case.server.server_dir, "win_only", "win-only\n")
        _stage_group_file(case.server.server_dir, "win_core", "win-core\n")
        assert case.server.rcon("automodpack config reload").returncode == 0
        assert case.server.rcon("automodpack generate").returncode == 0
        # Generation runs off the command thread and refuses connections while it runs, so wait for its report.
        case.server.wait_for_log(r"\[Rcon: (PUBLISHED|NO_CHANGES|FAILED)", timeout=120.0)

        bridge.connect(f"{case.server.host}:{case.server.game_port}#amp1={fp}")
        bridge.wait_screen("PackConfirmScreen", timeout=60.0)
        bridge.click(Selector(role="button", key="automodpack.confirm.customize", enabled=True), timeout=15.0)
        bridge.wait_screen("GroupSelectionScreen", timeout=30.0)
        gui_plat = bridge.gui()
        assert select_elements(gui_plat, Selector(text="OS:")), "OS dropdown must appear once compatible-platforms are declared"
        plat_texts = "\n".join(_row_texts(gui_plat))
        if case.provider.capabilities.os_family == "windows":
            assert "Windows Runtime" in plat_texts, "OS-scoped REQUIRED group must be included on its own platform"
        else:
            assert "Windows Runtime" not in plat_texts, "OS-scoped REQUIRED group must stay out of Included automatically on another OS"
        bridge.assert_layout_clean()
        if render:
            time.sleep(0.25)
            bridge.screenshot("group_selection_platforms")

        row = _scroll_row_into_view(bridge, "Windows Audio Bridge")
        if case.provider.capabilities.os_family != "windows":
            assert "Not available on" in str(row.get("text") or ""), f"Expected platform incompatibility badge on row: {row}"
            assert row.get("enabled") is False
        bridge.assert_layout_clean()
        if render:
            time.sleep(0.25)
            bridge.screenshot("group_selection_platforms_row")
        client.stop(force=False)
