"""Unit tests for the KVM/QEMU Windows VM provider and Windows guest agent."""
from __future__ import annotations

import base64
import json
import socket
import threading
from pathlib import Path

from automodpack_autotester.windows_agent import GuestAgentState
from automodpack_autotester.windows_vm import WindowsVmConfig, WindowsVmProvider, build_qemu_argv


def test_guest_agent_file_ops_and_exclusive_lock_lifecycle(tmp_path: Path):
    agent = GuestAgentState(tmp_path)
    assert agent.handle({"op": "ping"})["ok"] is True

    target = tmp_path / "game" / "mods" / "sample.jar"
    write = agent.handle({"op": "write_file", "path": str(target), "contentB64": base64.b64encode(b"hello-ntfs").decode("ascii")})
    assert write["ok"] is True and write["bytes"] == 10

    read = agent.handle({"op": "read_file", "path": str(target)})
    assert base64.b64decode(read["contentB64"]) == b"hello-ntfs"

    lock = agent.handle({"op": "hold_file_lock", "path": str(target)})
    assert lock["ok"] is True and lock["lockId"]
    rel = agent.handle({"op": "release_file_lock", "lockId": lock["lockId"]})
    assert rel["ok"] is True and rel["released"] is True


def test_guest_agent_bridge_call_proxy(tmp_path: Path):
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.bind(("127.0.0.1", 0))
    srv.listen(2)
    port = srv.getsockname()[1]

    def _bridge():
        conn, _ = srv.accept()
        with conn, conn.makefile("r", encoding="utf-8") as r, conn.makefile("w", encoding="utf-8") as w:
            req = json.loads(r.readline())
            w.write(json.dumps({"id": req["id"], "ok": True, "echo": req["op"]}) + "\n")
            w.flush()
        srv.close()

    t = threading.Thread(target=_bridge, daemon=True)
    t.start()

    agent = GuestAgentState(tmp_path)
    res = agent.handle({"op": "bridge_call", "port": port, "payload": {"id": 7, "token": "t", "op": "ping"}})
    assert res["ok"] is True
    assert res["response"]["id"] == 7
    assert res["response"]["echo"] == "ping"
    t.join(timeout=3)


def test_qemu_argv_uses_kvm_snapshot_and_ephemeral_overlay(tmp_path: Path):
    cfg = WindowsVmConfig(
        vm_dir=tmp_path,
        golden_disk=tmp_path / "windows-golden.qcow2",
        memory="6G",
        cpus=6,
        machine="pc",
        disk_interface="ide",
        nic="e1000e",
        tpm=True,
        snapshot_name="ready",
        guest_agent_port=19450,
    )
    argv = build_qemu_argv(cfg, host_agent_port=29450, qmp_socket=tmp_path / "qmp.sock", tpm_socket=tmp_path / "swtpm.sock")
    assert "-enable-kvm" in argv
    assert "-snapshot" in argv
    assert "-loadvm" not in argv
    assert "-tpmdev" in argv
    assert any("hostfwd=tcp:127.0.0.1:29450-:19450" in a for a in argv)

    snap_argv = build_qemu_argv(cfg, host_agent_port=29450, qmp_socket=tmp_path / "qmp.sock", tpm_socket=tmp_path / "swtpm.sock", ephemeral=False, load_snapshot=True)
    assert "-snapshot" not in snap_argv
    assert "-loadvm" in snap_argv
    assert "ready" in snap_argv

    provider = WindowsVmProvider({"windowsVm": {"dir": str(tmp_path)}})
    assert provider.capabilities.native_windows_ntfs is True
    assert provider.capabilities.os_family == "windows"
