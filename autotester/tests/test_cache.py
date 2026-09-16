from __future__ import annotations

import hashlib
import os

from automodpack_autotester.cache import deduplicate_asset_objects


def _asset(cache_root, target, payload):
    digest = hashlib.sha1(payload).hexdigest()
    path = cache_root / target / "assets" / "objects" / digest[:2] / digest
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(payload)
    return path


def test_asset_cache_deduplicates_verified_content_and_is_idempotent(tmp_path):
    first = _asset(tmp_path, "fabric", b"shared asset")
    second = _asset(tmp_path, "forge", b"shared asset")

    result = deduplicate_asset_objects(tmp_path)

    assert result.linked_files == 1
    assert result.reclaimed_bytes == len(b"shared asset")
    assert result.invalid_objects == 0
    assert result.link_failures == 0
    assert os.path.samestat(first.stat(), second.stat())
    assert deduplicate_asset_objects(tmp_path).linked_files == 0


def test_asset_cache_does_not_propagate_unverified_content(tmp_path):
    digest = hashlib.sha1(b"expected").hexdigest()
    for target in ("fabric", "forge"):
        path = tmp_path / target / "assets" / "objects" / digest[:2] / digest
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(b"corrupt")

    result = deduplicate_asset_objects(tmp_path)

    assert result.linked_files == 0
    assert result.invalid_objects == 2
