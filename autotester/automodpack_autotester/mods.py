"""Resolve a scenario's mod references to local jar files, fetching pinned remote jars.

A mod entry in a ``stage_modpack`` step is either:

  - a string — a path relative to the repo root (a jar already in the tree), or
  - a mapping ``{url, sha512[, name]}`` — a pinned remote jar (e.g. a Modrinth CDN
    URL) downloaded once into ``autotester/.mod-cache/`` and verified against its
    ``sha512``.

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


def resolve_mod(entry, resolve=str) -> Path:
    """A scenario mod entry → a local jar Path (downloading + verifying if remote)."""
    if isinstance(entry, dict):
        if "url" not in entry or "sha512" not in entry:
            raise ValueError(f"remote mod entry needs 'url' and 'sha512': {entry!r}")
        return _fetch(resolve(str(entry["url"])), str(entry["sha512"]).lower(), entry.get("name"))

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
