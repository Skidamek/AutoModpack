"""Release-gate scenarios ported from legacy YAMLs: pinned connect, packless server, restart deletion, and real 10s countdown."""
from __future__ import annotations

import time

from ..bridge import Selector
from ..harness import TestCase
from . import accept_certificate_and_confirm, scenario, wait_for_client_file, wait_for_tls_fingerprint


@scenario(
    "pinned-direct-connect",
    description="Join via host#amp1=<fp> (no TOFU prompt), then verify plain reconnect still prompts (no trust imported)",
    groups=("release",),
)
def pinned_direct_connect(case: TestCase) -> None:
    marker_rel = "config/amp-pinned-marker.txt"
    marker_val = "pinned-ok\n"

    with case.step("stage and boot server + client"):
        case.configure_server(connection_mode="HOLEPUNCH")
        case.stage_server_file(marker_rel, marker_val)
        client = case.client("default")
        case.server.start()
        client.start(fast_timers=case.fast_timers)
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))
        fp = wait_for_tls_fingerprint(case)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))

    with case.step("connect with #amp1= pin and verify it skips FingerprintVerificationScreen"):
        bridge.connect(f"{case.server.host}:{case.server.game_port}#amp1={fp}")
        bridge.wait_screen("PackConfirmScreen", timeout=60.0)
        accept_certificate_and_confirm(case, bridge, check_layout=True)
        wait_for_client_file(client, marker_rel, expected_text=marker_val, timeout=120.0)

    with case.step("relaunch and verify plain address still requires manual fingerprint verification"):
        client.stop(force=False)
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        bridge.wait_screen("FingerprintVerificationScreen", timeout=60.0)
        accept_certificate_and_confirm(case, bridge, check_layout=True)
        bridge.wait_in_world(timeout=60.0)
        client.stop(force=False)


@scenario(
    "no-modpack-server",
    description="Server with all files excluded publishes nothing and allows vanilla modded join",
    groups=("release",),
)
def no_modpack_server(case: TestCase) -> None:
    with case.step("configure server with empty pack rules"):
        case.configure_server(
            connection_mode="HOLEPUNCH",
            overrides={
                "modpack": {
                    "General": {
                        "main": {
                            "required": True,
                            "default-selected": True,
                            "from-server": ["config/excluded-only.txt"],
                            "exclude": ["config/excluded-only.txt"],
                        }
                    }
                }
            },
        )
        case.stage_server_file("config/excluded-only.txt", "excluded\n")

    client = case.client("default")
    with case.step("boot server and client in parallel"):
        case.server.start()
        client.start(fast_timers=case.fast_timers)
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))
        case.server.wait_for_log(r"Nothing to sync", timeout=30.0)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))

    with case.step("join packless server directly into world"):
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        bridge.wait_in_world(timeout=60.0)
        assert client.read_game_file("config/excluded-only.txt") is None
        client.stop(force=False)


@scenario(
    "restart-deletion",
    description="Publish v2 removing a synced file and mod jar; verify both stay gone after restart",
    groups=("release",),
)
def restart_deletion(case: TestCase) -> None:
    keep_rel = "config/amp-keep.txt"
    doomed_rel = "config/amp-doomed.txt"

    with case.step("stage v1 with keep + doomed files and mod jar"):
        case.configure_server(connection_mode="HOLEPUNCH")
        case.stage_server_file(keep_rel, "keep-v1\n")
        case.stage_server_file(doomed_rel, "doomed-v1\n")
        case.stage_server_mod("amp-doomed-mod.jar", {"modId": "amp_doomed_mod", "version": "1.0.0", "marker": "v1"})

    client = case.client("default")
    with case.step("boot and sync v1"):
        case.server.start()
        client.start(fast_timers=case.fast_timers)
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        accept_certificate_and_confirm(case, bridge, check_layout=True)
        wait_for_client_file(client, keep_rel, expected_text="keep-v1\n", timeout=120.0)
        wait_for_client_file(client, doomed_rel, expected_text="doomed-v1\n", timeout=30.0)

    with case.step("publish v2 removing doomed file and mod jar"):
        case.stage_server_file(keep_rel, "keep-v2\n")
        (case.server.server_dir / doomed_rel).unlink(missing_ok=True)
        (case.server.server_dir / "mods" / "amp-doomed-mod.jar").unlink(missing_ok=True)
        res = case.server.rcon("automodpack generate")
        assert res.returncode == 0, f"RCON generate failed: {res.output}"

    with case.step("relaunch, apply v2, restart again, and verify doomed file stays gone"):
        client.stop(force=False)
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        accept_certificate_and_confirm(case, bridge, check_layout=True)
        wait_for_client_file(client, keep_rel, expected_text="keep-v2\n", timeout=120.0)
        client.stop(force=False)
        client.start(fast_timers=case.fast_timers)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))
        assert client.read_game_file(keep_rel) == "keep-v2\n"
        assert client.read_game_file(doomed_rel) is None, "Removed file reappeared after restart"
        client.stop(force=False)


@scenario(
    "unverified-countdown",
    description="Verify the real 10-second unverified-pack safety countdown locks and then unlocks the ack checkbox",
    groups=("release",),
)
def unverified_countdown(case: TestCase) -> None:
    with case.step("stage server with unverified mod jar"):
        case.configure_server(connection_mode="HOLEPUNCH")
        case.stage_server_mod("amp-countdown-mod.jar", {"modId": "amp_countdown_mod", "version": "1.0.0", "marker": "lock"})

    client = case.client("default")
    with case.step("boot server and client with real timers (fastTimers=false)"):
        case.server.start()
        client.start(fast_timers=False)
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))
        fp = wait_for_tls_fingerprint(case)
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))

    with case.step("verify ack checkbox starts disabled and unlocks after countdown"):
        bridge.connect(f"{case.server.host}:{case.server.game_port}#amp1={fp}")
        bridge.wait_screen("PackConfirmScreen", timeout=60.0)
        t0 = time.monotonic()
        g = bridge.gui()
        ack_widgets = [w for w in (list(g.get("other") or ()) + list(g.get("buttons") or ())) if "automodpack.confirm.ack" in ({w.get("key")} | set(w.get("keys") or ()))]
        assert ack_widgets, "Expected automodpack.confirm.ack checkbox on PackConfirmScreen"
        assert ack_widgets[0].get("enabled") is False, "Ack checkbox must start locked while countdown runs"
        bridge.click(Selector(key="automodpack.confirm.ack", enabled=True), timeout=20.0)
        elapsed = time.monotonic() - t0
        assert elapsed >= 7.0, f"Countdown unlocked too quickly ({elapsed:.1f}s)"
        client.stop(force=False)
