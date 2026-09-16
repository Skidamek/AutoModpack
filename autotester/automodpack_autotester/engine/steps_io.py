"""Filesystem verbs: wait_file, wait_files, verify_files, verify_mods.

Log-based waits are expressed with ``wait_for`` + a ``log`` condition, so no
dedicated verb is needed for them.
"""
from __future__ import annotations

import base64
import json
from fnmatch import fnmatch
from pathlib import Path

from ..mod_fixtures import assert_valid_mod_fixture, write_valid_mod_fixture
from .registry import verb
from .util import await_condition, parse_duration


def _await_exist(ctx, root, rels, step, msg, default_timeout):
    """Poll until every path in ``rels`` exists under ``root``, or time out."""
    paths = [root / r for r in rels]
    timeout = parse_duration(step.get("timeout"), default=default_timeout)
    await_condition(
        lambda: True if all(p.exists() for p in paths) else None,
        timeout,
        step.get("poll"),
        msg,
    )


@verb("wait_file")
def wait_file(ctx, step):
    template = str(step["path"])
    timeout = parse_duration(step.get("timeout"), default=300)
    await_condition(
        lambda: True if (ctx.game_dir / ctx.resolve(template)).exists() else None,
        timeout,
        step.get("poll"),
        f"file {template} did not appear",
    )


@verb("wait_file_content")
def wait_file_content(ctx, step):
    """Wait until a UTF-8 file contains the exact requested content."""
    template = str(step["path"])
    expected = str(ctx.resolve(step.get("content", "")))
    path = ctx.path(template)
    timeout = parse_duration(step.get("timeout"), default=300)

    def _matches():
        try:
            return True if path.read_text(encoding="utf-8") == expected else None
        except (FileNotFoundError, IsADirectoryError, OSError):
            return None

    await_condition(_matches, timeout, step.get("poll"), f"file {template} did not contain the expected content")


@verb("wait_files")
def wait_files(ctx, step):
    root = ctx.game_dir / ctx.resolve(str(step.get("root", "")))
    rels = [ctx.resolve(str(p)) for p in step.get("paths", [])]
    _await_exist(ctx, root, rels, step, f"files did not all appear under {root}", 120)


@verb("verify_files")
def verify_files(ctx, step):
    """Wait until every file declared in the scenario's ``serverFiles`` is present."""
    root = ctx.game_dir / ctx.resolve(str(step.get("root", "${active_dir}")))
    rels = [str(rel) for rel, _ in ctx.scenario_files]
    _await_exist(ctx, root, rels, step, f"modpack files missing under {root}", 120)


@verb("verify_mods")
def verify_mods(ctx, step):
    if not ctx.expected_mods:
        return
    mod_dir = ctx.game_dir / ctx.resolve(str(step.get("root", "${active_dir}/mods")))
    timeout = parse_duration(step.get("timeout"), default=120)

    def _all():
        mods = {p.name for p in mod_dir.glob("*.jar")} if mod_dir.exists() else set()
        ok = all(any(fnmatch(m, pat) for m in mods) for pat in ctx.expected_mods)
        return True if ok else None

    await_condition(_all, timeout, step.get("poll"), "expected mods missing")


def _read_active_generation(ctx, expected_patch_notes=None):
    state_path = ctx.game_dir / "automodpack" / "client" / "active-state.json"
    state = json.loads(state_path.read_text(encoding="utf-8"))
    if not isinstance(state, dict):
        raise ValueError("active generation state is not an object")
    modpack_id = state["modpackId"]
    generation_id = state["generationId"]
    if state.get("status") != "ACTIVE" or not isinstance(modpack_id, str) or not isinstance(generation_id, str):
        raise ValueError("active generation state is not committed")
    record_path = ctx.game_dir / "automodpack" / "client" / "records" / generation_id / "manifest.json"
    manifest = json.loads(record_path.read_text(encoding="utf-8"))
    generation = manifest.get("generation") if isinstance(manifest, dict) else None
    if not isinstance(generation, dict) or manifest.get("modpackId") != modpack_id or generation.get("generationId") != generation_id:
        raise ValueError("active generation state does not match its immutable record")
    if expected_patch_notes is not None and generation.get("patchNotes") != expected_patch_notes:
        raise ValueError("active generation patch notes are not committed")
    return state, manifest


@verb("wait_generation")
def wait_generation(ctx, step):
    """Wait until active-state.json and its immutable generation record are committed."""
    timeout = parse_duration(step.get("timeout"), default=300)
    expected_patch_notes = str(ctx.resolve(step["patchNotes"])) if "patchNotes" in step else None

    def _committed():
        try:
            return _read_active_generation(ctx, expected_patch_notes)
        except (FileNotFoundError, IsADirectoryError, OSError, TypeError, ValueError, json.JSONDecodeError):
            return None

    await_condition(_committed, timeout, step.get("poll"), "active generation state was not committed")


@verb("assert_file_content")
def assert_file_content(ctx, step):
    """Assert the exact UTF-8 contents of a file under the client game directory."""
    path = ctx.path(step["path"])
    expected = str(ctx.resolve(step.get("content", "")))
    try:
        actual = path.read_text(encoding="utf-8")
    except (FileNotFoundError, IsADirectoryError, OSError) as error:
        raise AssertionError(f"file {path} is not readable: {error}") from error
    if actual != expected:
        raise AssertionError(f"file {path} contents differ: expected {expected!r}, got {actual!r}")


@verb("write_file")
def write_file(ctx, step):
    """Write deterministic local content under the client game directory."""
    raw_path = Path(str(ctx.resolve(step["path"])))
    path = ctx.path(raw_path)
    if raw_path.is_absolute() or not path.resolve().is_relative_to(ctx.game_dir.resolve()):
        raise ValueError(f"local file path escapes the client game directory: {path}")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(str(ctx.resolve(step.get("content", ""))), encoding="utf-8")


@verb("assert_bootstrap_import")
def assert_bootstrap_import(ctx, _step):
    """Assert that Preload imported and consumed the real bootstrap file."""
    expected = {
        "origin": str(ctx.vars.get("bootstrap_origin", "")),
        "endpoint": str(ctx.vars.get("bootstrap_endpoint", "")),
        "fingerprint": str(ctx.vars.get("bootstrap_fingerprint", "")),
        "modpackId": str(ctx.vars.get("bootstrap_modpack_id", "")),
        "connectionMode": str(ctx.vars.get("bootstrap_connection_mode", "")),
    }
    if not all(expected.values()):
        raise AssertionError("bootstrap expectations were not captured by seed_bootstrap")
    bootstrap_path = ctx.game_dir / "automodpack-bootstrap.json"
    if bootstrap_path.exists():
        raise AssertionError(f"Preload did not delete imported bootstrap file: {bootstrap_path}")
    try:
        client_config = json.loads((ctx.game_dir / "automodpack" / "client-config.json").read_text(encoding="utf-8"))
        known_hosts = json.loads((ctx.game_dir / "automodpack" / "client" / "data" / "known-hosts.json").read_text(encoding="utf-8"))
        connection = json.loads((ctx.game_dir / "automodpack" / "client" / "data" / "packs" / expected["modpackId"] / "connection.json").read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"Preload did not persist bootstrap state: {error}") from error
    if client_config.get("selectedModpackId") != expected["modpackId"]:
        raise AssertionError(f"bootstrap selectedModpackId mismatch: expected {expected['modpackId']!r}, got {client_config.get('selectedModpackId')!r}")
    host = known_hosts.get("hosts", {}).get(expected["origin"])
    normalized_expected_fingerprint = expected["fingerprint"].replace(":", "").lower()
    if (not isinstance(host, dict) or host.get("reason") != "SEED"
            or str(host.get("fingerprint", "")).replace(":", "").lower() != normalized_expected_fingerprint):
        raise AssertionError(f"bootstrap trust pin was not seeded for {expected['origin']!r}: {host!r}")
    actual = connection.get("connection", {})
    for field in ("origin", "endpoint", "connectionMode"):
        if actual.get(field) != expected[field]:
            raise AssertionError(f"bootstrap connection {field} mismatch: expected {expected[field]!r}, got {actual.get(field)!r}")


@verb("assert_authenticated_secret")
def assert_authenticated_secret(ctx, _step):
    """Assert that authenticated login persisted the same non-anonymous secret on both sides."""
    modpack_id = str(ctx.vars.get("bootstrap_modpack_id", ""))
    origin = str(ctx.vars.get("bootstrap_origin", ""))
    if not modpack_id or not origin:
        raise AssertionError("bootstrap identity was not captured before authenticated secret assertion")
    connection_path = ctx.game_dir / "automodpack" / "client" / "data" / "packs" / modpack_id / "connection.json"
    server_secrets_path = ctx.server_dir / "automodpack" / "server" / "secrets.json"
    try:
        connection = json.loads(connection_path.read_text(encoding="utf-8"))
        server_secrets = json.loads(server_secrets_path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"authenticated secret state is not readable: {error}") from error
    client_secret = (connection.get("secrets", {}) or {}).get(origin)
    if not isinstance(client_secret, dict):
        raise AssertionError("authenticated login did not persist a client secret for the bootstrap origin")
    value = client_secret.get("secret")
    timestamp = client_secret.get("timestamp")
    anonymous = base64.urlsafe_b64encode(bytes(32)).decode("ascii").rstrip("=")
    if not isinstance(value, str) or not value or value == anonymous or not isinstance(timestamp, (int, float)) or timestamp <= 0:
        raise AssertionError("persisted client secret is missing, anonymous, or has no valid timestamp")
    matching_server_secrets = [entry for entry in (server_secrets.get("secrets", {}) or {}).values() if isinstance(entry, dict) and entry.get("secret") == value]
    if not matching_server_secrets:
        raise AssertionError("server did not persist the secret issued during authenticated login")
    ctx.vars["authenticated_secret_persisted"] = True


@verb("seed_unowned_local_file")
def seed_unowned_local_file(ctx, step):
    """Create deterministic local content used to verify non-pack content survives switching."""
    raw_path = Path(str(ctx.resolve(step["path"])))
    path = ctx.path(raw_path)
    if raw_path.is_absolute() or not path.resolve().is_relative_to(ctx.game_dir.resolve()):
        raise ValueError(f"local fixture path escapes the client game directory: {path}")
    fixture = ctx.resolve(step.get("fixture"))
    if fixture is not None:
        if not isinstance(fixture, dict):
            raise ValueError("unowned local fixture must be a valid mod fixture mapping")
        write_valid_mod_fixture(path, fixture, ctx.target.minecraft)
        return
    if path.suffix.lower() == ".jar":
        raise ValueError("unowned local .jar files require a valid mod fixture mapping")
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(str(ctx.resolve(step.get("content", ""))), encoding="utf-8")


@verb("seed_same_path_conflict")
def seed_same_path_conflict(ctx, step):
    """Seed a valid local mod at the exact path used by a later pack target."""
    fixture = ctx.resolve(step.get("fixture"))
    if not isinstance(fixture, dict):
        raise ValueError("same-path conflict requires a valid mod fixture mapping")
    raw_path = Path(str(ctx.resolve(step["path"])))
    path = ctx.path(raw_path)
    if raw_path.is_absolute() or not path.resolve().is_relative_to(ctx.game_dir.resolve()):
        raise ValueError(f"local fixture path escapes the client game directory: {path}")
    write_valid_mod_fixture(path, fixture, ctx.target.minecraft)
    ctx.vars["same_path_conflict_path"] = str(ctx.resolve(step["path"]))
    ctx.vars["same_path_conflict_fixture"] = fixture


@verb("seed_mod_fixture")
def seed_mod_fixture(ctx, step):
    """Place a valid deterministic mod archive in the ordinary game mods directory."""
    fixture = ctx.resolve(step.get("fixture"))
    if not isinstance(fixture, dict):
        raise ValueError("mod fixture requires a valid fixture mapping")
    raw_path = Path(str(ctx.resolve(step["path"])))
    path = ctx.path(raw_path)
    if raw_path.is_absolute() or not path.resolve().is_relative_to(ctx.game_dir.resolve()):
        raise ValueError(f"mod fixture path escapes the client game directory: {path}")
    if raw_path.parts[:1] != ("mods",) or raw_path.suffix.lower() != ".jar":
        raise ValueError("mod fixtures must use a .jar path under the ordinary mods directory")
    write_valid_mod_fixture(path, fixture, ctx.target.minecraft)


@verb("assert_mod_fixture")
def assert_mod_fixture(ctx, step):
    """Assert that a path contains the requested valid cross-loader mod fixture."""
    path = ctx.path(step["path"])
    fixture = ctx.resolve(step.get("fixture"))
    if not isinstance(fixture, dict):
        raise ValueError("mod fixture assertion requires a fixture mapping")
    try:
        assert_valid_mod_fixture(path.read_bytes(), fixture, ctx.target.minecraft)
    except (FileNotFoundError, IsADirectoryError, OSError) as error:
        raise AssertionError(f"mod fixture {path} is not readable: {error}") from error


@verb("assert_quarantine_payload")
def assert_quarantine_payload(ctx, step):
    """Assert that a conflict payload was archived under one pack's quarantine records."""
    pack_id = str(ctx.resolve(step["packId"]))
    fixture = ctx.resolve(step.get("fixture"))
    expected = str(ctx.resolve(step.get("content", "")))
    conflicts = ctx.game_dir / "automodpack" / "client" / "quarantine" / pack_id / "conflicts"
    if not conflicts.is_dir():
        raise AssertionError(f"quarantine conflicts directory is missing: {conflicts}")
    for payload in sorted(conflicts.glob("*/payload")):
        try:
            if isinstance(fixture, dict):
                assert_valid_mod_fixture(payload.read_bytes(), fixture, ctx.target.minecraft)
                return
            if payload.read_text(encoding="utf-8") == expected:
                return
        except (AssertionError, FileNotFoundError, IsADirectoryError, OSError):
            continue
    raise AssertionError(f"no quarantine payload under {conflicts} matched the expected local content")


@verb("assert_generation")
def assert_generation(ctx, step):
    """Assert installed generation metadata without coupling scenarios to Java internals."""
    try:
        _state, manifest = _read_active_generation(ctx)
    except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"active generation metadata is invalid: {error}") from error
    for group_id, requirements in (step.get("groups", {}) or {}).items():
        if group_id not in manifest.get("groups", {}):
            raise AssertionError(f"active generation is missing group {group_id!r}")
        actual = manifest["groups"][group_id]
        for field, value in (requirements or {}).items():
            if actual.get(field) != value:
                raise AssertionError(f"group {group_id!r} field {field!r}: expected {value!r}, got {actual.get(field)!r}")
    if "patchNotes" in step and manifest.get("generation", {}).get("patchNotes") != ctx.resolve(step["patchNotes"]):
        raise AssertionError("active generation patch notes do not match the scenario")
