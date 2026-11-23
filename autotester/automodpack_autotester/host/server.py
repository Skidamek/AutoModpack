"""Minecraft server as a local Java child. itzg may seed the cache; it does not run the case."""
from __future__ import annotations

import os
import time
from pathlib import Path

from ..runtime.base import ExecResult, RuntimeState, ServerSpec
from . import rcon
from .java import java_bin
from .process import Process
from .seed import launch_argv, materialize


def write_server_properties(root: Path, *, game_port: int, rcon_port: int, rcon_password: str = "amp-autotest") -> None:
    path = root / "server.properties"
    lines = []
    if path.is_file():
        lines = path.read_text(encoding="utf-8").splitlines()
        drop = ("server-port=", "online-mode=", "server-ip=", "enable-rcon=", "rcon.port=", "rcon.password=")
        lines = [line for line in lines if not line.startswith(drop)]
    lines.extend(
        [
            f"server-port={game_port}",
            "server-ip=127.0.0.1",
            "online-mode=false",
            "enable-rcon=true",
            f"rcon.port={rcon_port}",
            f"rcon.password={rcon_password}",
        ]
    )
    path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    (root / "eula.txt").write_text("eula=true\n", encoding="utf-8")


class JavaServer:
    """One ``java -jar`` server process for one slot."""

    def __init__(self, ctx):
        self.name = ctx.srv_name
        self._process: Process | None = None
        self._ctx = ctx
        self._spec: ServerSpec | None = None

    def start(self, ctx, spec: ServerSpec) -> None:
        self._ctx = ctx
        self._spec = spec
        port = int(ctx.vars.get("server_game_port") or 0)
        rcon_port = int(ctx.vars.get("server_rcon_port") or 0)
        if port <= 0 or rcon_port <= 0:
            raise RuntimeError("native server needs allocated server_game_port and server_rcon_port")
        materialize(ctx.server_dir, spec, target_id=ctx.target.id, settings=ctx.settings)
        write_server_properties(ctx.server_dir, game_port=port, rcon_port=rcon_port)
        argv = [java_bin(ctx.settings, ctx.target.java), f"-Xmx{spec.memory}", *spec.jvm_args, *launch_argv(ctx.server_dir, spec)]
        # The same environment the container would get: the mod reads its data root from it, so a
        # native server must resolve the tree the harness inspects, not its own dedicated-server default.
        env = {**os.environ, **spec.env}
        log = ctx.server_dir.parent / f"{self.name}.log"
        if ctx.slot is not None:
            # The slot held the loopback ports until now, so no concurrent case was handed them.
            ctx.slot.release_ports()
            self._process = ctx.slot.spawn("server", argv, cwd=ctx.server_dir, env=env, log=log)
        else:
            self._process = Process()
            self._process.spawn(argv, cwd=ctx.server_dir, env=env, log=log)

    def stop(self) -> None:
        if self._process is not None:
            self._process.kill()

    def logs(self, tail: int | None = None) -> str:
        return self._process.logs(tail) if self._process is not None else ""

    def exec(self, argv: list[str], *, user: str | None = None) -> ExecResult:
        command = argv[1:] if argv and argv[0] == "rcon-cli" else argv
        port = int(self._ctx.vars.get("server_rcon_port") or 0)
        return rcon.run("127.0.0.1", port, "amp-autotest", " ".join(str(part) for part in command))

    def restart(self, timeout: float = 60) -> None:
        if self._spec is None:
            raise RuntimeError("native java server restart needs a previous start")
        self.stop()
        self.start(self._ctx, self._spec)

    def wait_for_log(self, needle: str, timeout: float) -> None:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            if needle in self.logs():
                return
            if self._process is not None and not self._process.alive():
                raise RuntimeError(f"server exited before log {needle!r}: {self.logs(tail=40)}")
            time.sleep(0.2)
        raise TimeoutError(f"Timeout waiting for {needle!r} in native server")

    def apply_netem(self, ctx, qdisc: list[str], label: str) -> None:
        raise RuntimeError(f"network shaping is not available on the native java server ({label})")

    def running(self) -> None:
        if self._process is None or not self._process.alive():
            raise RuntimeError(f"native server {self.name} is not running")

    def state(self) -> RuntimeState:
        alive = self._process is not None and self._process.alive()
        return RuntimeState(alive, None if alive else (self._process.exit_code() if self._process else 0))
