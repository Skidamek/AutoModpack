"""Case orchestration: resolve the artifact, build the per-case context, run the flow.

The Docker lifecycle verbs live in server_steps/client_steps/staging_steps; importing
them here registers them in the shared verb registry the executor dispatches on.
"""
from __future__ import annotations

import io
import json
import logging
import secrets
import threading
import time
import zipfile
from pathlib import Path

from . import client_steps, server_steps, s3_host, staging_steps, static_host  # noqa: F401  -- import for verb registration
from .config import Target, load_macros, parse_server_files
from .engine import ClientExited, Context, run_flow
from .host.backend import FarmBackend
from .host.slot import open_slot
from .runtime.detect import case_skip_reason
from .supervisor import resource_labels


logger = logging.getLogger(__name__)


def transport(scenario: dict, settings: dict) -> str:
    """How containers talk: ``bridge`` (CI default) or ``host`` (constrained envs).

    Decoupled from flow logic, so the same scenario runs either way. Precedence:
    scenario ``network:`` > settings ``network:`` / ``run.network:`` > ``bridge``.
    """
    val = (
        scenario.get("network")
        or settings.get("network")
        or settings.get("run", {}).get("network")
        or "bridge"
    )
    return str(val).lower()


def scenario_mode(scenario: dict) -> str:
    """``full`` (server + client) or ``client-only`` (pre-staged, no server)."""
    return str(scenario.get("mode", "full")).lower()


def server_host_for(net_mode: str, platform: FarmBackend, srv_name: str) -> str:
    """Where the client reaches the server: loopback for host networking or a native client."""
    if net_mode == "host" or platform.capabilities.native_client or platform.capabilities.native_server:
        return "127.0.0.1"
    return srv_name


def _resolve_artifact(target: Target, artifact_dir: Path) -> Path:
    pattern = target.artifact_pattern.format(
        id=target.id,
        minecraft=target.minecraft,
        loader=target.loader,
    )
    matches = sorted(artifact_dir.glob(pattern))
    if not matches:
        raise FileNotFoundError(
            f"No artifact for {target.id} matching {pattern!r} in {artifact_dir}"
        )
    if len(matches) != 1:
        names = ", ".join(path.name for path in matches)
        raise RuntimeError(
            f"Ambiguous artifacts for {target.id} matching {pattern!r}: {names}"
        )
    artifact = matches[0].resolve()
    names = _jar_names(artifact)
    if not any(name.startswith("pl/skidam/automodpack/client/autotest/") and name.endswith(".class") for name in names):
        raise RuntimeError(
            f"Artifact {artifact} has no AutoTestBridge classes; rebuild with -Pautomodpack.autotest before running the autotester"
        )
    return artifact


def _jar_names(artifact: Path) -> set[str]:
    """Entry names of the artifact, descending into the one jar's zstd impl solid."""
    with zipfile.ZipFile(artifact) as jar:
        names = set(jar.namelist())
        if "impl/manifest.json" not in names:
            return names
        manifest = json.loads(jar.read("impl/manifest.json"))
        solid = _decompress_solid(jar.read("impl/all.zst"))
    names.discard("impl/manifest.json")
    names.discard("impl/all.zst")
    for entry in manifest["impls"]:
        with zipfile.ZipFile(io.BytesIO(solid[entry["offset"] : entry["offset"] + entry["length"]])) as impl:
            names.update(impl.namelist())
    return names


def _decompress_solid(zstd_bytes: bytes) -> bytes:
    import zstandard

    return zstandard.decompress(zstd_bytes)


class _CaseDeadline:
    """The case clock, started before artifact resolution.

    The clock cannot abort runtime resources until the case context exists, so
    ``attach`` is the hand-off: an expiry before that point is remembered and
    aborts as soon as the context is attached. This keeps a hung artifact fetch
    inside ``caseSeconds`` instead of letting the prepare phase run unguarded.
    """

    def __init__(self, target_id: str, seconds: float, abort):
        self.expired = threading.Event()
        self._finished = threading.Event()
        self._ctx = None
        self._abort = abort
        self._seconds = seconds
        self._target_id = target_id
        self._lock = threading.Lock()
        self._aborted = False
        self._thread = threading.Thread(
            target=self._watch, name=f"deadline-{target_id}", daemon=True
        )

    def start(self) -> None:
        self._thread.start()

    def attach(self, ctx) -> None:
        with self._lock:
            self._ctx = ctx
            expired = self.expired.is_set()
        if expired:
            self._abort_once()

    def finish(self) -> None:
        self._finished.set()

    def _watch(self) -> None:
        if self._finished.wait(self._seconds):
            return
        self._expire()

    def _expire(self) -> None:
        self.expired.set()
        logger.error(
            "[%s] case exceeded its %.0fs deadline; removing its platform resources",
            self._target_id,
            self._seconds,
        )
        self._abort_once()

    def _abort_once(self) -> None:
        with self._lock:
            ctx = self._ctx
            if ctx is None or self._aborted:
                return
            self._aborted = True
        try:
            self._abort(ctx)
        except Exception:
            logger.warning("Failed to abort expired case %s", self._target_id)


def run_case(
    target: Target,
    scenario: dict,
    *,
    out_dir: Path,
    artifact_dir: Path,
    client_image: str,
    settings: dict,
    resource_scope: str,
    platform: FarmBackend,
    netem: list[str] | None = None,
    loss: str = "",
    server_netem: list[str] | None = None,
) -> dict:
    started = time.monotonic()
    phases: dict[str, float] = {}
    scenario_id = scenario.get("id", "?")
    connection_mode = str((scenario.get("connectionPath") or {}).get("mode", "")).upper() or None
    skip_reason = case_skip_reason(scenario, platform)
    if skip_reason:
        logger.info("[%s] skip: %s", target.id, skip_reason)
        return {
            "target": target.id,
            "scenario": scenario_id,
            "connectionMode": connection_mode,
            "ok": True,
            "skipped": True,
            "skipReason": skip_reason,
            "duration": time.monotonic() - started,
            "phases": {"skip": time.monotonic() - started},
            "steps": [],
        }
    net_mode = transport(scenario, settings)
    # On host networking the client container shares the host's interfaces, so a
    # qdisc on its eth0 would shape the host's own traffic instead of the test's.
    if netem and net_mode == "host":
        raise ValueError("--netem requires bridge networking: on host networking the client shares the host's eth0")
    if loss and net_mode == "host":
        raise ValueError("--loss requires bridge networking: on host networking the server shares the host's eth0")
    if server_netem and net_mode == "host":
        raise ValueError("--server-netem requires bridge networking: on host networking the server shares the host's eth0")
    if server_netem and loss:
        raise ValueError("--server-netem and --loss both want the server's one root qdisc; pass the loss percentage via --loss and the delay/rate via --server-netem, not both")
    if (netem or loss or server_netem) and not platform.capabilities.netem:
        raise ValueError(f"network shaping is not available on the {platform.name} platform")
    mode = scenario_mode(scenario)
    config = getattr(platform, "config", None)
    if config is not None and config.provide == "native":
        net_mode = "host"
    case_dir = out_dir / f"{target.id}-{int(time.time())}-{secrets.token_hex(3)}"
    slot = open_slot(case_dir, secrets.token_hex(4))
    server_dir = slot.server_dir
    game_dir = slot.game_dir
    resource_prefix = f"amp-{resource_scope}"
    net_name = "host" if net_mode == "host" else f"{resource_prefix}-n-{secrets.token_hex(4)}"[:63]
    srv_name = f"{resource_prefix}-s-{secrets.token_hex(4)}"[:63]
    cli_name = f"{resource_prefix}-c-{secrets.token_hex(4)}"[:63]
    prep_name = cli_name.replace("-c-", "-p-", 1)
    static_name = f"{resource_prefix}-h-{secrets.token_hex(4)}"[:63]
    s3_name = f"{resource_prefix}-o-{secrets.token_hex(4)}"[:63]
    server_host = server_host_for(net_mode, platform, srv_name)
    game_port = slot.game_port
    endpoint_port = slot.endpoint_port
    rcon_port = slot.rcon_port
    token = secrets.token_hex(16)
    case_seconds = float(settings.get("timeouts", {}).get("caseSeconds", 1800))
    if case_seconds <= 0:
        raise ValueError("timeouts.caseSeconds must be positive")
    deadline = _CaseDeadline(target.id, case_seconds, platform.abort_case)
    deadline_expired = deadline.expired
    deadline.start()

    for d in (server_dir, game_dir):
        d.mkdir(parents=True, exist_ok=True)

    step_results: list[dict] = []
    ctx: Context | None = None
    try:
        phase_started = time.monotonic()
        artifact = _resolve_artifact(target, artifact_dir)
        phases["artifact"] = time.monotonic() - phase_started

        phase_started = time.monotonic()
        sf = parse_server_files(scenario)

        ctx = Context(
            target=target,
            scenario=scenario,
            settings=settings,
            game_dir=game_dir,
            server_dir=server_dir,
            out_dir=out_dir,
            client_image=client_image,
            srv_name=srv_name,
            cli_name=cli_name,
            prep_name=prep_name,
            net_name=net_name,
            token=token,
            artifact=artifact,
            modpack_name=sf.modpack_name,
            marker_rel=sf.marker,
            scenario_files=sf.files,
            server_host=server_host,
            static_name=static_name,
            static_host_image=str(settings.get("images", {}).get("staticHost", "automodpack-autotest-static-host:local")),
            s3_name=s3_name,
            minio_image=str(settings.get("images", {}).get("minio", "minio/minio:latest")),
            minio_client_image=str(settings.get("images", {}).get("minioClient", "minio/mc:latest")),
            resource_scope=resource_scope,
            netem=list(netem or []),
            loss=loss,
            server_netem=list(server_netem or []),
            platform=platform,
            capabilities=platform.capabilities,
            vars={
                **dict(scenario.get("vars", {}) or {}),
                "server_game_port": game_port,
                "server_endpoint_port": endpoint_port,
                "server_rcon_port": rcon_port,
            },
        )
        ctx.slot = slot
        ctx.client = platform.client(ctx)
        ctx.server = platform.server(ctx)
        ctx.hosting = platform.hosting(ctx)
        ctx.server_workspace = platform.workspace(ctx)
        phases["prepare"] = time.monotonic() - phase_started
        deadline.attach(ctx)
        if deadline_expired.is_set():
            raise TimeoutError(f"Case exceeded the configured {case_seconds:.0f}s deadline")

        def _running():
            try:
                ctx.client.running()
            except RuntimeError as e:
                raise ClientExited(str(e))
            client_log = game_dir / "logs" / "latest.log"
            try:
                recent_log = client_log.read_text(encoding="utf-8", errors="replace")
            except OSError:
                return
            if 'Uncaught exception in thread "automodpack-net"' in recent_log:
                tail = "\n".join(recent_log.splitlines()[-80:])
                raise ClientExited(f"AutoModpack network worker crashed\n--- logs ---\n{tail}")

        ctx.running_provider = _running

        # Client-only (pre-staged) runs never launch a server; full runs do.
        phase_started = time.monotonic()
        if mode != "client-only":
            server_steps._prepare_server(ctx)
        phases["server"] = time.monotonic() - phase_started
        phase_started = time.monotonic()
        if net_name != "host":
            ctx.hosting.ensure_network(net_name, resource_labels(resource_scope))
        phases["network"] = time.monotonic() - phase_started

        phase_started = time.monotonic()
        run_flow(ctx, scenario, lib=load_macros(), results=step_results)
        phases["flow"] = time.monotonic() - phase_started

        if deadline_expired.is_set():
            raise TimeoutError(f"Case exceeded the configured {case_seconds:.0f}s deadline")

        return {
            "target": target.id,
            "scenario": scenario_id,
            "connectionMode": connection_mode,
            "ok": True,
            "duration": time.monotonic() - started,
            "phases": phases,
            "steps": step_results,
        }

    except Exception as e:
        error = f"Case exceeded the configured {case_seconds:.0f}s deadline" if deadline_expired.is_set() else str(e)
        return {
            "target": target.id,
            "scenario": scenario_id,
            "connectionMode": connection_mode,
            "ok": False,
            "duration": time.monotonic() - started,
            "phases": phases,
            "error": error,
            "steps": step_results,
        }

    finally:
        phase_started = time.monotonic()
        deadline.finish()
        if ctx is not None:
            captures = []
            if ctx.server is not None:
                captures.append((srv_name, ctx.server.logs))
            if ctx.client is not None:
                captures.append((cli_name, ctx.client.logs))
                captures.append((prep_name, ctx.client.prepare_logs))
            if ctx.hosting is not None:
                captures.append((static_name, lambda tail=None: ctx.hosting.logs(static_name, tail)))
                captures.append((s3_name, lambda tail=None: ctx.hosting.logs(s3_name, tail)))
            for name, reader in captures:
                try:
                    logs = reader()
                    if logs:
                        (case_dir / f"{name}.log").write_text(logs, encoding="utf-8", errors="replace")
                except Exception:
                    pass
            for stop in (ctx.client.stop_prepare if ctx.client else None, ctx.client.stop if ctx.client else None, ctx.server.stop if ctx.server else None):
                if stop is None:
                    continue
                try:
                    stop()
                except Exception:
                    logger.warning("Failed to remove case service for %s", target.id)
            if ctx.hosting is not None:
                for name in (static_name, s3_name, f"{s3_name}-mc"):
                    try:
                        ctx.hosting.remove(name)
                    except Exception:
                        logger.warning("Failed to remove container %s", name)
            if net_name != "host":
                try:
                    ctx.hosting.remove_network(net_name)
                except Exception:
                    logger.warning("Failed to remove network %s", net_name)
            if ctx.slot is not None:
                ctx.slot.kill_all()
        phases["cleanup"] = time.monotonic() - phase_started
