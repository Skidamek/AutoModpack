"""Typed TestCase harness supporting multi-client, fault-injection proxy, static HTTPS, and auto-pruning."""
from __future__ import annotations

import json
import re
import secrets
import time
import tomllib
from contextlib import contextmanager
from copy import deepcopy
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any, Callable

from .cache import prune_passed_case
from .config import Target
from .mod_fixtures import write_valid_mod_fixture
from .provider import ClientProcess, Provider, ServerProcess
from .proxy import FaultProxy
from .static_server import StaticHttpsHost


@dataclass
class StepRecord:
    name: str
    duration: float
    ok: bool = True
    error: str | None = None


@dataclass
class CaseResult:
    target: str
    scenario: str
    ok: bool
    duration: float
    steps: list[StepRecord] = field(default_factory=list)
    error: str | None = None
    skipped: bool = False
    skip_reason: str | None = None

    def to_dict(self) -> dict[str, Any]:
        return {
            "target": self.target,
            "scenario": self.scenario,
            "ok": self.ok,
            "skipped": self.skipped,
            "skipReason": self.skip_reason,
            "duration": round(self.duration, 3),
            "error": self.error,
            "steps": [{"name": s.name, "duration": round(s.duration, 3), "ok": s.ok, **({"error": s.error} if s.error else {})} for s in self.steps],
        }


class TestCase:
    """Isolated test case owning a directory, optional server, N named clients, and optional proxies/hosts."""

    def __init__(
        self,
        *,
        target: Target,
        scenario_id: str,
        provider: Provider,
        settings: dict,
        artifact: Path,
        out_dir: Path,
        fast_timers: bool = True,
        prune_passed: bool = True,
    ):
        self.target = target
        self.scenario_id = scenario_id
        self.provider = provider
        self.settings = settings
        self.artifact = artifact
        self.fast_timers = fast_timers
        self.prune_passed = prune_passed
        self.case_dir = out_dir / f"{target.id}-{scenario_id}-{int(time.time())}-{secrets.token_hex(2)}"
        self.case_dir.mkdir(parents=True, exist_ok=True)
        self.steps: list[StepRecord] = []
        self._server: ServerProcess | None = None
        self._clients: dict[str, ClientProcess] = {}
        self._proxies: list[FaultProxy] = []
        self._static_hosts: list[StaticHttpsHost] = []

    @contextmanager
    def step(self, name: str):
        t0 = time.monotonic()
        try:
            yield
        except Exception as e:
            self.steps.append(StepRecord(name=name, duration=time.monotonic() - t0, ok=False, error=str(e)))
            raise
        else:
            self.steps.append(StepRecord(name=name, duration=time.monotonic() - t0, ok=True))

    @property
    def server(self) -> ServerProcess:
        if self._server is None:
            self._server = self.provider.create_server(self.target, self.settings, self.case_dir / "server", self.artifact)
        return self._server

    def client(self, name: str = "default") -> ClientProcess:
        if name not in self._clients:
            self._clients[name] = self.provider.create_client(name, self.target, self.settings, self.case_dir / "clients" / name, self.artifact)
        return self._clients[name]

    def configure_server(self, *, connection_mode: str = "HOLEPUNCH", overrides: dict[str, Any] | None = None) -> Path:
        """Write automodpack/server.toml into the server dir before starting."""
        cfg = deepcopy((self.settings.get("automodpack") or {}).get("config") or {})
        network = cfg.setdefault("network", {})
        mode = connection_mode.upper()
        network["connection-mode"] = mode
        if mode in ("HTTP", "HTTPS"):
            network["bind-address"] = "0.0.0.0"
            network["bind-port"] = self.server.endpoint_port
            network["advertised-host"] = self.server.host
            network["advertised-port"] = self.server.endpoint_port
        if overrides:
            _deep_merge(cfg, overrides)
        rendered = _dump_toml(cfg)
        tomllib.loads(rendered)
        cfg_path = self.server.server_dir / "automodpack" / "server.toml"
        cfg_path.parent.mkdir(parents=True, exist_ok=True)
        cfg_path.write_text(rendered, encoding="utf-8")
        return cfg_path

    def stage_server_file(self, rel_path: str, content: str | bytes) -> Path:
        dest = self.server.server_dir / rel_path
        dest.parent.mkdir(parents=True, exist_ok=True)
        if isinstance(content, bytes):
            dest.write_bytes(content)
        else:
            dest.write_text(content, encoding="utf-8")
        return dest

    def stage_server_mod(self, filename: str, fixture: dict[str, Any]) -> Path:
        dest = self.server.server_dir / "mods" / filename
        write_valid_mod_fixture(dest, fixture, minecraft_version=self.target.minecraft)
        return dest

    def start_proxy(self, target_host: str, target_port: int, *, bind_host: str = "127.0.0.1") -> FaultProxy:
        proxy = FaultProxy(target_host, target_port, bind_host=bind_host)
        proxy.start()
        self._proxies.append(proxy)
        return proxy

    def start_static_host(self, root: Path, *, use_tls: bool = True) -> StaticHttpsHost:
        host = StaticHttpsHost(root, use_tls=use_tls)
        host.start()
        self._static_hosts.append(host)
        return host

    def close(self, *, passed: bool) -> None:
        for proxy in self._proxies:
            proxy.stop()
        for host in self._static_hosts:
            host.stop()
        for cli in self._clients.values():
            try:
                cli.stop(force=True)
            except Exception:
                pass
        if self._server is not None:
            try:
                self._server.stop(force=True)
            except Exception:
                pass
        if passed and self.prune_passed:
            prune_passed_case(self.case_dir)


_BARE_KEY_RE = re.compile(r"^[A-Za-z0-9_-]+$")


def _deep_merge(base: dict[str, Any], patch: dict[str, Any]) -> None:
    for key, val in patch.items():
        if isinstance(val, dict) and isinstance(base.get(key), dict):
            _deep_merge(base[key], val)
        else:
            base[key] = deepcopy(val)


def _format_key(key: str) -> str:
    return key if _BARE_KEY_RE.match(key) else json.dumps(key)


def _format_value(value: Any) -> str:
    if isinstance(value, bool):
        return "true" if value else "false"
    if isinstance(value, int):
        return str(value)
    if isinstance(value, float):
        return repr(value)
    if isinstance(value, str):
        return json.dumps(value)
    if isinstance(value, (list, tuple)):
        return "[" + ", ".join(_format_value(v) for v in value) + "]"
    raise TypeError(f"Unsupported TOML value: {value!r}")


def _emit_table(lines: list[str], data: dict[str, Any], prefix: tuple[str, ...]) -> None:
    scalars = [(k, v) for k, v in data.items() if not isinstance(v, dict)]
    tables = [(k, v) for k, v in data.items() if isinstance(v, dict)]
    if prefix and (scalars or not tables):
        if lines:
            lines.append("")
        lines.append("[" + ".".join(_format_key(p) for p in prefix) + "]")
    for k, v in scalars:
        lines.append(f"{_format_key(k)} = {_format_value(v)}")
    for k, v in tables:
        _emit_table(lines, v, (*prefix, k))


def _dump_toml(data: dict[str, Any]) -> str:
    lines: list[str] = []
    _emit_table(lines, data, ())
    return "\n".join(lines) + ("\n" if lines else "")


def run_scenario_fn(
    fn: Callable[[TestCase], None],
    *,
    target: Target,
    scenario_id: str,
    provider: Provider,
    settings: dict,
    artifact: Path,
    out_dir: Path,
    fast_timers: bool = True,
    prune_passed: bool = True,
) -> CaseResult:
    started = time.monotonic()
    case = TestCase(
        target=target,
        scenario_id=scenario_id,
        provider=provider,
        settings=settings,
        artifact=artifact,
        out_dir=out_dir,
        fast_timers=fast_timers,
        prune_passed=prune_passed,
    )
    passed = False
    error: str | None = None
    try:
        fn(case)
        passed = True
    except Exception as e:
        error = f"{type(e).__name__}: {e}"
    finally:
        case.close(passed=passed)
    return CaseResult(
        target=target.id,
        scenario=scenario_id,
        ok=passed,
        duration=time.monotonic() - started,
        steps=case.steps,
        error=error,
    )
