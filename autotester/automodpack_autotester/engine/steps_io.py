"""Filesystem verbs: wait_file, wait_files, verify_files, verify_mods.

Log-based waits are expressed with ``wait_for`` + a ``log`` condition, so no
dedicated verb is needed for them.
"""
from __future__ import annotations

from fnmatch import fnmatch

from .registry import verb
from .util import await_condition, parse_duration


@verb("wait_file")
def wait_file(ctx, step):
    rel = ctx.resolve(str(step["path"]))
    p = ctx.game_dir / rel
    timeout = parse_duration(step.get("timeout"), default=300)
    await_condition(
        lambda: p if p.exists() else None,
        timeout,
        step.get("poll"),
        f"file {rel} did not appear",
    )


@verb("wait_files")
def wait_files(ctx, step):
    root = ctx.game_dir / ctx.resolve(str(step.get("root", "")))
    paths = [ctx.resolve(str(p)) for p in step.get("paths", [])]
    timeout = parse_duration(step.get("timeout"), default=120)

    def _all():
        return True if all((root / p).exists() for p in paths) else None

    missing_msg = f"files did not all appear under {root}"
    await_condition(_all, timeout, step.get("poll"), missing_msg)


@verb("verify_files")
def verify_files(ctx, step):
    """Wait until every file declared in the scenario's ``serverFiles`` is present."""
    root = ctx.game_dir / ctx.resolve(str(step.get("root", "automodpack/modpacks/${modpack}")))
    rels = [str(rel) for rel, _ in ctx.scenario_files]
    timeout = parse_duration(step.get("timeout"), default=120)

    def _all():
        return True if all((root / r).exists() for r in rels) else None

    await_condition(_all, timeout, step.get("poll"), f"modpack files missing under {root}")


@verb("verify_mods")
def verify_mods(ctx, step):
    if not ctx.expected_mods:
        return
    mod_dir = ctx.game_dir / ctx.resolve(str(step.get("root", "automodpack/modpacks/${modpack}/mods")))
    timeout = parse_duration(step.get("timeout"), default=120)

    def _all():
        mods = {p.name for p in mod_dir.glob("*.jar")} if mod_dir.exists() else set()
        ok = all(any(fnmatch(m, pat) for m in mods) for pat in ctx.expected_mods)
        return True if ok else None

    await_condition(_all, timeout, step.get("poll"), "expected mods missing")
