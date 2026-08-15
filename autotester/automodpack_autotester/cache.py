from __future__ import annotations

import hashlib
import os
import secrets
from dataclasses import dataclass
from pathlib import Path


@dataclass(frozen=True)
class AssetDeduplication:
    linked_files: int = 0
    reclaimed_bytes: int = 0
    invalid_objects: int = 0
    link_failures: int = 0


def deduplicate_asset_objects(cache_root: Path) -> AssetDeduplication:
    """Hard-link immutable Minecraft asset objects across isolated target caches."""
    objects: dict[str, list[Path]] = {}
    if not cache_root.is_dir():
        return AssetDeduplication()
    for target in cache_root.iterdir():
        target_objects = target / "assets" / "objects"
        if not target.is_dir() or not target_objects.is_dir():
            continue
        for path in target_objects.glob("*/*"):
            digest = path.name.lower()
            if path.is_file() and len(digest) == 40 and all(character in "0123456789abcdef" for character in digest) and path.parent.name == digest[:2]:
                objects.setdefault(digest, []).append(path)

    linked_files = reclaimed_bytes = invalid_objects = link_failures = 0
    for digest, paths in objects.items():
        if len(paths) < 2:
            continue
        identities = {(stat.st_dev, stat.st_ino) for stat in (path.stat() for path in paths)}
        if len(identities) == 1:
            continue
        canonical = next((path for path in paths if _sha1(path) == digest), None)
        if canonical is None:
            invalid_objects += len(paths)
            continue
        canonical_stat = canonical.stat()
        for duplicate in paths:
            duplicate_stat = duplicate.stat()
            if duplicate_stat.st_dev == canonical_stat.st_dev and duplicate_stat.st_ino == canonical_stat.st_ino:
                continue
            temporary = duplicate.with_name(f".{duplicate.name}.link-{secrets.token_hex(4)}")
            try:
                os.link(canonical, temporary)
                os.replace(temporary, duplicate)
            except OSError:
                temporary.unlink(missing_ok=True)
                link_failures += 1
                continue
            linked_files += 1
            reclaimed_bytes += duplicate_stat.st_size
    return AssetDeduplication(linked_files, reclaimed_bytes, invalid_objects, link_failures)


def _sha1(path: Path) -> str:
    with path.open("rb") as stream:
        return hashlib.file_digest(stream, "sha1").hexdigest()
