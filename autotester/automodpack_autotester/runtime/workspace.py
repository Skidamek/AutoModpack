"""Server workspace: host-path I/O under the case ``server/`` directory.

Both platforms bind-mount this tree at the server's ``/data``. Relative paths
stay POSIX-style so scenarios never spell a platform path themselves.
"""
from __future__ import annotations

import shutil
from pathlib import Path, PurePosixPath

from ..config import write_generated
from .base import StageTree, TreeMeasurement


def _normalise(rel: str) -> str:
    """One POSIX-style relative path; reject anything that escapes the root."""
    text = str(rel).replace("\\", "/")
    pure = PurePosixPath(text)
    if not pure.parts or pure.is_absolute() or ".." in pure.parts:
        raise ValueError(f"workspace path must stay inside the server root: {rel!r}")
    return pure.as_posix()


def _join(root: Path, rel: str) -> Path:
    return root.joinpath(*PurePosixPath(_normalise(rel)).parts)


class HostPathServerWorkspace:
    """Direct host-path workspace used by Linux and Windows."""

    def __init__(self, root: Path):
        self.root = Path(root)

    def read_text(self, rel: str) -> str:
        return _join(self.root, rel).read_text(encoding="utf-8")

    def write_text(self, rel: str, text: str) -> None:
        path = _join(self.root, rel)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text, encoding="utf-8", newline="")

    def write_bytes(self, rel: str, data: bytes) -> None:
        path = _join(self.root, rel)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(data)

    def copy_in(self, rel: str, source: Path) -> None:
        path = _join(self.root, rel)
        path.parent.mkdir(parents=True, exist_ok=True)
        shutil.copy2(source, path)

    def ensure_dir(self, rel: str) -> None:
        _join(self.root, rel).mkdir(parents=True, exist_ok=True)

    def stage_tree(self, plan: StageTree) -> None:
        root = _join(self.root, plan.root)
        if plan.replace:
            self.remove_tree(plan.root)
        root.mkdir(parents=True, exist_ok=True)
        for entry in plan.files:
            dest = _join(root, entry.path)
            dest.parent.mkdir(parents=True, exist_ok=True)
            if entry.content is not None:
                dest.write_bytes(entry.content)
            elif entry.size_bytes is not None:
                write_generated(dest, entry.fill_name or entry.path, entry.size_bytes)
            else:
                raise ValueError(f"staged file {entry.path!r} has neither content nor sizeBytes")

    def measure_tree(self, rel: str) -> TreeMeasurement:
        root = _join(self.root, rel)
        count = 0
        total = 0
        for path in root.rglob("*"):
            if path.is_file():
                count += 1
                total += path.stat().st_size
        return TreeMeasurement(count, total)

    def exists(self, rel: str) -> bool:
        return _join(self.root, rel).exists()

    def remove_tree(self, rel: str) -> None:
        path = _join(self.root, rel)
        if path.is_dir() and not path.is_symlink():
            shutil.rmtree(path)
        else:
            path.unlink(missing_ok=True)

    def data_mounts(self, cache_volume: str | None) -> tuple[tuple[object, str, bool], ...]:
        if cache_volume:
            for sub in ("mods", "automodpack"):
                (self.root / sub).mkdir(parents=True, exist_ok=True)
            return (
                (cache_volume, "/data", False),
                (self.root / "mods", "/data/mods", False),
                (self.root / "automodpack", "/data/automodpack", False),
            )
        return ((self.root, "/data", False),)

    def host_path(self, rel: str) -> Path:
        return _join(self.root, rel)


__all__ = ["HostPathServerWorkspace"]
