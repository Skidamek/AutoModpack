"""Standalone zero-dependency Windows guest agent for KVM/QEMU Windows autotester workers.

Run inside the Windows VM (e.g. via Startup / Task Scheduler):
    python C:\\amp\\windows_agent.py --port 19450

Speaks newline-delimited JSON-RPC over TCP so the Linux host can spawn real Windows
HeadlessMC/Java clients on real NTFS, proxy AutoTestBridge calls, and inject real
Win32 NTFS file locks (dwShareMode=0) and junctions.
"""
from __future__ import annotations

import argparse
import base64
import json
import os
import platform
import secrets
import shutil
import socket
import subprocess
import sys
import threading
import time
import urllib.request
from pathlib import Path
from typing import Any


class _LockTable:
    """Holds real OS file locks (Win32 CreateFileW with dwShareMode=0 on Windows, exclusive open fallback elsewhere)."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._handles: dict[str, Any] = {}

    def acquire(self, path: str) -> str:
        lock_id = secrets.token_hex(8)
        if sys.platform.startswith("win"):
            import ctypes
            from ctypes import wintypes

            GENERIC_READ = 0x80000000
            GENERIC_WRITE = 0x40000000
            OPEN_ALWAYS = 4
            FILE_ATTRIBUTE_NORMAL = 0x80
            INVALID_HANDLE_VALUE = wintypes.HANDLE(-1).value
            handle = ctypes.windll.kernel32.CreateFileW(
                path,
                GENERIC_READ | GENERIC_WRITE,
                0,  # dwShareMode=0 -> exclusive lock (ERROR_SHARING_VIOLATION for other processes)
                None,
                OPEN_ALWAYS,
                FILE_ATTRIBUTE_NORMAL,
                None,
            )
            if handle == INVALID_HANDLE_VALUE:
                raise OSError(f"CreateFileW exclusive lock failed for {path!r}: winerr={ctypes.GetLastError()}")
            with self._lock:
                self._handles[lock_id] = ("win32", handle)
        else:
            Path(path).parent.mkdir(parents=True, exist_ok=True)
            fh = open(path, "a+b")
            with self._lock:
                self._handles[lock_id] = ("posix", fh)
        return lock_id

    def release(self, lock_id: str) -> bool:
        with self._lock:
            entry = self._handles.pop(lock_id, None)
        if entry is None:
            return False
        kind, handle = entry
        if kind == "win32":
            import ctypes

            ctypes.windll.kernel32.CloseHandle(handle)
        else:
            handle.close()
        return True

    def release_all(self) -> None:
        with self._lock:
            ids = list(self._handles.keys())
        for lid in ids:
            self.release(lid)


class GuestAgentState:
    def __init__(self, work_root: Path):
        self.work_root = work_root
        self.work_root.mkdir(parents=True, exist_ok=True)
        self.procs: dict[str, subprocess.Popen] = {}
        self.logs: dict[str, Path] = {}
        self.locks = _LockTable()

    def handle(self, req: dict[str, Any]) -> dict[str, Any]:
        op = str(req.get("op", ""))
        if op == "ping":
            return {"ok": True, "os": sys.platform, "platform": platform.platform(), "workRoot": str(self.work_root)}
        if op == "write_file":
            p = Path(req["path"])
            p.parent.mkdir(parents=True, exist_ok=True)
            data = base64.b64decode(req["contentB64"])
            p.write_bytes(data)
            return {"ok": True, "bytes": len(data)}
        if op == "read_file":
            p = Path(req["path"])
            if not p.is_file():
                return {"ok": False, "code": "not_found", "error": f"file not found: {p}"}
            return {"ok": True, "contentB64": base64.b64encode(p.read_bytes()).decode("ascii")}
        if op == "file_exists":
            return {"ok": True, "exists": Path(req["path"]).exists()}
        if op == "fetch_url":
            dest = Path(req["dest"])
            dest.parent.mkdir(parents=True, exist_ok=True)
            with urllib.request.urlopen(str(req["url"]), timeout=120) as resp, dest.open("wb") as out:
                shutil.copyfileobj(resp, out)
            return {"ok": True, "bytes": dest.stat().st_size}
        if op == "hold_file_lock":
            lock_id = self.locks.acquire(str(req["path"]))
            return {"ok": True, "lockId": lock_id}
        if op == "release_file_lock":
            return {"ok": True, "released": self.locks.release(str(req["lockId"]))}
        if op == "create_junction":
            link = Path(req["linkPath"])
            target = Path(req["targetPath"])
            link.parent.mkdir(parents=True, exist_ok=True)
            if sys.platform.startswith("win"):
                subprocess.run(["cmd", "/c", "mklink", "/J", str(link), str(target)], check=True, capture_output=True)
            else:
                os.symlink(target, link)
            return {"ok": True}
        if op == "spawn":
            proc_id = str(req["id"])
            argv = [str(a) for a in req["argv"]]
            cwd = Path(req["cwd"])
            cwd.mkdir(parents=True, exist_ok=True)
            log_path = cwd / f"{proc_id}.stdout.log"
            env = dict(os.environ)
            env.update({str(k): str(v) for k, v in (req.get("env") or {}).items()})
            fh = log_path.open("wb")
            kwargs: dict[str, Any] = {"cwd": str(cwd), "env": env, "stdout": fh, "stderr": subprocess.STDOUT, "stdin": subprocess.DEVNULL}
            if sys.platform.startswith("win"):
                kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
            proc = subprocess.Popen(argv, **kwargs)
            self.procs[proc_id] = proc
            self.logs[proc_id] = log_path
            return {"ok": True, "pid": proc.pid, "logPath": str(log_path)}
        if op == "proc_status":
            proc = self.procs.get(str(req["id"]))
            if proc is None:
                return {"ok": True, "alive": False, "returncode": None}
            rc = proc.poll()
            return {"ok": True, "alive": rc is None, "returncode": rc}
        if op == "kill":
            proc_id = str(req["id"])
            proc = self.procs.get(proc_id)
            if proc is not None and proc.poll() is None:
                if sys.platform.startswith("win"):
                    subprocess.run(["taskkill", "/PID", str(proc.pid), "/T", "/F"], check=False, capture_output=True)
                else:
                    proc.kill()
                try:
                    proc.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    pass
            return {"ok": True}
        if op == "bridge_call":
            port = int(req["port"])
            payload = (json.dumps(req["payload"], separators=(",", ":")) + "\n").encode("utf-8")
            with socket.create_connection(("127.0.0.1", port), timeout=float(req.get("timeout", 20.0))) as s:
                s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
                s.sendall(payload)
                line = s.makefile("r", encoding="utf-8").readline()
            return {"ok": True, "response": json.loads(line) if line else {"ok": False, "error": "empty response"}}
        return {"ok": False, "error": f"unknown agent op: {op!r}"}


def serve(host: str, port: int, work_root: Path) -> None:
    state = GuestAgentState(work_root)
    srv = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    srv.setsockopt(socket.SOL_SOCKET, socket.SO_REUSEADDR, 1)
    srv.bind((host, port))
    srv.listen(16)
    print(f"[amp-win-agent] listening on {host}:{port} (work={work_root})", flush=True)

    def _client(conn: socket.socket) -> None:
        with conn, conn.makefile("r", encoding="utf-8") as r, conn.makefile("w", encoding="utf-8") as w:
            conn.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            for line in r:
                if not line.strip():
                    continue
                req_id = None
                try:
                    req = json.loads(line)
                    req_id = req.get("id")
                    resp = state.handle(req)
                except Exception as e:
                    resp = {"ok": False, "error": str(e)}
                if req_id is not None:
                    resp["id"] = req_id
                w.write(json.dumps(resp, separators=(",", ":")) + "\n")
                w.flush()

    try:
        while True:
            conn, _ = srv.accept()
            threading.Thread(target=_client, args=(conn,), daemon=True).start()
    finally:
        state.locks.release_all()
        srv.close()


def main(argv: list[str] | None = None) -> int:
    p = argparse.ArgumentParser(prog="amp-windows-agent")
    p.add_argument("--host", default="0.0.0.0")
    p.add_argument("--port", type=int, default=19450)
    p.add_argument("--work-root", type=Path, default=Path(r"C:\amp\work") if sys.platform.startswith("win") else Path("/tmp/amp-win-agent"))
    args = p.parse_args(argv)
    serve(args.host, args.port, args.work_root)
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
