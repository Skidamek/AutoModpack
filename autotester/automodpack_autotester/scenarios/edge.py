"""Transfer edge-case and external HTTPS hosting scenarios."""
from __future__ import annotations

from ..harness import TestCase
from ..mod_fixtures import valid_mod_jar_bytes
from . import accept_certificate_and_confirm, scenario, wait_for_client_file


@scenario(
    "files-edge",
    description="Sync empty, 1-byte, 4MiB+1 chunk-boundary, duplicate CAS, unicode, and preserve unowned local mod",
    groups=("edge",),
)
def files_edge(case: TestCase) -> None:
    empty_rel = "config/edge/empty.bin"
    one_rel = "config/edge/one-byte.txt"
    dup_a = "config/edge/dup-a.txt"
    dup_b = "config/edge/dup-b.txt"
    unicode_rel = "config/edge/zażółć-日本語.txt"
    chunk_rel = "config/edge/chunk-boundary.bin"
    chunk_payload = ("ABCD1234" * ((4 * 1024 * 1024) // 8)) + "Z"  # 4 MiB + 1 byte

    with case.step("stage edge-case file universe on server"):
        case.configure_server(connection_mode="HTTP")
        case.stage_server_file(empty_rel, "")
        case.stage_server_file(one_rel, "x")
        case.stage_server_file(dup_a, "identical-cas-payload\n")
        case.stage_server_file(dup_b, "identical-cas-payload\n")
        case.stage_server_file(unicode_rel, "unicode-ok\n")
        case.stage_server_file(chunk_rel, chunk_payload)

    client = case.client("default")
    with case.step("seed unowned local mod and boot server + client"):
        unowned_bytes = valid_mod_jar_bytes({"modId": "amp_local_unowned", "version": "9.9.9", "marker": "local"}, minecraft_version=case.target.minecraft)
        client.write_game_file("mods/local-unowned.jar", unowned_bytes)
        case.server.start()
        client.start(fast_timers=case.fast_timers)
        case.server.wait_ready(timeout=float(case.settings["timeouts"]["serverStartSeconds"]))
        bridge = client.wait_ready(timeout=float(case.settings["timeouts"]["clientStartSeconds"]))

    with case.step("sync and verify every edge-case file"):
        bridge.connect(f"{case.server.host}:{case.server.game_port}")
        accept_certificate_and_confirm(case, bridge, check_layout=False)
        wait_for_client_file(client, one_rel, expected_text="x", timeout=120.0)
        assert client.read_game_file(empty_rel) == ""
        assert client.read_game_file(dup_a) == "identical-cas-payload\n"
        assert client.read_game_file(dup_b) == "identical-cas-payload\n"
        assert client.read_game_file(unicode_rel) == "unicode-ok\n"
        got_chunk = client.read_game_file(chunk_rel)
        assert got_chunk is not None and len(got_chunk) == len(chunk_payload) and got_chunk.endswith("Z")
        client.stop(force=False)
