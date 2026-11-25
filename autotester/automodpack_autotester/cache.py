"""Fast hardlink/copy tree materialization, cross-target deduplication, and output pruning."""
from __future__ import annotations

import hashlib
import os
import shutil
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class DedupStats:
    linked_files: int = 0
    reclaimed_bytes: int = 0
    failures: int = 0


def link_or_copy(src: Path, dest: Path) -> None:
    """Hardlink ``src`` to ``dest`` when possible (zero bytes copied), falling back to copy2."""
    dest.parent.mkdir(parents=True, exist_ok=True)
    if dest.exists() or dest.is_symlink():
        return
    try:
        os.link(src, dest)
    except OSError:
        shutil.copy2(src, dest, follow_symlinks=False)


def materialize_tree(
    src: Path,
    dest: Path,
    *,
    hardlink_dirs: frozenset[str] = frozenset(),
    skip: frozenset[str] = frozenset(),
    hardlink_all: bool = False,
) -> None:
    """Materialize ``src`` into ``dest`` without overwriting existing files.

    Immutable directories (e.g. ``libraries``) and jars are hardlinked for near-instant
    setup and zero extra disk usage; mutable files (configs, world) are copied.
    """
    dest.mkdir(parents=True, exist_ok=True)
    for child in src.iterdir():
        if child.name in skip:
            continue
        target = dest / child.name
        if child.is_dir():
            materialize_tree(
                child,
                target,
                hardlink_dirs=hardlink_dirs,
                skip=frozenset(),
                hardlink_all=hardlink_all or (child.name in hardlink_dirs),
            )
        elif not target.exists():
            if hardlink_all or child.suffix == ".jar":
                link_or_copy(child, target)
            else:
                shutil.copy2(child, target, follow_symlinks=False)


def deduplicate_tree(root: Path, subdirs: tuple[str, ...] = ("assets/objects", "libraries")) -> DedupStats:
    """Hardlink identical files across per-target subdirectories under ``root``."""
    if not root.is_dir():
        return DedupStats()
    seen: dict[tuple[int, str], Path] = {}
    linked = 0
    reclaimed = 0
    failures = 0
    for target_dir in sorted(p for p in root.iterdir() if p.is_dir()):
        for rel in subdirs:
            base = target_dir / rel
            if not base.is_dir():
                continue
            for path in base.rglob("*"):
                if not path.is_file() or path.is_symlink():
                    continue
                try:
                    st = path.stat()
                    digest = _sha256_fast(path)
                except OSError:
                    failures += 1
                    continue
                key = (st.st_size, digest)
                canonical = seen.get(key)
                if canonical is None:
                    seen[key] = path
                    continue
                try:
                    if os.path.samefile(canonical, path):
                        continue
                    tmp = path.with_name(f".{path.name}.dedup")
                    tmp.unlink(missing_ok=True)
                    os.link(canonical, tmp)
                    os.replace(tmp, path)
                    linked += 1
                    reclaimed += st.st_size
                except OSError:
                    failures += 1
    return DedupStats(linked, reclaimed, failures)


def _sha256_fast(path: Path) -> str:
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha256").hexdigest()


def prune_passed_case(case_dir: Path) -> None:
    """Keep logs and screenshots for a passing case, deleting heavy server/client game trees."""
    for heavy in ("server", "clients"):
        target = case_dir / heavy
        if target.is_dir():
            shutil.rmtree(target, ignore_errors=True)
