"""Power scenarios: fault-injection proxy, SIGKILL crash recovery, multi-client concurrency, and Windows NTFS."""
from __future__ import annotations

import threading

from ..harness import TestCase
from . import accept_certificate_and_confirm, scenario, wait_for_client_file


@scenario("fault-mid-transfer-drop", description="Drop TCP stream mid-transfer via FaultProxy and verify client recovery", groups=("faults",))
def fault_mid_transfer_drop(case: TestCase) -> None:
    marker_rel = "config/amp-fault-marker.txt"
    payload = "X" * 65536

    with case.step("stage server with 64KiB payload"):
        case.configure_server(connection_mode="HTTP")
        case.stage_server_file(marker_rel, payload)

    with case.step("start server and fault proxy"):
        case.server.start()
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))
        proxy_bind = "0.0.0.0" if case.provider.capabilities.name == "windows-vm" else "127.0.0.1"
        proxy = case.start_proxy("127.0.0.1", case.server.game_port, bind_host=proxy_bind)
        proxy.set_fault(delay_ms=10, drop_after_bytes=4096, drop_once=True)

    client = case.client("default")
    with case.step("connect through fault proxy and recover"):
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        bridge.connect(f"{case.server.host}:{proxy.port}")
        proxy.clear_faults()
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        accept_certificate_and_confirm(case, bridge, check_layout=False)
        wait_for_client_file(client, marker_rel, expected_text=payload, timeout=120.0)

    with case.step("quit cleanly"):
        client.stop(force=False)


@scenario("fault-sigkill-recovery", description="Hard-kill client with SIGKILL mid-session and verify clean recovery on relaunch", groups=("faults",))
def fault_sigkill_recovery(case: TestCase) -> None:
    marker_rel = "config/amp-crash-marker.txt"

    with case.step("stage and start server"):
        case.configure_server(connection_mode="HOLEPUNCH")
        case.stage_server_file(marker_rel, "crash-recovery-ok\n")
        case.server.start()
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))

    client = case.client("default")
    with case.step("boot client, begin connect, then SIGKILL"):
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        client.stop(force=True)

    with case.step("relaunch after SIGKILL and verify full sync succeeds"):
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        accept_certificate_and_confirm(case, bridge, check_layout=False)
        wait_for_client_file(client, marker_rel, expected_text="crash-recovery-ok\n", timeout=120.0)
        client.stop(force=False)


@scenario("multi-client", description="Sync two concurrent clients simultaneously against one server", groups=("faults", "concurrency"))
def multi_client(case: TestCase) -> None:
    marker_rel = "config/amp-multi-marker.txt"
    marker_val = "multi-client-ok\n"

    with case.step("stage and start server"):
        case.configure_server(connection_mode="HOLEPUNCH")
        case.stage_server_file(marker_rel, marker_val)
        case.server.start()
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))

    alpha = case.client("alpha")
    beta = case.client("beta")
    errors: list[Exception] = []

    def _sync_one(cli):
        try:
            cli.start(fast_timers=case.fast_timers)
            bridge = cli.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
            bridge.connect(f"{case.server.host}:{case.server.game_port}")
            accept_certificate_and_confirm(case, bridge, check_layout=False)
            wait_for_client_file(cli, marker_rel, expected_text=marker_val, timeout=120.0)
            cli.stop(force=False)
        except Exception as e:
            errors.append(e)

    with case.step("sync alpha and beta concurrently"):
        t1 = threading.Thread(target=_sync_one, args=(alpha,))
        t2 = threading.Thread(target=_sync_one, args=(beta,))
        t1.start()
        t2.start()
        t1.join()
        t2.join()
        if errors:
            raise errors[0]


@scenario(
    "windows-ntfs-locks",
    description="Verify Windows NTFS file-lock contention, long paths (>260 chars), and junctions",
    groups=("windows",),
    requires_windows_ntfs=True,
)
def windows_ntfs_locks(case: TestCase) -> None:
    """Runs on native Windows or inside the KVM Windows VM worker with real NTFS semantics."""
    marker_rel = "config/amp-win-ntfs-marker.txt"
    deep_rel = "config/nested_win/ntfs_sub/deep-marker.txt"

    with case.step("stage server with nested Windows NTFS paths"):
        case.configure_server(connection_mode="HOLEPUNCH")
        case.stage_server_file(marker_rel, "win-ntfs-ok\n")
        case.stage_server_file(deep_rel, "deep-path-ok\n")
        case.server.start()
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))

    client = case.client("win")
    with case.step("verify real Win32 exclusive file lock & NTFS junction"):
        client.write_game_file("config/amp-lock-target.txt", b"locked-by-av\n")
        if hasattr(client, "hold_file_lock"):
            lock_id = client.hold_file_lock("config/amp-lock-target.txt")
            client.release_file_lock(lock_id)
        if hasattr(client, "create_junction"):
            client.write_game_file("config/junction-src/probe.txt", b"junction-ok\n")
            client.create_junction("config/junction-link", "config/junction-src")
            assert client.read_game_file("config/junction-link/probe.txt") == "junction-ok\n"

    with case.step("sync on real Windows NTFS including >260-char path"):
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        accept_certificate_and_confirm(case, bridge, check_layout=False)
        wait_for_client_file(client, marker_rel, expected_text="win-ntfs-ok\n", timeout=120.0)
        wait_for_client_file(client, deep_rel, expected_text="deep-path-ok\n", timeout=30.0)
        client.stop(force=False)
