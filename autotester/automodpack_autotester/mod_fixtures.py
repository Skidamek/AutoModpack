"""Small deterministic mod archives used by cross-loader autotester scenarios."""
from __future__ import annotations

import io
import json
import re
import tomllib
import zipfile
from pathlib import Path


DEFAULT_MINECRAFT_VERSION = "1.20.1"
_PACK_METADATA_RECEIPTS = {
    "1.18.2": {"pack_format": 8},
    "1.19.2": {"pack_format": 9},
    "1.20.1": {"pack_format": 15},
    "1.21": {"pack_format": 34},
    "1.21.1": {"pack_format": 34},
    "1.21.4": {"pack_format": 46},
    "1.21.5": {"pack_format": 55},
    "1.21.8": {"pack_format": 64},
    "1.21.9": {"min_format": 69, "max_format": 69},
    "1.21.10": {"min_format": 69, "max_format": 69},
    "1.21.11": {"min_format": 75, "max_format": 75},
    "26.1": {"min_format": 84, "max_format": 84},
    "26.1.2": {"min_format": 84, "max_format": 84},
    "26.2": {"min_format": 88, "max_format": 88},
}


def pack_metadata_for(minecraft_version: str) -> dict:
    """Return the authoritative resource-pack metadata shape for a target.

    Runtime fixture writers must pass ``Target.minecraft``. Direct unit calls
    may omit it and use the documented 1.20.1 default in the public helpers.
    """
    version = str(minecraft_version)
    try:
        format_fields = _PACK_METADATA_RECEIPTS[version]
    except KeyError as error:
        raise ValueError(f"no pack metadata receipt for Minecraft {version!r}") from error
    return {"pack": {"description": "AutoModpack autotest fixture", **format_fields}}


def valid_mod_jar_bytes(fixture: dict, minecraft_version: str = DEFAULT_MINECRAFT_VERSION) -> bytes:
    """Build a harmless archive recognized by Fabric, Forge, and NeoForge."""
    if not isinstance(fixture, dict):
        raise ValueError("mod fixture must be a mapping")
    mod_id = str(fixture.get("modId", "amp_autotest_fixture"))
    version = str(fixture.get("version", "1.0.0"))
    marker = str(fixture.get("marker", "fixture"))
    if re.fullmatch(r"[a-z0-9][a-z0-9_-]*", mod_id) is None or re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9.+_-]*", version) is None:
        raise ValueError("mod fixture requires loader-safe modId and version")
    fabric = {
        "schemaVersion": 1,
        "id": mod_id,
        "version": version,
        "name": "AutoModpack autotest fixture",
        "environment": "*",
        "depends": {"minecraft": "*"},
    }
    pack = pack_metadata_for(minecraft_version)
    def loader_metadata(loader: str) -> bytes:
        return f'''modLoader = "{loader}"
loaderVersion = "[1,)"
license = "MIT"

[[mods]]
modId = "{mod_id}"
version = "{version}"
displayName = "AutoModpack autotest fixture"
description = "Harmless metadata-only release-gate fixture"
'''.encode("utf-8")
    entries = {
        "META-INF/mods.toml": loader_metadata("lowcodefml"),
        "META-INF/neoforge.mods.toml": loader_metadata("lowcodefml"),
        "data/automodpack-autotest-fixture.txt": marker.encode("utf-8"),
        "fabric.mod.json": (json.dumps(fabric, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"),
        "pack.mcmeta": (json.dumps(pack, sort_keys=True, separators=(",", ":")) + "\n").encode("utf-8"),
    }
    output = io.BytesIO()
    with zipfile.ZipFile(output, "w", compression=zipfile.ZIP_STORED) as archive:
        for name, content in sorted(entries.items()):
            info = zipfile.ZipInfo(name, date_time=(2020, 1, 1, 0, 0, 0))
            info.compress_type = zipfile.ZIP_STORED
            info.external_attr = 0o644 << 16
            archive.writestr(info, content)
    return output.getvalue()


def write_valid_mod_fixture(path: Path, fixture: dict, minecraft_version: str = DEFAULT_MINECRAFT_VERSION) -> None:
    """Write one fixture; runtime callers pass their target, unit callers use 1.20.1."""
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_bytes(valid_mod_jar_bytes(fixture, minecraft_version))


def assert_valid_mod_fixture(payload: bytes, fixture: dict, minecraft_version: str = DEFAULT_MINECRAFT_VERSION) -> None:
    """Validate the metadata and marker that make a fixture a real mod archive."""
    expected_id = str(fixture.get("modId", "amp_autotest_fixture"))
    expected_version = str(fixture.get("version", "1.0.0"))
    expected_marker = str(fixture.get("marker", "fixture"))
    try:
        with zipfile.ZipFile(io.BytesIO(payload)) as archive:
            fabric = json.loads(archive.read("fabric.mod.json"))
            forge = tomllib.loads(archive.read("META-INF/mods.toml").decode("utf-8"))
            neoforge = tomllib.loads(archive.read("META-INF/neoforge.mods.toml").decode("utf-8"))
            pack = json.loads(archive.read("pack.mcmeta"))
            marker = archive.read("data/automodpack-autotest-fixture.txt").decode("utf-8")
    except (KeyError, OSError, UnicodeDecodeError, ValueError, json.JSONDecodeError, tomllib.TOMLDecodeError, zipfile.BadZipFile) as error:
        raise AssertionError(f"fixture is not a valid cross-loader mod archive: {error}") from error
    if fabric.get("id") != expected_id or fabric.get("version") != expected_version:
        raise AssertionError("fixture Fabric metadata does not match the expected mod identity")
    if pack != pack_metadata_for(minecraft_version):
        raise AssertionError(f"fixture pack metadata does not match Minecraft {minecraft_version}")
    for metadata in (forge, neoforge):
        if metadata.get("modLoader") != "lowcodefml" or metadata.get("loaderVersion") != "[1,)":
            raise AssertionError("fixture Forge metadata must use the no-code loader")
        mods = metadata.get("mods", [])
        if not mods or mods[0].get("modId") != expected_id or mods[0].get("version") != expected_version:
            raise AssertionError("fixture Forge metadata does not match the expected mod identity")
    if marker != expected_marker:
        raise AssertionError(f"fixture marker differs: expected {expected_marker!r}, got {marker!r}")
