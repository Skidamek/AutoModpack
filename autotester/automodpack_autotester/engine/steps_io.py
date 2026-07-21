"""Filesystem verbs: wait_file, wait_files, verify_files, verify_mods.

Log-based waits are expressed with ``wait_for`` + a ``log`` condition, so no
dedicated verb is needed for them.
"""
from __future__ import annotations

from fnmatch import fnmatch

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
    root = ctx.game_dir / ctx.resolve(str(step.get("root", "${modpack_dir}")))
    rels = [str(rel) for rel, _ in ctx.scenario_files]
    _await_exist(ctx, root, rels, step, f"modpack files missing under {root}", 120)


@verb("verify_mods")
def verify_mods(ctx, step):
    if not ctx.expected_mods:
        return
    mod_dir = ctx.game_dir / ctx.resolve(str(step.get("root", "${modpack_dir}/mods")))
    timeout = parse_duration(step.get("timeout"), default=120)

    def _all():
        mods = {p.name for p in mod_dir.glob("*.jar")} if mod_dir.exists() else set()
        ok = all(any(fnmatch(m, pat) for m in mods) for pat in ctx.expected_mods)
        return True if ok else None

    await_condition(_all, timeout, step.get("poll"), "expected mods missing")
