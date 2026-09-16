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


def write_strings(encoder: CanonicalEncoder, values):
    values = sorted(values)
    encoder.integer(len(values))
    for value in values:
        encoder.string(value)
