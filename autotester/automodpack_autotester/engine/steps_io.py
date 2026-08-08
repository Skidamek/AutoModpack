"""Filesystem verbs: wait_file, wait_files, verify_files, verify_mods.

Log-based waits are expressed with ``wait_for`` + a ``log`` condition, so no
dedicated verb is needed for them.
"""
from __future__ import annotations

import json
from fnmatch import fnmatch
from pathlib import Path

from ..mod_fixtures import assert_valid_mod_fixture, write_valid_mod_fixture
from .registry import verb
from .util import await_condition, parse_duration


def _await_exist(ctx, root, rels, step, msg, default_timeout):
    """Poll until every path in ``rels`` exists under ``root``, or time out."""
    paths = [root / r for r in rels]
    timeout = parse_duration(step.get("timeout"), default=default_timeout)
    await_condition(
        lambda: True if all(p.exists() for p in paths) else None,
        timeout,
        step.get("poll"),
        msg,
    )


@verb("wait_file")
def wait_file(ctx, step):
    template = str(step["path"])
    timeout = parse_duration(step.get("timeout"), default=300)
    await_condition(
        lambda: True if (ctx.game_dir / ctx.resolve(template)).exists() else None,
        timeout,
        step.get("poll"),
        f"file {template} did not appear",
    )


@verb("wait_files")
def wait_files(ctx, step):
    root = ctx.game_dir / ctx.resolve(str(step.get("root", "")))
    rels = [ctx.resolve(str(p)) for p in step.get("paths", [])]
    _await_exist(ctx, root, rels, step, f"files did not all appear under {root}", 120)


@verb("verify_files")
def verify_files(ctx, step):
    """Wait until every file declared in the scenario's ``serverFiles`` is present."""
    root = ctx.game_dir / ctx.resolve(str(step.get("root", "${active_dir}")))
    rels = [str(rel) for rel, _ in ctx.scenario_files]
    _await_exist(ctx, root, rels, step, f"modpack files missing under {root}", 120)


@verb("verify_mods")
def verify_mods(ctx, step):
    if not ctx.expected_mods:
        return
    mod_dir = ctx.game_dir / ctx.resolve(str(step.get("root", "${active_dir}/mods")))
    timeout = parse_duration(step.get("timeout"), default=120)

    def _all():
        mods = {p.name for p in mod_dir.glob("*.jar")} if mod_dir.exists() else set()
        ok = all(any(fnmatch(m, pat) for m in mods) for pat in ctx.expected_mods)
        return True if ok else None

    await_condition(_all, timeout, step.get("poll"), "expected mods missing")


@verb("assert_file_content")
def assert_file_content(ctx, step):
    """Assert the exact UTF-8 contents of a file under the client game directory."""
    path = ctx.path(step["path"])
    expected = str(ctx.resolve(step.get("content", "")))
    try:
        actual = path.read_text(encoding="utf-8")
    except (FileNotFoundError, IsADirectoryError, OSError) as error:
        raise AssertionError(f"file {path} is not readable: {error}") from error
    if actual != expected:
        raise AssertionError(f"file {path} contents differ: expected {expected!r}, got {actual!r}")


@verb("seed_unowned_local_file")
def seed_unowned_local_file(ctx, step):
    """Create deterministic local content used to verify non-pack content survives switching."""
    raw_path = Path(str(ctx.resolve(step["path"])))
    path = ctx.path(raw_path)
    if raw_path.is_absolute() or not path.resolve().is_relative_to(ctx.game_dir.resolve()):
        raise ValueError(f"local fixture path escapes the client game directory: {path}")
    fixture = ctx.resolve(step.get("fixture"))
    if fixture is not None:
        if not isinstance(fixture, dict):
            raise ValueError("unowned local fixture must be a valid mod fixture mapping")
        write_valid_mod_fixture(path, fixture)
        return
    if path.suffix.lower() == ".jar":
        raise ValueError("unowned local .jar files require a valid mod fixture mapping")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(str(ctx.resolve(step.get("content", ""))), encoding="utf-8")


@verb("seed_same_path_conflict")
def seed_same_path_conflict(ctx, step):
    """Seed a valid local mod at the exact path used by a later pack target."""
    fixture = ctx.resolve(step.get("fixture"))
    if not isinstance(fixture, dict):
        raise ValueError("same-path conflict requires a valid mod fixture mapping")
    raw_path = Path(str(ctx.resolve(step["path"])))
    path = ctx.path(raw_path)
    if raw_path.is_absolute() or not path.resolve().is_relative_to(ctx.game_dir.resolve()):
        raise ValueError(f"local fixture path escapes the client game directory: {path}")
    write_valid_mod_fixture(path, fixture)
    ctx.vars["same_path_conflict_path"] = str(ctx.resolve(step["path"]))
    ctx.vars["same_path_conflict_fixture"] = fixture


@verb("assert_mod_fixture")
def assert_mod_fixture(ctx, step):
    """Assert that a path contains the requested valid cross-loader mod fixture."""
    path = ctx.path(step["path"])
    fixture = ctx.resolve(step.get("fixture"))
    if not isinstance(fixture, dict):
        raise ValueError("mod fixture assertion requires a fixture mapping")
    try:
        assert_valid_mod_fixture(path.read_bytes(), fixture)
    except (FileNotFoundError, IsADirectoryError, OSError) as error:
        raise AssertionError(f"mod fixture {path} is not readable: {error}") from error


@verb("assert_quarantine_payload")
def assert_quarantine_payload(ctx, step):
    """Assert that a conflict payload was archived under one pack's quarantine records."""
    pack_id = str(ctx.resolve(step["packId"]))
    fixture = ctx.resolve(step.get("fixture"))
    expected = str(ctx.resolve(step.get("content", "")))
    conflicts = ctx.game_dir / "automodpack" / "client" / "quarantine" / pack_id / "conflicts"
    if not conflicts.is_dir():
        raise AssertionError(f"quarantine conflicts directory is missing: {conflicts}")
    for payload in sorted(conflicts.glob("*/payload")):
        try:
            if isinstance(fixture, dict):
                assert_valid_mod_fixture(payload.read_bytes(), fixture)
                return
            if payload.read_text(encoding="utf-8") == expected:
                return
        except (AssertionError, FileNotFoundError, IsADirectoryError, OSError):
            continue
    raise AssertionError(f"no quarantine payload under {conflicts} matched the expected local content")


@verb("assert_generation")
def assert_generation(ctx, step):
    """Assert installed generation metadata without coupling scenarios to Java internals."""
    state_path = ctx.game_dir / "automodpack" / "client" / "active-state.json"
    try:
        state = json.loads(state_path.read_text(encoding="utf-8"))
        record_path = ctx.game_dir / "automodpack" / "client" / "records" / state["generationId"] / "manifest.json"
        manifest = json.loads(record_path.read_text(encoding="utf-8"))
    except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"active generation metadata is invalid: {error}") from error
    for group_id, requirements in (step.get("groups", {}) or {}).items():
        if group_id not in manifest.get("groups", {}):
            raise AssertionError(f"active generation is missing group {group_id!r}")
        actual = manifest["groups"][group_id]
        for field, value in (requirements or {}).items():
            if actual.get(field) != value:
                raise AssertionError(f"group {group_id!r} field {field!r}: expected {value!r}, got {actual.get(field)!r}")
    if "patchNotes" in step and manifest.get("generation", {}).get("patchNotes") != ctx.resolve(step["patchNotes"]):
        raise AssertionError("active generation patch notes do not match the scenario")
