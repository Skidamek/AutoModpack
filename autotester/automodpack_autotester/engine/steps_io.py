"""Filesystem verbs: wait_file, wait_files, verify_files, verify_mods.

Log-based waits are expressed with ``wait_for`` + a ``log`` condition, so no
dedicated verb is needed for them.
"""
from __future__ import annotations

import base64
import hashlib
import json
import re
import stat
from fnmatch import fnmatch
from pathlib import Path

from ..mod_fixtures import assert_valid_mod_fixture, valid_mod_jar_bytes, write_valid_mod_fixture
from .registry import verb
from .util import await_condition, parse_duration


def _while_client_running(ctx, result):
    if result is not None:
        return result
    ctx.assert_client_running()
    return None


def _await_exist(ctx, root, rels, step, msg, default_timeout):
    """Poll until every path in ``rels`` exists under ``root``, or time out."""
    paths = [root / r for r in rels]
    timeout = parse_duration(step.get("timeout"), default=default_timeout)
    await_condition(
        lambda: _while_client_running(ctx, True if all(p.exists() for p in paths) else None),
        timeout,
        step.get("poll"),
        msg,
    )


_CORRUPT_BYTES = b"AutoModpack autotester deliberate corruption\n"


def _client_path(ctx, template, purpose):
    raw = Path(str(ctx.resolve(template)))
    path = ctx.path(raw).resolve()
    root = ctx.game_dir.resolve()
    if raw.is_absolute() or not path.is_relative_to(root):
        raise ValueError(f"{purpose} escapes the client game directory: {path}")
    return path


def _mutate_file(path, action):
    if action == "delete":
        if not path.is_file():
            raise FileNotFoundError(f"cannot delete missing file: {path}")
        try:
            path.unlink()
        except PermissionError:
            path.chmod(stat.S_IMODE(path.stat().st_mode) | stat.S_IWUSR)
            path.unlink()
        return
    if action != "corrupt":
        raise ValueError(f"unknown client-file mutation {action!r}")
    if not path.is_file():
        raise FileNotFoundError(f"cannot corrupt missing file: {path}")
    original = path.read_bytes()
    payload = _CORRUPT_BYTES
    while payload == original:
        payload += b"!"
    original_mode = stat.S_IMODE(path.stat().st_mode)
    made_writable = not original_mode & stat.S_IWUSR
    if made_writable:
        path.chmod(original_mode | stat.S_IWUSR)
    try:
        path.write_bytes(payload)
    finally:
        if made_writable:
            path.chmod(original_mode)


def _active_file(ctx, logical_path):
    """Return the selected generation entry for one canonical logical path."""
    _state, manifest = _read_active_generation(ctx)
    wanted = Path(str(ctx.resolve(logical_path)))
    if wanted.is_absolute() or ".." in wanted.parts:
        raise ValueError(f"active logical path must be relative: {logical_path!r}")
    canonical = wanted.as_posix()
    matches = []
    for group in ((manifest.get("policy", {}) or {}).get("groups", {}) or {}).values():
        if not isinstance(group, dict):
            continue
        entry = (group.get("files", {}) or {}).get(canonical)
        if isinstance(entry, dict):
            matches.append(entry)
    if not matches:
        raise ValueError(f"active generation has no file {canonical!r}")
    identities = {(str(entry.get("sha1", "")), str(entry.get("size", ""))) for entry in matches}
    if len(identities) != 1:
        raise ValueError(f"active generation has conflicting metadata for {canonical!r}")
    expected_hash, raw_size = identities.pop()
    if not re.fullmatch(r"[0-9a-f]{40}", expected_hash):
        raise ValueError(f"active generation has an invalid object hash for {canonical!r}")
    try:
        expected_size = int(raw_size)
    except (TypeError, ValueError) as error:
        raise ValueError(f"active generation has an invalid file size for {canonical!r}") from error
    return canonical, expected_hash, expected_size


def _object_path(ctx, object_hash):
    digest = str(object_hash).lower()
    return ctx.game_dir / "automodpack" / "client" / "data" / "objects" / digest[:2] / digest[2:]


def _claim_fields(ctx, step):
    pack_id = str(ctx.resolve(step["packId"]))
    manifest = ctx.game_dir / "automodpack" / "client" / "preservation" / pack_id / "claims.json"
    if not manifest.is_file():
        return manifest, []
    try:
        claims = json.loads(manifest.read_text(encoding="utf-8")).get("claims", [])
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"preservation manifest is invalid: {manifest}") from error
    if not isinstance(claims, list):
        raise AssertionError(f"preservation manifest has no claim list: {manifest}")
    original_path = step.get("originalPath")
    reason = step.get("reason")
    fixture = ctx.resolve(step.get("fixture"))
    fixture_hash = hashlib.sha1(valid_mod_jar_bytes(fixture, ctx.target.minecraft)).hexdigest() if isinstance(fixture, dict) else None
    content_hash = hashlib.sha1(str(ctx.resolve(step["content"])).encode("utf-8")).hexdigest() if "content" in step else None
    result = []
    for claim in claims:
        if not isinstance(claim, dict):
            continue
        if original_path is not None and claim.get("originalPath") != str(ctx.resolve(original_path)):
            continue
        if reason is not None and claim.get("reason") != str(ctx.resolve(reason)):
            continue
        if fixture_hash is not None and claim.get("objectHash") != fixture_hash:
            continue
        if content_hash is not None and claim.get("objectHash") != content_hash:
            continue
        result.append(claim)
    return manifest, result


@verb("wait_file")
def wait_file(ctx, step):
    template = str(step["path"])
    timeout = parse_duration(step.get("timeout"), default=300)
    await_condition(
        lambda: _while_client_running(ctx, True if (ctx.game_dir / ctx.resolve(template)).exists() else None),
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
            result = True if path.read_text(encoding="utf-8") == expected else None
        except (FileNotFoundError, IsADirectoryError, OSError):
            result = None
        return _while_client_running(ctx, result)

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
        return _while_client_running(ctx, True if ok else None)

    await_condition(_all, timeout, step.get("poll"), "expected mods missing")


def _active_generation_head(manifest: dict) -> dict:
    """The journal entry at the record's head: the generation that is currently active."""
    head = int(manifest.get("journalHead", -1))
    entry = next((entry for entry in (manifest.get("journal") or []) if isinstance(entry, dict) and int(entry.get("seq", -1)) == head), None)
    if entry is None:
        raise ValueError("active generation record has no head journal entry")
    return entry


def _read_active_generation(ctx, expected_patch_notes=None):
    state_path = ctx.game_dir / "automodpack" / "client" / "active-state.json"
    state = json.loads(state_path.read_text(encoding="utf-8"))
    if not isinstance(state, dict):
        raise ValueError("active generation state is not an object")
    modpack_id = state["modpackId"]
    content_token = state["contentToken"]
    if state.get("status") != "ACTIVE" or state.get("schemaVersion") != 1 or not isinstance(modpack_id, str) or not isinstance(content_token, str):
        raise ValueError("active generation state is not committed")
    record_path = ctx.game_dir / "automodpack" / "client" / "records" / content_token / "manifest.json"
    manifest = json.loads(record_path.read_text(encoding="utf-8"))
    policy = manifest.get("policy") if isinstance(manifest, dict) else None
    if not isinstance(policy, dict) or str(manifest.get("contentToken", "")) != content_token or policy.get("modpackId") != modpack_id:
        raise ValueError("active generation state does not match its immutable record")
    if expected_patch_notes is not None and _active_generation_head(manifest).get("notes") != expected_patch_notes:
        raise ValueError("active generation patch notes are not committed")
    return state, manifest


@verb("wait_generation")
def wait_generation(ctx, step):
    """Wait until active-state.json and its immutable generation record are committed."""
    timeout = parse_duration(step.get("timeout"), default=300)
    expected_patch_notes = str(ctx.resolve(step["patchNotes"])) if "patchNotes" in step else None

    def _committed():
        try:
            result = _read_active_generation(ctx, expected_patch_notes)
        except (FileNotFoundError, IsADirectoryError, OSError, TypeError, ValueError, json.JSONDecodeError):
            result = None
        return _while_client_running(ctx, result)

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


@verb("mutate_client_file")
def mutate_client_file(ctx, step):
    """Deliberately delete or corrupt one exact file inside the client game directory."""
    path = _client_path(ctx, step["path"], "client-file mutation")
    _mutate_file(path, str(step["action"]))


@verb("mutate_active_object")
def mutate_active_object(ctx, step):
    """Deliberately delete or corrupt the CAS object expected by an active logical path."""
    _logical_path, expected_hash, _expected_size = _active_file(ctx, step["path"])
    _mutate_file(_object_path(ctx, expected_hash), str(step["action"]))


@verb("assert_client_object")
def assert_client_object(ctx, step):
    """Assert presence and integrity of the CAS object expected by an active logical path."""
    logical_path, expected_hash, expected_size = _active_file(ctx, step["path"])
    path = _object_path(ctx, expected_hash)
    expected_present = step.get("present", True)
    if path.exists() != expected_present:
        raise AssertionError(f"client object for {logical_path!r} presence was {path.exists()}, expected {expected_present}")
    if not expected_present:
        return
    if not path.is_file():
        raise AssertionError(f"client object for {logical_path!r} is not a regular file: {path}")
    valid = path.stat().st_size == expected_size and hashlib.sha1(path.read_bytes()).hexdigest() == expected_hash
    expected_valid = step.get("valid", True)
    if valid != expected_valid:
        raise AssertionError(f"client object for {logical_path!r} validity was {valid}, expected {expected_valid}")


@verb("mutate_preservation_object")
def mutate_preservation_object(ctx, step):
    """Deliberately delete or corrupt the CAS object for one uniquely selected vault claim."""
    manifest, matches = _claim_fields(ctx, step)
    if len(matches) != 1:
        raise AssertionError(f"expected one matching preservation claim under {manifest}, found {len(matches)}")
    object_hash = str(matches[0].get("objectHash", ""))
    if not re.fullmatch(r"[0-9a-f]{40}", object_hash):
        raise AssertionError(f"matching preservation claim has an invalid object hash: {object_hash!r}")
    _mutate_file(_object_path(ctx, object_hash), str(step["action"]))


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
    bootstrap_path = ctx.game_dir / "automodpack" / "automodpack-bootstrap.json"
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


@verb("assert_preservation_claim")
def assert_preservation_claim(ctx, step):
    """Assert filtered vault claims and verify that their claimed CAS bytes are sound.

    Vault mutations (delete, save-copy, restore, repair) run on the client's background executor and surface through
    a render-thread refresh, so a one-shot read right after a UI wait can observe the pre-mutation file. The
    expectation is therefore awaited (default 30s, override with ``timeout``); passing states return immediately,
    so the wait only costs time when the state is actually wrong.
    """
    raw_timeout = step.get("timeout")
    timeout = 30.0 if raw_timeout is None else parse_duration(raw_timeout, default=30.0)
    if timeout <= 0:
        error = _preservation_claim_mismatch(ctx, step)
        if error is not None:
            raise error
        return
    state: dict = {}

    def _pred():
        error = _preservation_claim_mismatch(ctx, step)
        if error is None:
            return True
        state["error"] = error
        ctx.assert_client_running()
        return None

    try:
        await_condition(_pred, timeout, step.get("poll"), f"preservation claim assertion under {step.get('packId')}")
    except TimeoutError as timeout_error:
        raise state.get("error", timeout_error) from timeout_error


def _preservation_claim_mismatch(ctx, step):
    """Return an AssertionError describing the mismatch, or None when the expectation holds."""
    try:
        manifest, matches = _claim_fields(ctx, step)
    except AssertionError as error:
        return error
    expected_present = step.get("present", True)
    expected_count = step.get("count")
    if expected_count is not None:
        if len(matches) != expected_count:
            return AssertionError(f"matching preservation claim count under {manifest} was {len(matches)}, expected {expected_count}")
    elif bool(matches) != expected_present:
        return AssertionError(f"matching preservation claim presence under {manifest} was {bool(matches)}, expected {expected_present}")
    if not expected_present or not matches:
        return None
    expected_valid = step.get("objectValid", True)
    for claim in matches:
        object_hash = str(claim.get("objectHash", ""))
        payload = _object_path(ctx, object_hash)
        try:
            size = int(claim.get("size", -1))
        except (TypeError, ValueError):
            size = -1
        valid = re.fullmatch(r"[0-9a-f]{40}", object_hash) is not None and payload.is_file() and payload.stat().st_size == size and hashlib.sha1(payload.read_bytes()).hexdigest() == object_hash
        if valid != expected_valid:
            return AssertionError(f"preservation object {object_hash!r} validity was {valid}, expected {expected_valid}")
    return None


@verb("assert_generation")
def assert_generation(ctx, step):
    """Assert installed generation metadata without coupling scenarios to Java internals."""
    try:
        _state, manifest = _read_active_generation(ctx)
    except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"active generation metadata is invalid: {error}") from error
    groups = (manifest.get("policy", {}) or {}).get("groups", {}) or {}
    for group_id, requirements in (step.get("groups", {}) or {}).items():
        if group_id not in groups:
            raise AssertionError(f"active generation is missing group {group_id!r}")
        actual = groups[group_id]
        for field, value in (requirements or {}).items():
            if actual.get(field) != value:
                raise AssertionError(f"group {group_id!r} field {field!r}: expected {value!r}, got {actual.get(field)!r}")
    if "patchNotes" in step and _active_generation_head(manifest).get("notes") != ctx.resolve(step["patchNotes"]):
        raise AssertionError("active generation patch notes do not match the scenario")
