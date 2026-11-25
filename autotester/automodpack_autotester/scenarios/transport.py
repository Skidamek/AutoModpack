"""Decoupled fast transport matrix: verifies MAGIC, HOLEPUNCH, and HTTP in 1 client boot each."""
from __future__ import annotations

from ..harness import TestCase
from . import accept_certificate_and_confirm, scenario, wait_for_client_file


def _run_transport_case(case: TestCase, mode: str) -> None:
    marker_rel = "config/amp-transport-marker.txt"
    marker_val = f"transport-{mode.lower()}-ok\n"

    with case.step("stage server modpack"):
        case.configure_server(connection_mode=mode)
        case.stage_server_file(marker_rel, marker_val)
        case.stage_server_mod("amp-transport-fixture.jar", {"modId": "amp_transport_fx", "version": "1.0.0", "marker": mode})

    client = case.client("default")
    with case.step("boot server and client in parallel"):
        case.server.start()
        client.start(fast_timers=case.fast_timers)
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))

    with case.step(f"connect and sync over {mode}"):
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        accept_certificate_and_confirm(case, bridge, check_layout=True)
        wait_for_client_file(client, marker_rel, expected_text=marker_val, timeout=120.0)

    with case.step("quit cleanly"):
        client.stop(force=False)


@scenario("transport-holepunch", description="Verify end-to-end sync over HOLEPUNCH transport", groups=("transport",))
def transport_holepunch(case: TestCase) -> None:
    _run_transport_case(case, "HOLEPUNCH")


@scenario("transport-magic", description="Verify end-to-end sync over MAGIC (shared game port) transport", groups=("transport",))
def transport_magic(case: TestCase) -> None:
    _run_transport_case(case, "MAGIC")


@scenario("transport-http", description="Verify end-to-end sync over dedicated HTTP/HTTPS port transport", groups=("transport",))
def transport_http(case: TestCase) -> None:
    _run_transport_case(case, "HTTP")
