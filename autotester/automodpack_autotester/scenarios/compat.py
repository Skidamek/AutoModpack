"""Fast loader-compatibility scenarios ported from legacy offline YAMLs."""
from __future__ import annotations

from ..harness import TestCase
from ..mod_fixtures import valid_mod_jar_bytes
from . import accept_certificate_and_confirm, scenario, wait_for_client_file


@scenario(
    "canary-preload-classload",
    description="Verify Mixin config selection runs on the main thread (never a background worker)",
    groups=("compat", "offline"),
)
def canary_preload_classload(case: TestCase) -> None:
    client = case.client("default")
    with case.step("boot client offline"):
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        assert bridge.state().get("ready") is True

    with case.step("assert main-thread Mixin config selection in latest.log"):
        log = client.game_log()
        assert "AutoModpack target:" in log, "Missing AutoModpack target banner in latest.log"
        assert "Mixin config selection ran on" not in log, "Mixin config selection leaked onto a background worker thread!"
        client.stop(force=False)


@scenario(
    "dependency-driven-copy",
    description="Fabric: pack mod nests a Jar-in-Jar lib; user mod added later hard-depends on it and triggers bundle generation",
    groups=("compat",),
    loaders=("fabric",),
    targets=("1.21.11-fabric", "1.20.1-fabric"),
)
def dependency_driven_copy(case: TestCase) -> None:
    marker_rel = "config/amp-depcopy-marker.txt"

    with case.step("stage pack mod with nested Jar-in-Jar library"):
        case.configure_server(connection_mode="HOLEPUNCH")
        case.stage_server_file(marker_rel, "v1\n")
        case.stage_server_mod(
            "nesteddep-pack-1.0.0.jar",
            {
                "modId": "nesteddep_pack",
                "version": "1.0.0",
                "marker": "pack",
                "nested": [{"modId": "nesteddep_lib", "version": "1.0.0", "marker": "lib"}],
            },
        )

    client = case.client("default")
    with case.step("install generation v1"):
        case.server.start()
        client.start(fast_timers=case.fast_timers)
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        accept_certificate_and_confirm(case, bridge, check_layout=False)
        wait_for_client_file(client, marker_rel, expected_text="v1\n", timeout=120.0)
        client.stop(force=False)

    with case.step("add user mod while client is live, publish v2, and sync"):
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        user_mod = valid_mod_jar_bytes(
            {"modId": "nesteddep_user", "version": "1.0.0", "marker": "user", "depends": ["nesteddep_lib"]},
            minecraft_version=case.target.minecraft,
        )
        client.write_game_file("mods/nesteddep-user-1.0.0.jar", user_mod)
        case.stage_server_file(marker_rel, "v2\n")
        res = case.server.rcon("automodpack generate")
        assert res.returncode == 0, f"RCON generate failed: {res.output}"
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        accept_certificate_and_confirm(case, bridge, check_layout=False)
        wait_for_client_file(client, marker_rel, expected_text="v2\n", timeout=120.0)
        client.stop(force=False)

    with case.step("relaunch and verify Fabric resolves nesteddep_user via generated bundle"):
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        assert bridge.state().get("screenName") == "TitleScreen"
        client.stop(force=False)


@scenario(
    "neoforge-preload-mods-no-restart",
    description="NeoForge 21.10+/26.x: deleting a ledger-owned mod in standard mods/ during preload boots in one JVM without locking or loading it",
    groups=("compat",),
    loaders=("neoforge",),
    targets=("26.3-neoforge", "26.2-neoforge", "26.1-neoforge", "1.21.11-neoforge", "1.21.10-neoforge"),
)
def neoforge_preload_mods_no_restart(case: TestCase) -> None:
    marker_rel = "config/amp-nf-preload-marker.txt"
    doomed_spec = {"modId": "amp_ledger_doomed", "version": "1.0.0", "marker": "doomed"}

    with case.step("stage v1 with mod jar so it enters the deletion ledger"):
        case.configure_server(connection_mode="HOLEPUNCH")
        case.stage_server_file(marker_rel, "v1\n")
        doomed_server_path = case.stage_server_mod("amp-ledger-doomed.jar", doomed_spec)
        doomed_bytes = doomed_server_path.read_bytes()

    client = case.client("default")
    with case.step("install generation v1"):
        case.server.start()
        client.start(fast_timers=case.fast_timers)
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        accept_certificate_and_confirm(case, bridge, check_layout=False)
        wait_for_client_file(client, marker_rel, expected_text="v1\n", timeout=120.0)
        client.stop(force=False)

    with case.step("publish v2 removing the mod (ledger entry) and place that jar in client standard mods/"):
        doomed_server_path.unlink(missing_ok=True)
        case.stage_server_file(marker_rel, "v2\n")
        res = case.server.rcon("automodpack generate")
        assert res.returncode == 0, f"RCON generate failed: {res.output}"
        client.write_game_file("mods/amp-ledger-doomed.jar", doomed_bytes)

    with case.step("boot client once and verify ledger mod is deleted in preload without lock, load, or restart"):
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        assert client.read_game_file("mods/amp-ledger-doomed.jar") is None, "Ledger-owned jar in mods/ was not deleted during preload"
        assert client.read_game_file(marker_rel) == "v2\n", "v2 marker was not applied during preload"
        log = client.game_log()
        assert "Launch apply needs no restart at preload" in log, "Expected preload launch apply to skip restart"
        assert "Restart required because:" not in log, "Unexpected restart demand during NeoForge preload"
        assert "Launched detached update helper" not in log, "Standard mods/ jar was locked during preload and deferred to helper"
        assert "amp_ledger_doomed" not in log, "Deleted ledger mod was loaded or discovered by NeoForge"

    with case.step("join server directly in the same JVM"):
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        bridge.wait_in_world(timeout=60.0)
        client.stop(force=False)
