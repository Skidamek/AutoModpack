"""Autotester v2 CLI: fast, native-first, zero-bloat runner and Windows KVM worker control."""
from __future__ import annotations

import argparse
import json
import os
import shutil
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

from .cache import deduplicate_tree
from .config import (
    default_jobs,
    load_settings,
    load_targets,
    resolve_artifact,
    resolve_settings_path,
    shared_cache_root,
)
from .harness import CaseResult, run_scenario_fn
from .provider import LocalProvider, Provider
from .scenarios import list_scenarios, resolve_scenarios


def _select_provider(name: str, settings: dict) -> Provider:
    key = name.strip().lower()
    if key in ("local", "native"):
        return LocalProvider()
    if key in ("windows-vm", "win-vm", "windows"):
        from .windows_vm import WindowsVmProvider

        return WindowsVmProvider(settings)
    raise ValueError(f"Unknown provider: {name!r} (choose 'local' or 'windows-vm')")


def _select_targets(all_targets: dict, values: list[str] | None, default_target: str) -> list:
    raw = ",".join(values) if values else default_target
    if raw.strip().lower() == "all":
        return list(all_targets.values())
    selected = []
    seen = set()
    for tid in (t.strip() for t in raw.split(",") if t.strip()):
        if tid not in all_targets:
            raise KeyError(f"Unknown target {tid!r} (available: {', '.join(all_targets)})")
        if tid not in seen:
            selected.append(all_targets[tid])
            seen.add(tid)
    return selected


def _cmd_list() -> int:
    print("Scenarios:")
    for spec in list_scenarios():
        groups = f" [{', '.join(spec.groups)}]" if spec.groups else ""
        ntfs = " (Windows NTFS)" if spec.requires_windows_ntfs else ""
        print(f"  {spec.id:26s}{groups}{ntfs} - {spec.description}")
    print("\nTargets:")
    for tid, t in load_targets().items():
        print(f"  {tid:20s} mc={t.minecraft} loader={t.loader} java={t.java}")
    return 0


def _cmd_doctor(args) -> int:
    settings = load_settings()
    targets = _select_targets(load_targets(), args.target, str((settings.get("run") or {}).get("target", "26.2-fabric")))
    provider = _select_provider(args.provider or str((settings.get("run") or {}).get("provider", "local")), settings)
    artifact_dir = resolve_settings_path(settings, "artifactDir", "merged")
    jars = sorted(artifact_dir.glob("*.jar")) if artifact_dir.is_dir() else []
    checks = [
        ("python", sys.version_info >= (3, 11), sys.version.split()[0]),
        ("sharedCache", True, str(shared_cache_root())),
        ("artifactDir", bool(jars), f"{artifact_dir} ({len(jars)} jar(s))"),
        *provider.prerequisites(settings, targets),
    ]
    failures = 0
    for name, ok, detail in checks:
        if not ok:
            failures += 1
        print(f"{'OK  ' if ok else 'FAIL'} {name}: {detail}")
    return 0 if failures == 0 else 1


def _cmd_clean(args) -> int:
    settings = load_settings()
    out_dir = args.out_dir.resolve() if args.out_dir else resolve_settings_path(settings, "outDir", "autotester/out")
    if out_dir.exists():
        shutil.rmtree(out_dir, ignore_errors=True)
        print(f"Removed {out_dir}")
    if args.dedup:
        stats = deduplicate_tree(shared_cache_root() / "hmc")
        print(f"Deduplicated HMC cache: {stats.linked_files} files linked, {stats.reclaimed_bytes / (1024 ** 2):.1f} MiB reclaimed")
    if args.cache:
        cache = shared_cache_root()
        if cache.exists():
            shutil.rmtree(cache, ignore_errors=True)
            print(f"Removed shared cache {cache}")
    return 0


def _cmd_run(args) -> int:
    settings = load_settings()
    run_cfg = settings.get("run") or {}
    targets = _select_targets(load_targets(), args.target, str(run_cfg.get("target", "26.2-fabric")))
    scenarios = resolve_scenarios(args.scenario or str(run_cfg.get("scenario", "offline-smoke")))
    provider_name = args.provider or str(run_cfg.get("provider", "local"))
    provider = _select_provider(provider_name, settings)
    artifact_dir = args.artifact_dir.resolve() if args.artifact_dir else resolve_settings_path(settings, "artifactDir", "merged")
    out_dir = args.out_dir.resolve() if args.out_dir else resolve_settings_path(settings, "outDir", "autotester/out")
    out_dir.mkdir(parents=True, exist_ok=True)

    fast_timers = not args.no_fast_timers and bool(run_cfg.get("fastTimers", True))
    prune_passed = not args.keep_out and bool(run_cfg.get("prunePassed", True))
    jobs = max(1, int(args.jobs)) if args.jobs else default_jobs(settings)

    work: list[tuple] = []
    skipped: list[CaseResult] = []
    for target in targets:
        artifact = resolve_artifact(target, artifact_dir)
        for spec in scenarios:
            if not spec.matches_target(target):
                skipped.append(CaseResult(target=target.id, scenario=spec.id, ok=True, duration=0.0, skipped=True, skip_reason="out of target scope"))
                continue
            reason = spec.skip_reason(provider.capabilities)
            if reason:
                skipped.append(CaseResult(target=target.id, scenario=spec.id, ok=True, duration=0.0, skipped=True, skip_reason=reason))
                continue
            work.append((target, spec, artifact))

    if not work:
        print(f"No cases ran ({len(skipped)} skipped)", file=sys.stderr)
        return 1

    results: list[CaseResult] = list(skipped)
    try:
        with ThreadPoolExecutor(max_workers=jobs) as pool:
            futures = {
                pool.submit(
                    run_scenario_fn,
                    spec.fn,
                    target=target,
                    scenario_id=spec.id,
                    provider=provider,
                    settings=settings,
                    artifact=artifact,
                    out_dir=out_dir,
                    fast_timers=fast_timers,
                    prune_passed=prune_passed,
                ): (target.id, spec.id)
                for target, spec, artifact in work
            }
            for fut in as_completed(futures):
                res = fut.result()
                results.append(res)
                status = "PASS" if res.ok else "FAIL"
                print(f"{status} {res.target} {res.scenario} ({res.duration:.1f}s)", flush=True)
                if res.error:
                    print(f"  {res.error}", file=sys.stderr, flush=True)
    finally:
        if hasattr(provider, "close"):
            provider.close()

    ran = sum(1 for r in results if not r.skipped)
    failed = sum(1 for r in results if not r.skipped and not r.ok)
    payload = {
        "ok": failed == 0 and ran > 0,
        "provider": provider.capabilities.name,
        "summary": {"ran": ran, "skipped": len(skipped), "failed": failed, "total": len(results)},
        "results": [r.to_dict() for r in results],
    }
    tmp = out_dir / "results.json.tmp"
    tmp.write_text(json.dumps(payload, indent=2) + "\n", encoding="utf-8")
    os.replace(tmp, out_dir / "results.json")
    print(f"Summary: {ran} ran / {len(skipped)} skipped / {failed} failed")
    return 0 if payload["ok"] else 1


def _cmd_windows_vm(args) -> int:
    from .windows_vm import cli_windows_vm

    return cli_windows_vm(args, load_settings())


def main(argv: list[str] | None = None) -> int:
    parser = argparse.ArgumentParser(prog="autotester", description="AutoModpack v2 integration test runner")
    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("list", help="List available scenarios and targets")

    tgt_p = sub.add_parser("targets", help="Print selected target IDs as JSON (for CI matrices)")
    tgt_p.add_argument("--target", action="append")
    tgt_p.add_argument("--scenario")

    doc = sub.add_parser("doctor", help="Check prerequisites for the selected provider")
    doc.add_argument("--target", action="append")
    doc.add_argument("--provider", choices=("local", "windows-vm"))

    cln = sub.add_parser("clean", help="Clean output directory and optionally deduplicate or wipe shared cache")
    cln.add_argument("--out-dir", type=Path)
    cln.add_argument("--dedup", action="store_true", help="Hardlink duplicate files across HMC target caches")
    cln.add_argument("--cache", action="store_true", help="Also wipe ~/.cache/automodpack-autotest")

    run_p = sub.add_parser("run", help="Run scenarios against target(s)")
    run_p.add_argument("--target", action="append", help="Target ID, comma-separated list, or 'all'")
    run_p.add_argument("--scenario", help="Scenario ID, group (transport, release, offline, faults, windows), comma-separated list, or 'all'")
    run_p.add_argument("--provider", choices=("local", "windows-vm"), help="Execution provider (default: local)")
    run_p.add_argument("--jobs", type=int, help="Parallel cases (default: auto)")
    run_p.add_argument("--artifact-dir", type=Path)
    run_p.add_argument("--out-dir", type=Path)
    run_p.add_argument("--no-fast-timers", action="store_true", help="Use real 10s UI countdown timers")
    run_p.add_argument("--keep-out", action="store_true", help="Keep server/client directories even on passing cases")

    vm_p = sub.add_parser("windows-vm", help="Manage the KVM/QEMU live-snapshot Windows worker")
    vm_sub = vm_p.add_subparsers(dest="vm_action", required=True)
    vm_sub.add_parser("status", help="Show KVM/QEMU and golden Windows image status")
    vm_init = vm_sub.add_parser("init", help="Create base qcow2 disk (or CoW overlay from an existing Windows disk)")
    vm_init.add_argument("--from-disk", type=Path, help="Create a fast CoW overlay backed by an existing Windows qcow2 image")
    vm_init.add_argument("--iso", type=Path, help="Path to Windows 11 / Server ISO")
    vm_init.add_argument("--virtio-iso", type=Path, help="Path to virtio-win ISO")
    vm_init.add_argument("--disk-size", default="40G")
    vm_sub.add_parser("snapshot", help="Boot the golden image and save a live 'ready' RAM snapshot via QMP")

    args = parser.parse_args(argv)
    if args.command == "list":
        return _cmd_list()
    if args.command == "targets":
        settings = load_settings()
        run_cfg = settings.get("run") or {}
        targets = _select_targets(load_targets(), args.target, str(run_cfg.get("target", "all")))
        if args.scenario:
            scenarios = resolve_scenarios(args.scenario)
            targets = [t for t in targets if any(s.matches_target(t) for s in scenarios)]
        print(json.dumps([t.id for t in targets]))
        return 0
    if args.command == "doctor":
        return _cmd_doctor(args)
    if args.command == "clean":
        return _cmd_clean(args)
    if args.command == "run":
        return _cmd_run(args)
    if args.command == "windows-vm":
        return _cmd_windows_vm(args)
    return 1
