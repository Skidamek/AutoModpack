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
from filelock import Timeout

from .cache import deduplicate_asset_objects
from .client_steps import parse_loss, parse_netem
from .config import (
    REPO_ROOT,
    ROOT,
    connection_path_variants,
    load_macros,
    load_scenarios,
    load_settings,
    load_targets,
    resolve_settings_path,
    scenario_matches_target,
    server_cache_volume,
)
from .runner import case_network, run_case
from .runtime.detect import case_skip_reason, select_platform
from .supervisor import RunSupervisor, reap_orphaned_scopes, server_cache_lock
from .validate import CONNECTION_MODES, validate_scenario

logging.basicConfig(level=logging.INFO, format="[%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)

_TARGET_HELP = "Target from targets.yaml, or all; repeatable, and each value may be a comma-separated list"


def _interrupt_on_termination(_signum, _frame) -> None:
    raise KeyboardInterrupt


def _stop_gradle_daemons() -> None:
    gradlew = REPO_ROOT / "gradlew"
    if not os.access(gradlew, os.X_OK):
        return
    try:
        subprocess.run([str(gradlew), "--stop"], cwd=REPO_ROOT, check=False, capture_output=True, timeout=60)
    except (OSError, subprocess.TimeoutExpired):
        pass


def _leaf_results(result: dict) -> list[dict]:
    """One target result can carry one entry per connection path; count the paths."""
    paths = result.get("connectionPaths")
    return paths if isinstance(paths, list) and paths else [result]


def _empty_selection_error(selector: str | None, ran: int, skipped: int) -> str | None:
    """Loud failure for an explicitly requested selection that exercised nothing.

    Something the caller named (a platform, a connection path) must actually run a case:
    every case skipping is a capability mismatch, not a green run.
    """
    if not selector or ran:
        return None
    return f"no cases ran on explicitly requested {selector}: {skipped} skipped"


def _explicit_selection(args, settings: dict) -> str | None:
    """What the caller named explicitly, phrased for :func:`_empty_selection_error`."""
    requested_platform = str(getattr(args, "platform", None) or getattr(args, "provide", None) or settings.get("platform") or "").lower()
    selectors = [f"platform {requested_platform!r}"] if requested_platform else []
    if getattr(args, "connection_path", None):
        selectors.append(f"connection path {args.connection_path!r}")
    return " and ".join(selectors) or None


def _selected_variants(scenario: dict, scenario_name: str, connection_path: str | None) -> list[dict] | None:
    """The connection-path variants this run/plan executes, or None when the scenario has none for that path."""
    variants = connection_path_variants(scenario, connection_path)
    if connection_path and not variants:
        print(f"Scenario {scenario_name!r} declares no {connection_path} connection path", file=sys.stderr)
        return None
    return variants


def _matrix_payload(
    selected: list,
    results: dict,
    interrupted: bool,
    explicit_selection: str | None = None,
) -> dict:
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
    leaves = [leaf for result in terminal.values() for leaf in _leaf_results(result)]
    ran = sum(1 for leaf in leaves if not leaf.get("skipped"))
    skipped = sum(1 for leaf in leaves if leaf.get("skipped"))
    failed = sum(1 for leaf in leaves if not leaf.get("skipped") and not leaf.get("ok", False))
    ok = not interrupted and complete and all(result.get("ok", False) for result in terminal.values())
    error = _empty_selection_error(explicit_selection, ran, skipped)
    if error:
        ok = False
    payload = {
        "ok": ok,
        "results": [terminal[target_id] for target_id in expected],
        "summary": {"ran": ran, "skipped": skipped, "failed": failed, "total": len(leaves)},
    }
    if error:
        payload["error"] = error
    return payload


def _summary_line(payload: dict) -> str:
    summary = payload.get("summary", {})
    line = f"Summary: {summary.get('ran', 0)} ran / {summary.get('skipped', 0)} skipped"
    if summary.get("failed"):
        line += f" / {summary['failed']} failed"
    return line


def _write_results(path: Path, payload: dict) -> None:
    temporary = path.with_suffix(".tmp")
    temporary.write_text(json.dumps(payload, indent=2), encoding="utf-8")
    os.replace(temporary, path)


def _server_cache_guard(target, variants, settings):
    """A held lock keeps two concurrent runs from sharing one server cache volume and corrupting each other's world."""
    sc = variants[0].get("serverCache") or settings.get("serverCache", {})
    if not sc.get("enabled", True):
        return None
    volume = server_cache_volume(target.id, sc.get("volumePrefix", "amp-server-cache"))
    lock = server_cache_lock(volume)
    while True:
        try:
            lock.acquire(timeout=5)
            return lock
        except Timeout:
            print(f"[wait] {target.id}: server cache is in use by another run; waiting", flush=True)


def _run_target_cases(target, variants, *, out_dir, artifact_dir, client_image, settings, resource_scope, platform, netem=None, loss="", server_netem=None):
    # A target whose every variant skips never touches the server cache, so it
    # must not block a real run (or wait behind one) on the shared volume lock.
    all_skip = bool(variants) and all(case_skip_reason(variant, platform, network=case_network(variant, settings, platform)) for variant in variants)
    lock = None if all_skip else _server_cache_guard(target, variants, settings)
    try:
        case_results = [
            run_case(
                target,
                deepcopy(variant),
                out_dir=out_dir,
                artifact_dir=artifact_dir,
                client_image=client_image,
                settings=settings,
                resource_scope=resource_scope,
                netem=netem,
                loss=loss,
                server_netem=server_netem,
                platform=platform,
            )
            for variant in variants
        ]
    finally:
        if lock is not None:
            lock.release()
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
        "skipped": all(result.get("skipped", False) for result in case_results),
        "skipReason": "; ".join(str(result.get("skipReason")) for result in case_results if result.get("skipped")) or None,
        "duration": sum(float(result.get("duration", 0)) for result in case_results),
        "phases": {
            phase: round(sum(float(result.get("phases", {}).get(phase, 0)) for result in case_results), 3)
            for phase in sorted({name for result in case_results for name in (result.get("phases") or {})})
        },
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


def _target_selection(values: list[str] | None) -> str | None:
    """One comma-separated target selection; a repeated ``--target`` is the union, never a silent overwrite."""
    if not values:
        return None
    return ",".join(name.strip() for value in values for name in value.split(",") if name.strip()) or None


def _selection_defaults(settings: dict) -> tuple[str, str]:
    run = settings.get("run", {})
    return str(run.get("scenario", "all")), str(run.get("target", "all"))


def _cmd_targets(scenario_name: str | None, target_values: list[str] | None) -> int:
    default_scenario, default_target = _selection_defaults(load_settings())
    scenario_name = scenario_name or default_scenario
    target_name = _target_selection(target_values) or default_target
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


def _cmd_plan(args) -> int:
    settings = load_settings()
    scenario_name = args.scenario or _selection_defaults(settings)[0]
    target_name = _target_selection(args.target) or _selection_defaults(settings)[1]
    scenarios = load_scenarios()
    scenario = scenarios.get(scenario_name)
    if scenario is None:
        print(f"No such scenario: {scenario_name}", file=sys.stderr)
        return 1
    targets = load_targets()
    problems = validate_scenario(scenario, load_macros(), targets)
    if problems:
        print(f"Scenario {scenario_name!r} is invalid:", file=sys.stderr)
        for problem in problems:
            print(f"  - {problem}", file=sys.stderr)
        return 1
    try:
        platform = select_platform(settings, requested=args.provide or args.platform, require_host=False)
    except Exception as error:
        print(f"Cannot plan: {error}", file=sys.stderr)
        return 1
    farm = getattr(platform, "config", None)
    if farm is not None:
        print(f"Farm: {farm.describe()}")
    variants = _selected_variants(scenario, scenario_name, args.connection_path)
    if variants is None:
        return 1
    _requested, selected = _select_targets(targets, target_name, scenario)
    ran = skipped = 0
    for target in selected:
        for variant in variants:
            reason = case_skip_reason(variant, platform, network=case_network(variant, settings, platform))
            if reason:
                skipped += 1
                print(f"SKIP {target.id} {variant.get('id', '?')} ({reason})")
            else:
                ran += 1
                print(f"RUN  {target.id} {variant.get('id', '?')}")
    print(f"Plan: {ran} run / {skipped} skip on {platform.name}")
    error = _empty_selection_error(_explicit_selection(args, settings), ran, skipped)
    if error:
        print(error, file=sys.stderr)
        return 1
    return 0


def _cmd_doctor(args) -> int:
    settings = load_settings()
    try:
        platform = select_platform(settings, requested=args.provide or args.platform)
    except Exception as error:
        print(f"FAIL platform: {error}", file=sys.stderr)
        return 1
    artifact_dir = resolve_settings_path(settings, "artifactDir", "merged")
    jars = sorted(artifact_dir.glob("*.jar")) if artifact_dir.is_dir() else []
    farm = getattr(platform, "config", None)
    checks = [
        ("farm", True, farm.describe() if farm is not None else platform.name),
        ("platform", True, platform.name),
        ("python", sys.version_info >= (3, 11), f"{sys.version.split()[0]} ({sys.executable})"),
        *platform.prerequisites(settings),
        ("artifactDir", bool(jars), f"{artifact_dir} ({len(jars)} jar(s))"),
    ]
    failures = 0
    for name, ok, detail in checks:
        if not ok:
            failures += 1
        print(f"{'OK  ' if ok else 'FAIL'} {name}: {detail}")
    return 0 if failures == 0 else 1


def main(argv: list[str] | None = None) -> int:
    import argparse

    p = argparse.ArgumentParser(prog="autotester")
    sub = p.add_subparsers(dest="command", required=True)

    build = sub.add_parser("build-images")
    build.add_argument("--client-image")

    run_p = sub.add_parser("run")
    run_p.add_argument("--target", action="append", help=_TARGET_HELP)
    run_p.add_argument("--scenario")
    run_p.add_argument("--connection-path", choices=sorted(CONNECTION_MODES), type=str.upper,
                       help="Run one connection path (e.g. HOLEPUNCH) instead of the scenario's full matrix")
    run_p.add_argument("--jobs", type=int)
    run_p.add_argument("--netem", type=parse_netem, metavar="delay=300ms,rate=5mbit",
                       help="Shape the client container's eth0 with a tc netem qdisc (bridge networking only), "
                            "e.g. delay=300ms,rate=5mbit")
    run_p.add_argument("--loss", type=parse_loss, metavar="1%%",
                       help="Drop this percentage of the server's outgoing segments with a netem loss qdisc "
                            "(bridge networking only) - the download's data direction")
    run_p.add_argument("--server-netem", type=parse_netem, metavar="delay=300ms,rate=5mbit",
                       help="Shape the SERVER container's eth0 with a tc netem qdisc (bridge networking only): "
                            "delay/rate on the download's data direction; shares the one root qdisc with --loss")
    run_p.add_argument("--docker-uid", type=int)
    run_p.add_argument("--docker-gid", type=int)
    run_p.add_argument("--artifact-dir", type=Path)
    run_p.add_argument("--out-dir", type=Path)
    run_p.add_argument("--client-image")
    run_p.add_argument("--provide", choices=("native", "docker"), help="Spawn strategy: native Java children on this kernel, or Docker containers")
    run_p.add_argument("--platform", help="Deprecated alias of --provide (linux=docker, windows=native)")

    clean = sub.add_parser("clean")
    clean.add_argument("--out-dir", type=Path)
    clean.add_argument("--stop-daemons", action="store_true",
                       help="Also stop Gradle daemons. Off by default: gradle --stop kills daemons for every "
                            "project of this Gradle user home, including builds another checkout is running.")

    sub.add_parser("verbs", help="List available scenario verbs and condition keys")

    val = sub.add_parser("validate", help="Statically validate scenario(s) without Docker")
    val.add_argument("--scenario", help="Scenario stem; omit to validate all")

    target_p = sub.add_parser("targets", help="Print in-scope target IDs as JSON")
    target_p.add_argument("--scenario")
    target_p.add_argument("--target", action="append", help=_TARGET_HELP)

    plan_p = sub.add_parser("plan", help="Print resolved cases and skip reasons without starting anything")
    plan_p.add_argument("--scenario")
    plan_p.add_argument("--target", action="append", help=_TARGET_HELP)
    plan_p.add_argument("--provide", choices=("native", "docker"))
    plan_p.add_argument("--platform", help="Deprecated alias of --provide (linux=docker, windows=native)")
    plan_p.add_argument("--connection-path", choices=sorted(CONNECTION_MODES), type=str.upper)

    doctor_p = sub.add_parser("doctor", help="Check the selected platform's runtime prerequisites")
    doctor_p.add_argument("--provide", choices=("native", "docker"))
    doctor_p.add_argument("--platform", help="Deprecated alias of --provide (linux=docker, windows=native)")

    args = p.parse_args(argv)

    if args.command == "verbs":
        return _cmd_verbs()

    if args.command == "validate":
        return _cmd_validate(args.scenario)

    if args.command == "targets":
        return _cmd_targets(args.scenario, args.target)

    if args.command == "plan":
        return _cmd_plan(args)

    if args.command == "doctor":
        return _cmd_doctor(args)

    if args.command == "build-images":
        s = load_settings()
        hmc = s.get("headlessmc", {})
        img = args.client_image or str(
            s.get("images", {}).get("client", "automodpack-autotest-client:local")
        )
        # Pass through only what settings provide; the Dockerfile falls back to
        # the upstream HeadlessMC 3 release for anything unset.
        buildargs = {}
        if hmc.get("repo"):
            buildargs["HEADLESSMC_REPO"] = str(hmc["repo"])
        if hmc.get("ref"):
            buildargs["HEADLESSMC_REF"] = str(hmc["ref"])
        images = docker_py.from_env().images
        images.build(
            path=str(ROOT / "docker" / "client"),
            dockerfile="Dockerfile",
            tag=img,
            buildargs=buildargs,
            rm=True,
        )
        images.build(
            path=str(ROOT / "docker" / "static-host"),
            dockerfile="Dockerfile",
            tag=str(s.get("images", {}).get("staticHost", "automodpack-autotest-static-host:local")),
            rm=True,
        )
        return 0

    if args.command == "clean":
        s = load_settings()
        out_dir = (
            resolve_settings_path(s, "outDir", "out")
            if not args.out_dir
            else args.out_dir.resolve()
        )
        shutil.rmtree(out_dir, ignore_errors=True)
        if args.stop_daemons:
            _stop_gradle_daemons()
        reap_orphaned_scopes()
        return 0

    # --- run ---
    s = load_settings()
    rc = s.get("run", {})
    try:
        platform = select_platform(s, requested=args.provide or args.platform)
    except Exception as error:
        print(f"Cannot select a test platform: {error}", file=sys.stderr)
        return 1

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

    target_name = _target_selection(args.target) or default_target
    # Drop targets the scenario doesn't apply to (targets:/loaders:/minecraft:),
    # so an unrelated target doesn't fail confusingly on missing mods.
    requested, selected = _select_targets(targets, target_name, scenario)
    for t in requested:
        if t not in selected:
            print(f"SKIP {t.id} (out of scenario scope)")
    if not selected:
        print("No targets in scope for this scenario", file=sys.stderr)
        return 1
    variants = _selected_variants(scenario, scenario_name, args.connection_path)
    if variants is None:
        return 1

    out_dir = (
        resolve_settings_path(s, "outDir", "out")
        if not args.out_dir
        else args.out_dir.resolve()
    )
    artifact_dir = (
        resolve_settings_path(s, "artifactDir", "merged")
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
        jobs = max(1, int(args.jobs or rc.get("jobs", 1)))
        # A native farm runs case children on this kernel, so its slots are the real concurrency bound;
        # container cases are isolated and answer only to --jobs.
        if getattr(platform, "name", "") == "native":
            jobs = min(jobs, getattr(getattr(platform, "config", None), "slots", jobs))
        executor = ThreadPoolExecutor(max_workers=jobs)
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
                    platform=platform,
                    netem=args.netem,
                    loss=args.loss,
                    server_netem=args.server_netem,
                ): t
                for t in selected
            }
            for f in as_completed(task_map):
                r = f.result()
                results[r["target"]] = r
                if r.get("skipped"):
                    print(f"SKIP {r['target']} ({r.get('skipReason')})")
                else:
                    print(
                        f"{'PASS' if r['ok'] else 'FAIL'} {r['target']} {r.get('duration', 0):.1f}s"
                    )
                for path_result in r.get("connectionPaths", [r] if r.get("connectionMode") else []):
                    if path_result.get("skipped"):
                        print(f"  SKIP {path_result.get('connectionMode', path_result.get('scenario', '?'))} ({path_result.get('skipReason')})")
                    else:
                        print(
                            f"  {'PASS' if path_result['ok'] else 'FAIL'} {path_result.get('connectionMode', path_result.get('scenario', '?'))} "
                            f"{path_result.get('duration', 0):.1f}s"
                        )
                if r.get("error"):
                    print(f"  {r['error']}", file=sys.stderr)

        except KeyboardInterrupt:
            interrupted = True
            print("\nInterrupted; the run watcher is reaping this run's containers and case children...", file=sys.stderr)
            for ff in task_map:
                ff.cancel()

        finally:
            signal.signal(signal.SIGTERM, previous_sigterm_handler)
            executor.shutdown(wait=False)
            payload = _matrix_payload(selected, results, interrupted, explicit_selection=_explicit_selection(args, s))
            ok = payload["ok"]
            _write_results(out_dir / "results.json", payload)
            print(_summary_line(payload))
            if payload.get("error"):
                print(payload["error"], file=sys.stderr)
            if interrupted:
                supervisor.abandon()
                os._exit(1)

        supervisor.close()
        return 0 if ok else 1

    except KeyboardInterrupt:
        supervisor.abandon()
        os._exit(1)
