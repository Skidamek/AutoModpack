"""Cross-platform managed subprocesses, incremental log tailing, and loopback port reservation."""
from __future__ import annotations

import os
import re
import signal
import socket
import subprocess
import sys
import time
from pathlib import Path


class PortReservation:
    """Holds bound loopback TCP sockets until handed off to a launching server."""

    def __init__(self, count: int):
        self._sockets: list[socket.socket] = []
        try:
            for _ in range(count):
                s = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
                s.bind(("127.0.0.1", 0))
                s.listen(1)
                self._sockets.append(s)
        except Exception:
            self.release()
            raise
        self.ports: list[int] = [int(s.getsockname()[1]) for s in self._sockets]

    def release(self) -> None:
        for s in self._sockets:
            try:
                s.close()
            except OSError:
                pass
        self._sockets.clear()


class LogTailer:
    """Incremental log reader that only reads newly appended bytes (O(new), never O(N^2))."""

    def __init__(self, path: Path):
        self.path = path
        self._offset = 0
        self._buffer = ""

    def read_new(self) -> str:
        if not self.path.is_file():
            return ""
        try:
            size = self.path.stat().st_size
            if size < self._offset:
                self._offset = 0
            with self.path.open("rb") as f:
                f.seek(self._offset)
                chunk = f.read()
                self._offset += len(chunk)
        except OSError:
            return ""
        text = chunk.decode("utf-8", errors="replace")
        self._buffer += text
        return text

    def full_text(self) -> str:
        self.read_new()
        return self._buffer

    def tail(self, lines: int = 80) -> str:
        return "\n".join(self.full_text().splitlines()[-lines:])

    def search(self, pattern: str | re.Pattern[str]) -> re.Match[str] | None:
        self.read_new()
        return re.search(pattern, self._buffer, re.MULTILINE)


class ManagedProcess:
    """Subprocess isolated in its own process group with clean tree kill (SIGKILL or TerminateProcess)."""

    def __init__(self, argv: list[str], *, cwd: Path, env: dict[str, str], log_path: Path):
        self.argv = argv
        self.cwd = cwd
        self.env = env
        self.log_path = log_path
        self.tailer = LogTailer(log_path)
        self._proc: subprocess.Popen | None = None
        self._log_handle = None

    def start(self) -> None:
        self.log_path.parent.mkdir(parents=True, exist_ok=True)
        self._log_handle = self.log_path.open("wb")
        kwargs: dict = {
            "cwd": str(self.cwd),
            "env": self.env,
            "stdout": self._log_handle,
            "stderr": subprocess.STDOUT,
            "stdin": subprocess.DEVNULL,
        }
        if sys.platform.startswith("win"):
            kwargs["creationflags"] = subprocess.CREATE_NEW_PROCESS_GROUP
        else:
            kwargs["start_new_session"] = True
        self._proc = subprocess.Popen(self.argv, **kwargs)

    @property
    def pid(self) -> int | None:
        return self._proc.pid if self._proc is not None else None

    def alive(self) -> bool:
        return self._proc is not None and self._proc.poll() is None

    def returncode(self) -> int | None:
        return self._proc.poll() if self._proc is not None else None

    def wait(self, timeout: float | None = None) -> int:
        if self._proc is None:
            return 0
        return self._proc.wait(timeout=timeout)

    def kill(self, *, force: bool = True) -> None:
        """Terminate the entire process tree immediately (SIGKILL on POSIX, taskkill /T /F on Windows)."""
        proc = self._proc
        if proc is not None and proc.poll() is None:
            if sys.platform.startswith("win"):
                cmd = ["taskkill", "/PID", str(proc.pid), "/T"]
                if force:
                    cmd.append("/F")
                subprocess.run(cmd, check=False, capture_output=True)
            else:
                sig = signal.SIGKILL if force else signal.SIGTERM
                try:
                    os.killpg(proc.pid, sig)
                except ProcessLookupError:
                    pass
            try:
                proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                proc.kill()
                proc.wait(timeout=5)
        if self._log_handle is not None:
            try:
                self._log_handle.close()
            except OSError:
                pass
            self._log_handle = None

    def wait_for_log(self, pattern: str | re.Pattern[str], *, timeout: float = 120.0) -> re.Match[str]:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            match = self.tailer.search(pattern)
            if match is not None:
                return match
            if not self.alive():
                # One final read after exit in case the matching line flushed right before exit.
                match = self.tailer.search(pattern)
                if match is not None:
                    return match
                raise RuntimeError(f"Process exited (code={self.returncode()}) before matching {pattern!r}\n--- log tail ---\n{self.tailer.tail(50)}")
            time.sleep(0.05)
        raise TimeoutError(f"Timed out after {timeout:.0f}s waiting for {pattern!r}\n--- log tail ---\n{self.tailer.tail(50)}")
