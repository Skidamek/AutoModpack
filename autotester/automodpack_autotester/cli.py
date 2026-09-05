from __future__ import annotations

import json
import logging
import os
import secrets
import signal
import shutil
import subprocess
import sys
from copy import deepcopy
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import docker as docker_py

from .cache import deduplicate_asset_objects
from .config import (
    REPO_ROOT,
    ROOT,
    connection_path_variants,
    load_macros,
    load_scenarios,
    load_settings,
    load_targets,
    scenario_matches_target,
)
from .runner import run_case
from .supervisor import RunSupervisor, reap_orphaned_scopes
from .validate import CONNECTION_MODES, validate_scenario

logging.basicConfig(level=logging.INFO, format="[%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)


def _interrupt_on_termination(_signum, _frame) -> None:
    raise KeyboardInterrupt


def _resolve_settings_path(s: dict, key: str, default: str) -> Path:
    raw = s.get("paths", {}).get(key, default)
    p = Path(str(raw))
    return (REPO_ROOT / p).resolve() if not p.is_absolute() else p.resolve()


def _stop_gradle_daemons() -> None:
    gradlew = REPO_ROOT / "gradlew"
    if not os.access(gradlew, os.X_OK):
        return
    try:
        subprocess.run([str(gradlew), "--stop"], cwd=REPO_ROOT, check=False, capture_output=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        pass


def _cleanup_run_resources(resource_scope: str) -> None:
    prefix = f"amp-{resource_scope}-"
    client = docker_py.from_env()
    for c in client.containers.list(all=True):
        if not c.name.startswith(prefix):
            continue
        try:
            c.remove(force=True)
        except Exception:
            pass
    for n in client.networks.list():
        if not n.name.startswith(prefix):
            continue
        try:
            n.remove()
        except Exception:
            pass


def _matrix_payload(selected: list, results: dict, interrupted: bool) -> dict:
    expected = [target.id for target in selected]
    terminal = dict(results)
    for target_id in expected:
        if target_id not in terminal:
            terminal[target_id] = {
                "target": target_id,
                "scenario": "?",
                "ok": False,
                "duration": 0,
                "error": "Case did not produce a terminal result",
                "steps": [],
            }
    complete = set(terminal) == set(expected) and len(terminal) == len(expected)
    return {"ok": not interrupted and complete and all(result.get("ok", False) for result in terminal.values()), "results": [terminal[target_id] for target_id in expected]}


def _write_results(path: Path, payload: dict) -> None:
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    os.replace(temporary, path)


def _run_target_cases(target, variants, *, out_dir, artifact_dir, client_image, settings, resource_scope):
    case_results = [
        run_case(
            target,
            deepcopy(variant),
            out_dir=out_dir,
            artifact_dir=artifact_dir,
            client_image=client_image,
            settings=settings,
            resource_scope=resource_scope,
        )
        for variant in variants
    ]
    if len(case_results) == 1:
        return case_results[0]
    failures = [
        f"{result.get('connectionMode', result.get('scenario', '?'))}: {result.get('error', 'failed')}"
        for result in case_results
        if not result.get("ok", False)
    ]
    return {
        "target": target.id,
        "scenario": variants[0].get("id", "?"),
        "ok": all(result.get("ok", False) for result in case_results),
        "duration": sum(float(result.get("duration", 0)) for result in case_results),
        "connectionPaths": case_results,
        "error": "; ".join(failures) if failures else None,
    }


def _cmd_verbs() -> int:
    from .engine import conditions
    from .engine.registry import describe as describe_verbs

    print("Verbs:")
    for entry in describe_verbs():
        names = ", ".join(entry["names"])
        print(f"  {names}")
        if entry["doc"]:
            print(f"      {entry['doc']}")
    print("\nCondition keys (when / until / that):")
    print(f"  {', '.join(sorted(conditions.KEYS))}")
    print("\nlog condition keys:")
    print(f"  {', '.join(sorted(conditions.LOG_KEYS))}")
    return 0


def _cmd_validate(scenario_name: str | None) -> int:
    macros = load_macros()
    scenarios = load_scenarios()
    targets = load_targets()
    settings = load_settings()
    if scenario_name in (None, "all"):
        if "all" not in scenarios:
            print("FAIL release gate: scenarios/all.yaml is missing")
            return 1
        if str(settings.get("run", {}).get("scenario", "")) != "all":
            print("FAIL release gate: settings.yaml run.scenario must be 'all'")
            return 1
    if scenario_name:
        if scenario_name not in scenarios:
            print(f"No such scenario: {scenario_name}", file=sys.stderr)
            return 1
        scenarios = {scenario_name: scenarios[scenario_name]}
    ok = True
    for name, scenario in scenarios.items():
        problems = validate_scenario(scenario, macros, targets)
        if problems:
            ok = False
            print(f"FAIL {name}")
            for prob in problems:
                print(f"  - {prob}")
        else:
            print(f"OK   {name}")
    return 0 if ok else 1


def _select_targets(targets: dict, target_name: str, scenario: dict) -> tuple[list, list]:
    requested = list(targets.values()) if target_name == "all" else [targets[name.strip()] for name in target_name.split(",") if name.strip()]
    return requested, [t for t in requested if scenario_matches_target(scenario, t)]


def _selection_defaults(settings: dict) -> tuple[str, str]:
    run = settings.get("run", {})
    return str(run.get("scenario", "all")), str(run.get("target", "all"))


def _cmd_targets(scenario_name: str | None, target_name: str | None) -> int:
    default_scenario, default_target = _selection_defaults(load_settings())
    scenario_name = scenario_name or default_scenario
    target_name = target_name or default_target
    scenarios = load_scenarios()
    scenario = scenarios.get(scenario_name)
    if scenario is None:
        print(f"No such scenario: {scenario_name}", file=sys.stderr)
        return 1
    targets = load_targets()
    problems = validate_scenario(scenario, load_macros(), targets)
    if problems:
        for problem in problems:
            print(problem, file=sys.stderr)
        return 1
    _, selected = _select_targets(targets, target_name, scenario)
    print(json.dumps([t.id for t in selected]))
    return 0


def main(argv: list[str] | None = None) -> int:
    import argparse

    p = argparse.ArgumentParser(prog="autotester")
    sub = p.add_subparsers(dest="command", required=True)

    build = sub.add_parser("build-images")
    build.add_argument("--client-image")

    run_p = sub.add_parser("run")
    run_p.add_argument("--target")
    run_p.add_argument("--scenario")
    run_p.add_argument("--connection-path", choices=sorted(CONNECTION_MODES), type=str.upper,
                       help="Run one connection path (e.g. HOLEPUNCH) instead of the scenario's full matrix")
    run_p.add_argument("--jobs", type=int)
    run_p.add_argument("--docker-uid", type=int)
    run_p.add_argument("--docker-gid", type=int)
    run_p.add_argument("--artifact-dir", type=Path)
    run_p.add_argument("--out-dir", type=Path)
    run_p.add_argument("--client-image")

    clean = sub.add_parser("clean")
    clean.add_argument("--out-dir", type=Path)

    sub.add_parser("verbs", help="List available scenario verbs and condition keys")

    val = sub.add_parser("validate", help="Statically validate scenario(s) without Docker")
    val.add_argument("--scenario", help="Scenario stem; omit to validate all")

    target_p = sub.add_parser("targets", help="Print in-scope target IDs as JSON")
    target_p.add_argument("--scenario")
    target_p.add_argument("--target")

    args = p.parse_args(argv)

    if args.command == "verbs":
        return _cmd_verbs()

    if args.command == "validate":
        return _cmd_validate(args.scenario)

    if args.command == "targets":
        return _cmd_targets(args.scenario, args.target)

    if args.command == "build-images":
        s = load_settings()
        hmc = s.get("headlessmc", {})
        img = args.client_image or str(
            s.get("images", {}).get("client", "automodpack-autotest-client:local")
        )
        # Pass through only what settings provide; the Dockerfile falls back to
        # upstream HeadlessMC for anything unset.
        buildargs = {}
        if hmc.get("repo"):
            buildargs["HEADLESSMC_REPO"] = str(hmc["repo"])
        if hmc.get("ref"):
            buildargs["HEADLESSMC_REF"] = str(hmc["ref"])
        docker_py.from_env().images.build(
            path=str(ROOT / "docker" / "client"),
            dockerfile=str(ROOT / "docker" / "client" / "Dockerfile"),
            tag=img,
            buildargs=buildargs,
            rm=True,
        )
        return 0

    if args.command == "clean":
        s = load_settings()
        out_dir = (
            _resolve_settings_path(s, "outDir", "out")
            if not args.out_dir
            else args.out_dir.resolve()
        )
        shutil.rmtree(out_dir, ignore_errors=True)
        _stop_gradle_daemons()
        reap_orphaned_scopes()
        return 0

    # --- run ---
    s = load_settings()
    rc = s.get("run", {})

    if args.docker_uid is not None:
        os.environ["AUTOTEST_DOCKER_UID"] = str(args.docker_uid)
    elif rc.get("dockerUid") is not None:
        os.environ.setdefault("AUTOTEST_DOCKER_UID", str(rc["dockerUid"]))
    if args.docker_gid is not None:
        os.environ["AUTOTEST_DOCKER_GID"] = str(args.docker_gid)
    elif rc.get("dockerGid") is not None:
        os.environ.setdefault("AUTOTEST_DOCKER_GID", str(rc["dockerGid"]))

    targets = load_targets()
    scenarios = load_scenarios()
    default_scenario, default_target = _selection_defaults(s)
    scenario_name = args.scenario or default_scenario
    scenario = scenarios.get(scenario_name)
    if scenario is None:
        print(f"No such scenario: {scenario_name}", file=sys.stderr)
        return 1

    # Fail fast on a malformed scenario rather than after minutes in Docker.
    problems = validate_scenario(scenario, load_macros(), targets)
    if problems:
        print(f"Scenario {scenario_name!r} is invalid:", file=sys.stderr)
        for prob in problems:
            print(f"  - {prob}", file=sys.stderr)
        return 1

    target_name = args.target or default_target
    # Drop targets the scenario doesn't apply to (targets:/loaders:/minecraft:),
    # so an unrelated target doesn't fail confusingly on missing mods.
    requested, selected = _select_targets(targets, target_name, scenario)
    for t in requested:
        if t not in selected:
            print(f"SKIP {t.id} (out of scenario scope)")
    if not selected:
        print("No targets in scope for this scenario", file=sys.stderr)
        return 1
    variants = connection_path_variants(scenario)
    if args.connection_path:
        variants = [v for v in variants if str(v.get("connectionPath", {}).get("mode", "")).upper() == args.connection_path]
        if not variants:
            print(f"Scenario {scenario_name!r} declares no {args.connection_path} connection path", file=sys.stderr)
            return 1

    out_dir = (
        _resolve_settings_path(s, "outDir", "out")
        if not args.out_dir
        else args.out_dir.resolve()
    )
    artifact_dir = (
        _resolve_settings_path(s, "artifactDir", "merged")
        if not args.artifact_dir
        else args.artifact_dir.resolve()
    )
    client_image = args.client_image or str(
        s.get("images", {}).get("client", "automodpack-autotest-client:local")
    )
    out_dir.mkdir(parents=True, exist_ok=True)
    _stop_gradle_daemons()
    reap_orphaned_scopes()
    asset_cache = deduplicate_asset_objects(out_dir.parent / ".hmc-cache")
    if asset_cache.linked_files or asset_cache.invalid_objects or asset_cache.link_failures:
        logger.info(
            "HMC assets: linked %d duplicates, reclaimed %.2f GiB, invalid=%d, link failures=%d",
            asset_cache.linked_files,
            asset_cache.reclaimed_bytes / (1024 ** 3),
            asset_cache.invalid_objects,
            asset_cache.link_failures,
        )

    results: dict = {}
    interrupted = False
    resource_scope = secrets.token_hex(4)
    supervisor = RunSupervisor(resource_scope)
    try:
        executor = ThreadPoolExecutor(
            max_workers=max(1, args.jobs or rc.get("jobs", 1))
        )
        previous_sigterm_handler = signal.signal(signal.SIGTERM, _interrupt_on_termination)
        task_map = {}
        try:
            task_map = {
                executor.submit(
                    _run_target_cases,
                    t,
                    variants,
                    out_dir=out_dir,
                    artifact_dir=artifact_dir,
                    client_image=client_image,
                    settings=s,
                    resource_scope=resource_scope,
                ): t
                for t in selected
            }
            for f in as_completed(task_map):
                r = f.result()
                results[r["target"]] = r
                print(
                    f"{'PASS' if r['ok'] else 'FAIL'} {r['target']} {r.get('duration', 0):.1f}s"
                )
                for path_result in r.get("connectionPaths", [r] if r.get("connectionMode") else []):
                    print(
                        f"  {'PASS' if path_result['ok'] else 'FAIL'} {path_result.get('connectionMode', path_result.get('scenario', '?'))} "
                        f"{path_result.get('duration', 0):.1f}s"
                    )
                if r.get("error"):
                    print(f"  {r['error']}", file=sys.stderr)

        except KeyboardInterrupt:
            interrupted = True
            print("\nInterrupted, cleaning up containers...", file=sys.stderr)
            for ff in task_map:
                ff.cancel()
            try:
                _cleanup_run_resources(resource_scope)
            except KeyboardInterrupt:
                print("Force exit.", file=sys.stderr)
                os._exit(1)
            print("Cleanup complete.", file=sys.stderr)

        finally:
            signal.signal(signal.SIGTERM, previous_sigterm_handler)
            executor.shutdown(wait=False)
            payload = _matrix_payload(selected, results, interrupted)
            ok = payload["ok"]
            _write_results(out_dir / "results.json", payload)
            if interrupted:
                supervisor.close()
                os._exit(1)

        supervisor.close()
        return 0 if ok else 1

    except KeyboardInterrupt:
        supervisor.close()
        os._exit(1)
