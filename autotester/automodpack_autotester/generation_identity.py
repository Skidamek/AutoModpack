"""Cross-language generation identity encoding used by staged test fixtures.

The Java ``CanonicalEncoder`` is the production implementation. This module is
the autotester's protocol adapter; keeping it isolated makes the parity boundary
explicit instead of hiding a second identity implementation in the Docker runner.
"""

from __future__ import annotations

import hashlib
import struct


class CanonicalEncoder:
    def __init__(self):
        self.data = bytearray()

    def string(self, value: str | None):
        if value is None:
            self.data.append(0)
            return self
        encoded = value.encode("utf-8")
        self.data.append(1)
        self.data.extend(struct.pack(">i", len(encoded)))
        self.data.extend(encoded)
        return self

    def integer(self, value: int):
        self.data.extend(struct.pack(">i", value))
        return self

    def long(self, value: int):
        self.data.extend(struct.pack(">q", value))
        return self

    def boolean(self, value: bool):
        self.data.append(1 if value else 0)
        return self

    def digest(self) -> str:
        return hashlib.sha1(self.data).hexdigest()


def content_token(files: dict[str, tuple[str, int]]) -> str:
    """The canonical content identity of one served file set (Java ContentTree.token)."""
    encoder = CanonicalEncoder().string("automodpack-content-v1").integer(len(files))
    for path in sorted(files):
        sha1, size = files[path]
        encoder.string(path).string(str(sha1).lower()).long(int(size))
    return encoder.digest()


def ownership_ledger_digest(modpack_id: str, entries: list[dict]) -> str:
    """The canonical digest of one ownership ledger (Java OwnershipLedger.digest)."""
    encoder = CanonicalEncoder().string("automodpack-ownership-ledger-v1").string(modpack_id).integer(len(entries))
    for entry in sorted(entries, key=lambda entry: entry["logicalPath"]):
        hashes = sorted(entry["historicalHashes"], key=lambda content: (str(content["sha1"]).lower(), int(content["size"])))
        groups = sorted(entry["historicalGroupIds"])
        encoder.string(entry["logicalPath"]).integer(len(hashes))
        for content in hashes:
            encoder.string(str(content["sha1"]).lower()).long(int(content["size"]))
        encoder.integer(len(groups))
        for group in groups:
            encoder.string(group)
        encoder.string(entry["currentStatus"])
    return encoder.digest()
