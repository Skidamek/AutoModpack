#!/usr/bin/env python3
"""Refresh the bundled Mozilla OneCRL snapshot.

The output is the human-edited source JSON next to this script. The Gradle build copies it to /onecrl.json at the jar root.
"""

import argparse
import json
import sys
import urllib.request
from pathlib import Path

PAGE_LIMIT = 10_000
SOURCE_URL = f"https://firefox.settings.services.mozilla.com/v1/buckets/security-state/collections/onecrl/records?_limit={PAGE_LIMIT}"
HERE = Path(__file__).resolve().parent
DEFAULT_OUTPUT = HERE / "onecrl.json"


def fetch_records():
    request = urllib.request.Request(SOURCE_URL, headers={"Accept": "application/json", "User-Agent": "AutoModpack-OneCRL-snapshot"})
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.load(response).get("data", [])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", type=Path, default=DEFAULT_OUTPUT)
    args = parser.parse_args()

    records = fetch_records()
    if len(records) >= PAGE_LIMIT:
        sys.exit(f"OneCRL returned {len(records)} records, at the {PAGE_LIMIT} page limit; add pagination before writing a truncated snapshot")

    issuer_serial = set()
    spki = set()
    for record in records:
        if not record.get("enabled", True):
            continue
        issuer = record.get("issuerName")
        serial = record.get("serialNumber")
        if issuer and serial:
            issuer_serial.add((issuer, serial))
        pub_key_hash = record.get("pubKeyHash")
        if pub_key_hash:
            spki.add(pub_key_hash)

    if not issuer_serial and not spki:
        sys.exit("OneCRL returned no enabled records; refusing to overwrite the snapshot")

    snapshot = {"is": sorted([list(pair) for pair in issuer_serial]), "spki": sorted(spki)}
    args.output.parent.mkdir(parents=True, exist_ok=True)
    args.output.write_text(json.dumps(snapshot, separators=(",", ":")) + "\n", encoding="utf-8")
    print(f"Wrote {len(issuer_serial)} issuer/serial and {len(spki)} SPKI records to {args.output}")


if __name__ == "__main__":
    main()
