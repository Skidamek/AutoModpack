"""Deep feature release gate: runs once on default transport instead of 3x across the transport matrix."""
from __future__ import annotations

from ..harness import TestCase
from . import accept_certificate_and_confirm, scenario, wait_for_client_file


@scenario("gate", description="Release gate: initial sync, live generation update, file deletion, RCON GC", groups=("release",))
def release_gate(case: TestCase) -> None:
    marker_rel = "config/amp-gate-marker.txt"
    removable_rel = "config/amp-gate-removable.txt"

    with case.step("stage generation v1"):
        case.configure_server(connection_mode="HOLEPUNCH")
        case.stage_server_file(marker_rel, "gen-v1\n")
        case.stage_server_file(removable_rel, "to-be-removed\n")
        case.stage_server_mod("amp-gate-fixture.jar", {"modId": "amp_gate_fx", "version": "1.0.0", "marker": "v1"})

    client = case.client("default")
    with case.step("boot server and client in parallel and sync v1"):
        case.server.start()
        client.start(fast_timers=case.fast_timers)
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        accept_certificate_and_confirm(case, bridge, check_layout=True)
        wait_for_client_file(client, marker_rel, expected_text="gen-v1\n", timeout=120.0)
        wait_for_client_file(client, removable_rel, expected_text="to-be-removed\n", timeout=30.0)

    with case.step("publish generation v2 on server"):
        case.stage_server_file(marker_rel, "gen-v2\n")
        (case.server.server_dir / removable_rel).unlink(missing_ok=True)
        res = case.server.rcon("automodpack generate")
        assert res.returncode == 0, f"RCON generate failed: {res.output}"

    with case.step("relaunch client and verify v2 update"):
        client.stop(force=False)
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        accept_certificate_and_confirm(case, bridge, check_layout=True)
        wait_for_client_file(client, marker_rel, expected_text="gen-v2\n", timeout=120.0)

    with case.step("run server object GC via RCON"):
        gc = case.server.rcon("automodpack gc")
        assert gc.returncode == 0, f"RCON gc failed: {gc.output}"

    with case.step("quit cleanly"):
        client.stop(force=False)
