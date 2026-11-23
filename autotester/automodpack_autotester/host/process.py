"""One child process tree: spawn, logs, wait, kill."""
from __future__ import annotations

import os
import signal
import subprocess
import sys
from pathlib import Path


class Process:
    """A supervised child. Logs go to one file. Kill tears down the whole tree."""

    def __init__(self) -> None:
        self._proc: subprocess.Popen | None = None
        self._log: Path | None = None
        self._log_file = None

    def spawn(self, argv: list[str], *, cwd: Path | None = None, env: dict[str, str] | None = None, log: Path) -> None:
        if self._proc is not None and self._proc.poll() is None:
            raise RuntimeError("process is already running")
        log.parent.mkdir(parents=True, exist_ok=True)
        self._log = log
        self._log_file = log.open("ab")
        creationflags = 0
        start_new_session = True
        if sys.platform.startswith("win"):
            creationflags = subprocess.CREATE_NEW_PROCESS_GROUP
            start_new_session = False
        self._proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=subprocess.DEVNULL, stdout=self._log_file, stderr=subprocess.STDOUT, start_new_session=start_new_session, creationflags=creationflags)

    def alive(self) -> bool:
        return self._proc is not None and self._proc.poll() is None

    def exit_code(self) -> int | None:
        if self._proc is None:
            return None
        code = self._proc.poll()
        return None if code is None else int(code)

    def wait(self, timeout: float | None = None) -> int:
        if self._proc is None:
            raise RuntimeError("process was never started")
        code = self._proc.wait(timeout=timeout)
        self._close_log()
        return int(code)

    def kill(self) -> None:
        if self._proc is None or self._proc.poll() is not None:
            self._close_log()
            return
        if sys.platform.startswith("win"):
            subprocess.run(["taskkill", "/PID", str(self._proc.pid), "/T", "/F"], capture_output=True, check=False)
        else:
            os.killpg(self._proc.pid, signal.SIGKILL)
        try:
            self._proc.wait(timeout=10)
        except subprocess.TimeoutExpired:
            self._proc.kill()
            self._proc.wait(timeout=10)
        self._close_log()

    def logs(self, tail: int | None = None) -> str:
        if self._log is None:
            return ""
        try:
            text = self._log.read_text(encoding="utf-8", errors="replace")
        except OSError:
            return ""
        if tail is not None and tail >= 0:
            return "\n".join(text.splitlines()[-tail:])
        return text

    def _close_log(self) -> None:
        if self._log_file is not None:
            self._log_file.close()
            self._log_file = None
