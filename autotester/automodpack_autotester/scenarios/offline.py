"""Fast client-only offline scenarios (zero server boot)."""
from __future__ import annotations

from ..harness import TestCase
from ..mod_fixtures import valid_mod_jar_bytes
from . import scenario


@scenario("offline-smoke", description="Fastest smoke test: boot client with AutoModpack, verify bridge & TitleScreen", groups=("offline", "smoke"))
def offline_smoke(case: TestCase) -> None:
    client = case.client("default")
    with case.step("boot client offline"):
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))

    with case.step("verify TitleScreen and clean layout"):
        st = bridge.state()
        assert st.get("ready") is True
        assert st.get("screenName") == "TitleScreen", f"Expected TitleScreen, got {st.get('screenName')!r}"
        bridge.assert_layout_clean()

    with case.step("quit cleanly"):
        client.stop(force=False)


@scenario("offline-mod-fixture", description="Boot client with a cross-loader mod fixture and verify clean load", groups=("offline",))
def offline_mod_fixture(case: TestCase) -> None:
    client = case.client("default")
    with case.step("stage local mod fixture"):
        client.write_game_file(
            "mods/amp-offline-fixture.jar",
            valid_mod_jar_bytes({"modId": "amp_offline_fx", "version": "1.0.0", "marker": "offline"}, minecraft_version=case.target.minecraft),
        )

    with case.step("boot client and verify bridge ready"):
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        assert bridge.state().get("screenName") == "TitleScreen"

    with case.step("quit cleanly"):
        client.stop(force=False)
