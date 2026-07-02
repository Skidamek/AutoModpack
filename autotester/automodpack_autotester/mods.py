"""Resolve a scenario's mod references to local jar files, fetching pinned remote jars.

A mod entry in a ``stage_modpack`` step is either:

  - a string — a path relative to the repo root (a jar already in the tree), or
  - a mapping ``{url, sha512[, name]}`` — a pinned remote jar (e.g. a Modrinth CDN
    URL) downloaded once into ``autotester/.mod-cache/`` and verified against its
    ``sha512``.

``url`` and ``sha512`` may each be a plain value, or a mapping keyed by target id
(``{1.21.1-neoforge: ..., 1.21.10-neoforge: ...}``) when a scenario runs the same
mod against multiple targets that each need a different build - the matching
``target_id`` entry is picked before resolving. This is what lets one scenario file
(e.g. one that stages Sodium) run across several loader-version targets instead of
being copy-pasted per version with only the mod URLs actually differing.

Pinning the ``sha512`` keeps runs reproducible and CI-safe: the file is
content-addressed and cached across runs, and a wrong or corrupt download fails
loudly instead of silently testing the wrong jar. No local, machine-specific
paths leak into the scenarios this way.
"""
from __future__ import annotations

import hashlib
import shutil
import urllib.parse
import urllib.request
from pathlib import Path

from .config import REPO_ROOT

CACHE_DIR = REPO_ROOT / "autotester" / ".mod-cache"


def _per_target(value, target_id, field: str):
    """A plain value, or a {target_id: value} mapping resolved against ``target_id``."""
    if not isinstance(value, dict):
        return value
    if target_id is None or target_id not in value:
        raise ValueError(f"mod entry '{field}' has no entry for target {target_id!r}: {value!r}")
    return value[target_id]


def resolve_mod(entry, resolve=str, target_id=None) -> Path:
    """A scenario mod entry → a local jar Path (downloading + verifying if remote)."""
    if isinstance(entry, dict):
        if "url" not in entry or "sha512" not in entry:
            raise ValueError(f"remote mod entry needs 'url' and 'sha512': {entry!r}")
        url = _per_target(entry["url"], target_id, "url")
        sha512 = _per_target(entry["sha512"], target_id, "sha512")
        return _fetch(resolve(str(url)), str(sha512).lower(), entry.get("name"))

    path = Path(resolve(str(entry)))
    if not path.is_absolute():
        path = REPO_ROOT / path
    path = path.resolve()
    if not path.is_file():
        raise FileNotFoundError(f"stage_modpack mod not found: {path}")
    return path


def _fetch(url: str, sha512: str, name: str | None) -> Path:
    # Keep the jar's real filename (the hash lives in the bucket dir, not the name) so the
    # staged mod is byte-for-byte the same path a human download would produce - scenario log
    # assertions that match on the filename keep working. URL-decode so %2B etc. become '+'.
    filename = name or urllib.parse.unquote(url.rsplit("/", 1)[-1])
    bucket = CACHE_DIR / sha512[:16]
    bucket.mkdir(parents=True, exist_ok=True)
    dest = bucket / filename
    if dest.is_file() and _sha512(dest) == sha512:
        return dest

    tmp = dest.with_name(dest.name + ".tmp")
    with urllib.request.urlopen(url) as response, open(tmp, "wb") as out:
        shutil.copyfileobj(response, out)
    got = _sha512(tmp)
    if got != sha512:
        tmp.unlink(missing_ok=True)
        raise ValueError(f"sha512 mismatch for {url}\n  expected {sha512}\n  got      {got}")
    tmp.replace(dest)
    return dest


def _sha512(path: Path) -> str:
    digest = hashlib.sha512()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            digest.update(chunk)
    return digest.hexdigest()
