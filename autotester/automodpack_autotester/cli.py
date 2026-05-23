from __future__ import annotations

import json
import logging
import os
import shutil
import sys
from concurrent.futures import ThreadPoolExecutor, as_completed
from pathlib import Path

import docker as docker_py

from .config import REPO_ROOT, ROOT, load_scenarios, load_settings, load_targets
from .runner import run_case

logging.basicConfig(level=logging.INFO, format="[%(levelname)s] %(message)s")
logger = logging.getLogger(__name__)


def _resolve_settings_path(s: dict, key: str, default: str) -> Path:
    raw = s.get("paths", {}).get(key, default)
    p = Path(str(raw))
    return (REPO_ROOT / p).resolve() if not p.is_absolute() else p.resolve()


def _kill_amp_containers() -> None:
    client = docker_py.from_env()
    for c in client.containers.list(all=True, filters={"name": "amp-"}):
        try:
            c.remove(force=True)
        except Exception:
            pass
    for n in client.networks.list(filters={"name": "amp-"}):
        try:
            n.remove()
        except Exception:
            pass


def main(argv: list[str] | None = None) -> int:
    import argparse

    p = argparse.ArgumentParser(prog="autotester")
    sub = p.add_subparsers(dest="command", required=True)

    build = sub.add_parser("build-images")
    build.add_argument("--client-image")
    build.add_argument("--headlessmc-version")

    run_p = sub.add_parser("run")
    run_p.add_argument("--target")
    run_p.add_argument("--scenario")
    run_p.add_argument("--jobs", type=int, default=1)
    run_p.add_argument("--docker-uid", type=int)
    run_p.add_argument("--docker-gid", type=int)
    run_p.add_argument("--artifact-dir", type=Path)
    run_p.add_argument("--out-dir", type=Path)
    run_p.add_argument("--client-image")

    clean = sub.add_parser("clean")
    clean.add_argument("--out-dir", type=Path)

    args = p.parse_args(argv)

    if args.command == "build-images":
        s = load_settings()
        ver = args.headlessmc_version or str(
            s.get("headlessmc", {}).get("version", "2.9.0")
        )
        img = args.client_image or str(
            s.get("images", {}).get("client", "automodpack-autotest-client:local")
        )
        docker_py.from_env().images.build(
            path=str(ROOT / "docker" / "client"),
            dockerfile=str(ROOT / "docker" / "client" / "Dockerfile"),
            tag=img,
            buildargs={"HEADLESSMC_VERSION": ver},
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
    scenario = scenarios.get(args.scenario or rc.get("scenario", "sync"))
    selected = (
        list(targets.values())
        if not args.target or args.target == "all"
        else [targets[args.target]]
    )
    if not selected:
        print("No targets", file=sys.stderr)
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

    results: dict = {}
    interrupted = False
    try:
        executor = ThreadPoolExecutor(
            max_workers=max(1, args.jobs or rc.get("jobs", 1))
        )
        try:
            task_map = {
                executor.submit(
                    run_case,
                    t,
                    scenario,
                    out_dir=out_dir,
                    artifact_dir=artifact_dir,
                    client_image=client_image,
                    settings=s,
                ): t
                for t in selected
            }
            for f in as_completed(task_map):
                r = f.result()
                results[r["target"]] = r
                print(
                    f"{'PASS' if r['ok'] else 'FAIL'} {r['target']} {r.get('duration', 0):.1f}s"
                )
                if r.get("error"):
                    print(f"  {r['error']}", file=sys.stderr)

        except KeyboardInterrupt:
            interrupted = True
            print("\nInterrupted, cleaning up containers...", file=sys.stderr)
            for ff in task_map:
                ff.cancel()
            try:
                _kill_amp_containers()
            except KeyboardInterrupt:
                print("Force exit.", file=sys.stderr)
                os._exit(1)
            print("Cleanup complete.", file=sys.stderr)

        finally:
            executor.shutdown(wait=False)
            ok = all(r.get("ok", False) for r in results.values())
            (out_dir / "results.json").write_text(
                json.dumps({"ok": ok, "results": list(results.values())}, indent=2)
            )
            if interrupted:
                os._exit(1)
            return 0 if ok else 1

    except KeyboardInterrupt:
        os._exit(1)
