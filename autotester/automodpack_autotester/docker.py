from __future__ import annotations

import json
import subprocess
import time
from dataclasses import dataclass
from pathlib import Path
from typing import Mapping


@dataclass
class Container:
    name: str
    id: str


def _run(
    args: list[str], *, check: bool = True, capture: bool = False
) -> subprocess.CompletedProcess[str]:
    try:
        return subprocess.run(args, check=check, text=True, capture_output=capture)
    except subprocess.CalledProcessError as e:
        msg = str(e)
        if e.stderr:
            msg += f"\n  stderr: {e.stderr.strip()}"
        raise RuntimeError(msg) from e


def _output(args: list[str]) -> str:
    cp = _run(args, capture=True)
    return (cp.stdout or "") + (cp.stderr or "")


class Docker:
    def build(
        self,
        tag: str,
        dockerfile: Path,
        context: Path,
        build_args: Mapping[str, str] | None = None,
    ) -> None:
        args = ["docker", "build", "-t", tag, "-f", str(dockerfile)]
        for k, v in (build_args or {}).items():
            args += ["--build-arg", f"{k}={v}"]
        _run(args + [str(context)])

    def create_network(self, name: str) -> None:
        _run(["docker", "network", "rm", name], check=False, capture=True)
        _run(["docker", "network", "create", name])

    def remove_network(self, name: str) -> None:
        _run(["docker", "network", "rm", name], check=False, capture=True)

    def ensure_volume(self, name: str) -> None:
        _run(["docker", "volume", "create", name])

    def remove_volume(self, name: str) -> None:
        _run(["docker", "volume", "rm", name], check=False, capture=True)

    def remove_container(self, name: str) -> None:
        _run(["docker", "rm", "-f", name], check=False, capture=True)

    def run_detached(
        self,
        *,
        name: str,
        image: str,
        network: str,
        env: Mapping[str, str],
        mounts: list[tuple[Path | str, str, bool]],
        command: list[str] | None = None,
        user: str | None = None,
    ) -> Container:
        args = ["docker", "run", "-d", "--name", name, "--network", network]
        if user:
            args += ["-u", user]
        for k, v in env.items():
            args += ["-e", f"{k}={v}"]
        for host, container, readonly in mounts:
            args += ["-v", f"{host}:{container}:{'ro' if readonly else 'rw'}"]
        args.append(image)
        if command:
            args += command
        return Container(name=name, id=_output(args).strip())

    def logs(self, name: str) -> str:
        return _output(["docker", "logs", name])

    def inspect(self, name: str) -> dict:
        return json.loads(_output(["docker", "inspect", name]))[0]

    def wait_for_log(self, name: str, needle: str, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if needle in self.logs(name):
                return
            self.assert_running(name)
            time.sleep(2)
        raise TimeoutError(f"Timeout waiting for {needle!r} in {name}")

    def assert_running(self, name: str) -> None:
        state = self.inspect(name).get("State", {})
        if not state.get("Running", False):
            raise RuntimeError(
                f"Container {name} exited (code={state.get('ExitCode', -1)}, error={state.get('Error', '')})"
            )

    def wait_exited(self, name: str, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if not self.inspect(name).get("State", {}).get("Running", False):
                return
            time.sleep(1)
        raise TimeoutError(f"Timeout waiting for {name} to exit")
