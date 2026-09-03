"""Case orchestration: resolve the artifact, build the per-case context, run the flow.

The Docker lifecycle verbs live in server_steps/client_steps/staging_steps; importing
them here registers them in the shared verb registry the executor dispatches on.
"""
from __future__ import annotations

import logging
import secrets
import threading
import time
import zipfile
from pathlib import Path

from . import client_steps, server_steps, staging_steps  # noqa: F401  -- import for verb registration
from .config import Target, load_macros, parse_server_files
from .docker_harness import _assert_running, _container_logs, _ensure_network, _remove_container, _remove_network
from .engine import ClientExited, Context, run_flow
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
    with zipfile.ZipFile(artifact) as jar:
        names = set(jar.namelist())
    if "META-INF/jarjar/automodpack-mod.jar" in names:
        with zipfile.ZipFile(artifact) as jar, jar.open("META-INF/jarjar/automodpack-mod.jar") as nested, zipfile.ZipFile(nested) as mod_jar:
            names.update(mod_jar.namelist())
    if not any(name.startswith("pl/skidam/automodpack/client/autotest/") and name.endswith(".class") for name in names):
        raise RuntimeError(
            f"Artifact {artifact} has no AutoTestBridge classes; rebuild with -Pautomodpack.autotest before running the autotester"
        )
    return artifact


def run_case(
    target: Target,
    scenario: dict,
    *,
    out_dir: Path,
    artifact_dir: Path,
    client_image: str,
    settings: dict,
    resource_scope: str,
) -> dict:
    started = time.monotonic()
    scenario_id = scenario.get("id", "?")
    connection_mode = str((scenario.get("connectionPath") or {}).get("mode", "")).upper() or None
    net_mode = transport(scenario, settings)
    mode = scenario_mode(scenario)
    case_dir = out_dir / f"{target.id}-{int(time.time())}-{secrets.token_hex(3)}"
    server_dir = case_dir / "server"
    game_dir = case_dir / "client" / "game"
    # On host networking the two containers share the host namespace, so there is
    # no user-defined network to create and the client reaches the server locally.
    resource_prefix = f"amp-{resource_scope}"
    net_name = "host" if net_mode == "host" else f"{resource_prefix}-n-{secrets.token_hex(4)}"[:63]
    srv_name = f"{resource_prefix}-s-{secrets.token_hex(4)}"[:63]
    cli_name = f"{resource_prefix}-c-{secrets.token_hex(4)}"[:63]
    prep_name = cli_name.replace("-c-", "-p-", 1)
    server_host = "127.0.0.1" if net_mode == "host" else srv_name
    token = secrets.token_hex(16)
    case_seconds = float(settings.get("timeouts", {}).get("caseSeconds", 1800))
    if case_seconds <= 0:
        raise ValueError("timeouts.caseSeconds must be positive")
    deadline_expired = threading.Event()
    case_finished = threading.Event()

    def expire_case() -> None:
        if case_finished.wait(case_seconds):
            return
        deadline_expired.set()
        logger.error("[%s] case exceeded its %.0fs deadline; removing its Docker resources", target.id, case_seconds)
        for name in (cli_name, prep_name, srv_name):
            try:
                _remove_container(name)
            except Exception:
                logger.warning("Failed to remove expired case container %s", name)
        if net_name != "host":
            try:
                _remove_network(net_name)
            except Exception:
                logger.warning("Failed to remove expired case network %s", net_name)

    threading.Thread(target=expire_case, name=f"deadline-{target.id}", daemon=True).start()

    for d in (server_dir, game_dir):
        d.mkdir(parents=True, exist_ok=True)

    step_results: list[dict] = []
    try:
        artifact = _resolve_artifact(target, artifact_dir)

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
            net_name=net_name,
            token=token,
            artifact=artifact,
            modpack_name=sf.modpack_name,
            marker_rel=sf.marker,
            scenario_files=sf.files,
            expected_mods=sf.expected_mods,
            server_host=server_host,
            resource_scope=resource_scope,
            vars={
                **dict(scenario.get("vars", {}) or {}),
                "server_endpoint_port": int((scenario.get("connectionPath") or {}).get("endpointPort", 25565)),
            },
        )
        ctx.logs_provider = lambda which, tail=None: _container_logs(
            srv_name if which == "server" else cli_name, tail=tail
        )

        def _running():
            try:
                _assert_running(cli_name)
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
        if mode != "client-only":
            server_steps._prepare_server(ctx)
        if net_name != "host":
            _ensure_network(net_name, resource_labels(resource_scope))

        run_flow(ctx, scenario, lib=load_macros(), results=step_results)

        if deadline_expired.is_set():
            raise TimeoutError(f"Case exceeded the configured {case_seconds:.0f}s deadline")

        return {
            "target": target.id,
            "scenario": scenario_id,
            "connectionMode": connection_mode,
            "ok": True,
            "duration": time.monotonic() - started,
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
            "error": error,
            "steps": step_results,
        }

    finally:
        case_finished.set()
        for name in [cli_name, prep_name, srv_name]:
            try:
                logs = _container_logs(name)
                if logs:
                    (case_dir / f"{name}.log").write_text(logs, encoding="utf-8", errors="replace")
            except Exception:
                pass
            try:
                _remove_container(name)
            except Exception:
                logger.warning("Failed to remove container %s", name)
        if net_name != "host":
            _remove_network(net_name)
