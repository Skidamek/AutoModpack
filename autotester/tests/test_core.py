"""Critical unit tests for autotester v2 core runtime (bridge, fault proxy, cache, process, static HTTPS)."""
from __future__ import annotations

import json
import os
import socket
import ssl
import threading
import urllib.request
from pathlib import Path

from automodpack_autotester.bridge import BridgeClient, Selector, select_elements
from automodpack_autotester.cache import deduplicate_tree, materialize_tree, prune_passed_case
from automodpack_autotester.harness import _deep_merge, _dump_toml
from automodpack_autotester.hmc import hmc_asset_name, write_hmc_config
from automodpack_autotester.mod_fixtures import assert_valid_mod_fixture, valid_mod_jar_bytes
from automodpack_autotester.process import LogTailer, PortReservation
from automodpack_autotester.provider import linux_render_wrapper
from automodpack_autotester.proxy import FaultProxy
from automodpack_autotester.server_seed import _download
from automodpack_autotester.static_server import StaticHttpsHost


def test_bridge_tcp_jsonrpc_roundtrip_and_id_correlation():
    received: list[dict] = []
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.bind(("127.0.0.1", 0))
    srv.listen(4)
    port = srv.getsockname()[1]

    def _serve():
        conn, _ = srv.accept()
        with conn, conn.makefile("r", encoding="utf-8") as r, conn.makefile("w", encoding="utf-8") as w:
            for line in r:
                req = json.loads(line)
                received.append(req)
                # Send a stray response with wrong reqId first to verify correlation skips it.
                w.write(json.dumps({"reqId": 9999, "ok": True, "stray": True}) + "\n")
                if req["op"] == "ping":
                    w.write(json.dumps({"reqId": req["reqId"], "ok": True}) + "\n")
                elif req["op"] == "state":
                    w.write(json.dumps({"reqId": req["reqId"], "ok": True, "inWorld": True, "screenName": "TitleScreen"}) + "\n")
                w.flush()
        srv.close()

    t = threading.Thread(target=_serve, daemon=True)
    t.start()

    client = BridgeClient("127.0.0.1", port, token="secret", timeout=5.0)
    try:
        assert client.ping() is True
        st = client.state()
        assert st["inWorld"] is True
        assert st["screenName"] == "TitleScreen"
        assert [r["reqId"] for r in received] == [1, 2]
    finally:
        client.close()
        t.join(timeout=3)


def test_selector_matching_and_layout_check():
    gui = {
        "screenName": "PackConfirmScreen",
        "screenWidth": 400,
        "screenHeight": 300,
        "buttons": [
            {"id": 0, "text": "Cancel", "key": "automodpack.cancel", "x": 10, "y": 250, "width": 100, "height": 20, "enabled": True, "visible": True},
            {"id": 1, "text": "Download", "key": "automodpack.confirm", "x": 120, "y": 250, "width": 100, "height": 20, "enabled": True, "visible": True},
        ],
        "textFields": [],
        "other": [],
    }
    assert select_elements(gui, Selector(role="button", key="automodpack.confirm"))[0]["id"] == 1
    assert select_elements(gui, Selector(text="cancel"))[0]["id"] == 0


def test_fault_proxy_forwarding_and_drop_after_bytes():
    upstream = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    upstream.bind(("127.0.0.1", 0))
    upstream.listen(4)
    uport = upstream.getsockname()[1]

    def _echo_server():
        for _ in range(2):
            conn, _ = upstream.accept()
            with conn:
                conn.sendall(b"A" * 8192)
        upstream.close()

    t = threading.Thread(target=_echo_server, daemon=True)
    t.start()

    proxy = FaultProxy("127.0.0.1", uport)
    pport = proxy.start()
    try:
        # 1. Clean transfer
        with socket.create_connection(("127.0.0.1", pport), timeout=5) as s:
            chunks = []
            while chunk := s.recv(4096):
                chunks.append(chunk)
        assert len(b"".join(chunks)) == 8192

        # 2. Fault: drop after 1024 bytes
        proxy.set_fault(drop_after_bytes=1024, drop_once=True)
        with socket.create_connection(("127.0.0.1", pport), timeout=5) as s:
            chunks = []
            while chunk := s.recv(4096):
                chunks.append(chunk)
        assert len(b"".join(chunks)) < 8192
        assert proxy.drops_triggered == 1
        assert proxy.profile.drop_after_bytes == 0
    finally:
        proxy.stop()
        t.join(timeout=3)


def test_cache_hardlink_materialization_dedup_and_prune(tmp_path: Path):
    src = tmp_path / "seed"
    (src / "libraries" / "a").mkdir(parents=True)
    lib = src / "libraries" / "a" / "foo.jar"
    lib.write_bytes(b"jar-bytes")
    (src / "server.properties").write_text("x=1\n", encoding="utf-8")

    dest = tmp_path / "case" / "server"
    materialize_tree(src, dest, hardlink_dirs=frozenset({"libraries"}))
    assert os.path.samefile(lib, dest / "libraries" / "a" / "foo.jar")
    assert not os.path.samefile(src / "server.properties", dest / "server.properties")

    # Dedup across two target trees
    hmc_root = tmp_path / "hmc"
    for tid in ("t1", "t2"):
        p = hmc_root / tid / "libraries" / "lib.jar"
        p.parent.mkdir(parents=True)
        p.write_bytes(b"duplicate-payload-12345")
    stats = deduplicate_tree(hmc_root)
    assert stats.linked_files == 1
    assert os.path.samefile(hmc_root / "t1" / "libraries" / "lib.jar", hmc_root / "t2" / "libraries" / "lib.jar")

    # Prune passed case keeps logs, removes heavy server/clients dirs
    (tmp_path / "case" / "server.log").write_text("ok\n", encoding="utf-8")
    prune_passed_case(tmp_path / "case")
    assert not dest.exists()
    assert (tmp_path / "case" / "server.log").is_file()


def test_log_tailer_incremental_and_port_reservation(tmp_path: Path):
    log = tmp_path / "test.log"
    tailer = LogTailer(log)
    assert tailer.read_new() == ""
    log.write_text("line1\n", encoding="utf-8")
    assert tailer.read_new() == "line1\n"
    assert tailer.read_new() == ""
    with log.open("a", encoding="utf-8") as f:
        f.write("Done (1.2s)!\n")
    assert tailer.search(r"Done \(") is not None

    res = PortReservation(3)
    assert len(set(res.ports)) == 3
    res.release()


def test_static_https_host_serves_files(tmp_path: Path):
    (tmp_path / "hello.txt").write_text("world", encoding="utf-8")
    host = StaticHttpsHost(tmp_path, use_tls=True)
    host.start()
    try:
        assert len(host.fingerprint_sha256) == 64
        ctx = ssl._create_unverified_context()
        with urllib.request.urlopen(f"{host.base_url}/hello.txt", context=ctx, timeout=5) as resp:
            assert resp.read() == b"world"
    finally:
        host.stop()


def test_hmc_asset_names_and_mod_fixture():
    assert hmc_asset_name("linux", "x86_64") == "headlessmc-launcher-linux-x64"
    assert hmc_asset_name("win32", "AMD64") == "headlessmc-launcher-windows-x64.exe"
    assert hmc_asset_name("darwin", "arm64") == "headlessmc-launcher-macos-arm64"
    fx = {"modId": "test_mod", "version": "1.2.3", "marker": "ok"}
    assert_valid_mod_fixture(valid_mod_jar_bytes(fx, "26.2"), fx, "26.2")


def test_toml_emitter_round_trips_server_config_and_deep_merges():
    import tomllib

    base = {
        "modpack-host": True,
        "network": {"connection-mode": "HOLEPUNCH", "bind-port": -1},
        "groups": {"main": {"selection": "REQUIRED", "from-server": ["mods/*.jar"]}},
    }
    _deep_merge(base, {"modpack": {"name": "Pack"}, "groups": {"main": {"exclude": ["**/.*"]}}})
    parsed = tomllib.loads(_dump_toml(base))
    assert parsed["modpack-host"] is True
    assert parsed["network"]["bind-port"] == -1
    assert parsed["modpack"]["name"] == "Pack"
    assert parsed["groups"]["main"]["from-server"] == ["mods/*.jar"]
    assert parsed["groups"]["main"]["exclude"] == ["**/.*"]


def test_render_wrapper_priority_and_hmc_xvfb_flag(tmp_path: Path, monkeypatch):
    import shutil

    monkeypatch.setattr(shutil, "which", lambda cmd: f"/usr/bin/{cmd}" if cmd in ("xvfb-run", "gamescope") else None)
    assert linux_render_wrapper(1280, 720) == ["/usr/bin/xvfb-run", "-a", "-s", "-screen 0 1280x720x24"]
    monkeypatch.setattr(shutil, "which", lambda cmd: "/usr/bin/gamescope" if cmd == "gamescope" else None)
    assert linux_render_wrapper(1280, 720) == ["/usr/bin/gamescope", "--backend", "headless", "-W", "1280", "-H", "720", "--"]
    monkeypatch.setattr(shutil, "which", lambda _cmd: None)
    assert linux_render_wrapper(1280, 720) is None

    write_hmc_config(tmp_path, tmp_path / "mc", tmp_path / "game", tmp_path / "jdk", xvfb_check=True)
    cfg = (tmp_path / "headlessmc" / "config.properties").read_text(encoding="utf-8")
    assert "hmc.xvfb.check=true" in cfg


def test_server_seed_download_sets_non_urllib_user_agent(tmp_path: Path, monkeypatch):
    seen_ua: list[str] = []

    class FakeResp:
        def __enter__(self):
            return self

        def __exit__(self, *args):
            return False

        def read(self, n=-1):
            if getattr(self, "_done", False):
                return b""
            self._done = True
            return b"installer-bytes"

    def fake_urlopen(req, timeout=None):
        seen_ua.append(req.get_header("User-agent") or "")
        return FakeResp()

    monkeypatch.setattr("urllib.request.urlopen", fake_urlopen)
    out = tmp_path / "inst.jar"
    _download("https://example.invalid/forge.jar", out)
    assert seen_ua == ["automodpack-autotester"]
    assert out.read_bytes() == b"installer-bytes"
