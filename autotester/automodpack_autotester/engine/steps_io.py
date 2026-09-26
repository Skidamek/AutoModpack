"""Filesystem verbs: wait_file, verify_files.

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
    for category in ((manifest.get("policy", {}) or {}).get("categories", {}) or {}).values():
        if not isinstance(category, dict):
            continue
        for group in category.values():
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


@verb("verify_files")
def verify_files(ctx, step):
    """Wait until every file declared in the scenario's ``serverFiles`` is present."""
    root = ctx.game_dir / ctx.resolve(str(step.get("root", "${active_dir}")))
    rels = [str(hosted.path) for hosted in ctx.scenario_files]
    _await_exist(ctx, root, rels, step, f"modpack files missing under {root}", 120)


def _mirror_entry(ctx, modpack_id, content_token):
    """One generation's journal entry, read from the client's journal mirror."""
    mirror_path = ctx.game_dir / "automodpack" / "client" / "history" / modpack_id / "journal.jsonl"
    with mirror_path.open(encoding="utf-8") as handle:
        for line in handle:
            line = line.strip()
            if not line:
                continue
            entry = json.loads(line)
            if isinstance(entry, dict) and str(entry.get("contentToken", "")) == content_token:
                return entry
    raise ValueError(f"journal mirror has no entry for the active generation {content_token!r}")


def _active_generation_notes(ctx, modpack_id, content_token):
    """The patch notes of one generation's journal entry, read from the client's journal mirror."""
    return str(_mirror_entry(ctx, modpack_id, content_token).get("notes", ""))


def _read_active_generation(ctx, expected_patch_notes=None):
    state_path = ctx.game_dir / "automodpack" / "client" / "active-state.json"
    state = json.loads(state_path.read_text(encoding="utf-8"))
    if not isinstance(state, dict):
        raise ValueError("active generation state is not an object")
    modpack_id = state["modpackId"]
    content_token = state["contentToken"]
    if state.get("status") != "ACTIVE" or not isinstance(modpack_id, str) or not isinstance(content_token, str):
        raise ValueError("active generation state is not committed")
    entry = _mirror_entry(ctx, modpack_id, content_token)
    policy_sha1 = str(entry.get("policySha1", ""))
    policy_path = _object_path(ctx, policy_sha1)
    policy = json.loads(policy_path.read_text(encoding="utf-8"))
    manifest = {"contentToken": content_token, "policySha1": policy_sha1, "createdAt": entry.get("createdAt"), "policy": policy}
    if not isinstance(policy, dict) or policy.get("modpackId") != modpack_id or state.get("ownershipLedger", {}).get("modpackId") != modpack_id:
        raise ValueError("active generation state does not match its mirror and policy document")
    if expected_patch_notes is not None and _active_generation_notes(ctx, modpack_id, content_token) != expected_patch_notes:
        raise ValueError("active generation patch notes are not committed")
    return state, manifest


@verb("wait_generation")
def wait_generation(ctx, step):
    """Wait until active-state.json, its mirror entry, and its policy document are committed."""
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


@verb("mutate_timeline_object")
def mutate_timeline_object(ctx, step):
    """Deliberately delete or corrupt the CAS object of one uniquely hashed timeline-tracked file."""
    tracked = _timeline_tracked(ctx, str(step["path"]))
    hashes = {str(entry["sha1"]).lower() for entry in tracked}
    if len(hashes) != 1:
        raise AssertionError(f"expected exactly one tracked version of {step['path']!r} in the timeline, found {len(hashes)}")
    object_hash = hashes.pop()
    _mutate_file(_object_path(ctx, object_hash), str(step["action"]))


def _timeline_tracked(ctx, logical_path):
    """Every timeline tree entry of one game-dir path, oldest snapshot first; missing journal reads as an empty timeline."""
    logical_path = logical_path.lstrip("/")
    client_dir = ctx.game_dir / "automodpack" / "client"
    journal = client_dir / "state-history" / "journal.jsonl"
    tracked = []
    seen_trees = set()
    if journal.is_file():
        for line in journal.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            snapshot = json.loads(line)
            tree_sha1 = str(snapshot.get("treeSha1", "")).lower()
            if not re.fullmatch(r"[0-9a-f]{40}", tree_sha1) or tree_sha1 in seen_trees:
                continue
            seen_trees.add(tree_sha1)
            tree_file = client_dir / "state-history" / "trees" / tree_sha1
            if not tree_file.is_file():
                continue
            tree = json.loads(tree_file.read_text(encoding="utf-8"))
            for file in tree.get("files", []):
                if isinstance(file, dict) and str(file.get("root", "")) == "GAME_DIR" and str(file.get("path", "")).lstrip("/") == logical_path:
                    tracked.append(file)
    return tracked


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
        selected_path = ctx.game_dir / "automodpack" / "client" / "selected.json"
        selected = json.loads(selected_path.read_text(encoding="utf-8"))
        known_hosts = json.loads((ctx.game_dir / "automodpack" / "client" / "data" / "known-hosts.json").read_text(encoding="utf-8"))
        connection = json.loads((ctx.game_dir / "automodpack" / "client" / "data" / "packs" / expected["modpackId"] / "connection.json").read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"Preload did not persist bootstrap state: {error}") from error
    if selected.get("modpackId") != expected["modpackId"]:
        raise AssertionError(f"bootstrap selected.json mismatch: expected {expected['modpackId']!r}, got {selected.get('modpackId')!r}")
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
    """Assert the login persisted the secret it issued, in every connection mode; only login-less preload shapes store absence."""
    modpack_id = str(ctx.vars.get("bootstrap_modpack_id", ""))
    origin = str(ctx.vars.get("bootstrap_origin", ""))
    if not modpack_id or not origin:
        raise AssertionError("bootstrap identity was not captured before authenticated secret assertion")
    connection_path = ctx.game_dir / "automodpack" / "client" / "data" / "packs" / modpack_id / "connection.json"
    try:
        connection = json.loads(connection_path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"authenticated secret state is not readable: {error}") from error
    client_secret = (connection.get("secrets", {}) or {}).get(origin)
    if not isinstance(client_secret, dict):
        raise AssertionError("authenticated login did not persist a client secret for the bootstrap origin")
    server_secrets_path = ctx.server_dir / "automodpack" / "credentials" / "secrets.json"
    try:
        server_secrets = json.loads(server_secrets_path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"authenticated secret state is not readable: {error}") from error
    value = client_secret.get("secret")
    timestamp = client_secret.get("timestamp")
    anonymous = base64.urlsafe_b64encode(bytes(32)).decode("ascii").rstrip("=")
    if not isinstance(value, str) or not value or value == anonymous or not isinstance(timestamp, (int, float)) or timestamp <= 0:
        raise AssertionError("persisted client secret is missing, anonymous, or has no valid timestamp")
    if value in json.dumps(server_secrets):
        raise AssertionError("server persisted the raw download secret instead of its SHA-256 key")
    issued = (server_secrets.get("secrets", {}) or {}).get(hashlib.sha256(value.encode("utf-8")).hexdigest())
    if not isinstance(issued, dict):
        raise AssertionError("server did not persist the issued secret under its SHA-256 key")
    if not (isinstance(issued.get("name"), str) and issued.get("name")):
        raise AssertionError("server persisted the secret without the player name it was issued to")


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


@verb("assert_timeline_file")
def assert_timeline_file(ctx, step):
    """Assert the instance timeline tracks a path and its claimed CAS bytes stay recoverable: hash-and-size intact, and matching the fixture or content when one is requested."""
    root = str(step.get("root", "GAME_DIR")).upper()
    overlay_pack_id = str(ctx.resolve(step["overlayPackId"])) if "overlayPackId" in step else None
    logical_path = str(step["path"]).lstrip("/")
    tracked = [entry for entry in _timeline_tracked(ctx, logical_path) if str(entry.get("root", "")) == root
               and (overlay_pack_id is None or str(entry.get("overlayPackId", "")) == overlay_pack_id)]
    if not tracked:
        journal = ctx.game_dir / "automodpack" / "client" / "state-history" / "journal.jsonl"
        snapshots = [(json.loads(line)["seq"], json.loads(line)["kind"]) for line in journal.read_text(encoding="utf-8").splitlines() if line.strip()] if journal.is_file() else []
        raise AssertionError(f"no timeline snapshot tracks {logical_path!r} under root {root}; snapshots={snapshots}")
    fixture = ctx.resolve(step.get("fixture"))
    content = ctx.resolve(step["content"]).encode("utf-8") if "content" in step else None
    if fixture is not None and not isinstance(fixture, dict):
        raise ValueError("timeline file assertion fixture must be a mapping")
    # Recoverable means the newest tracked bytes are the payload; earlier versions may legitimately differ.
    entry = tracked[-1]
    digest = str(entry.get("sha1", "")).lower()
    if not re.fullmatch(r"[0-9a-f]{40}", digest):
        raise AssertionError(f"tracked timeline entry for {logical_path!r} has an invalid hash: {digest!r}")
    payload = _object_path(ctx, digest)
    if not payload.is_file():
        raise AssertionError(f"tracked bytes for {logical_path!r} are missing from the object store: {payload}")
    if payload.stat().st_size != int(entry.get("size", -1)) or hashlib.sha1(payload.read_bytes()).hexdigest() != digest:
        raise AssertionError(f"tracked bytes for {logical_path!r} do not match their hash {digest}")
    if fixture is None and content is None:
        return

    if content is not None and payload.read_bytes() != content:
        raise AssertionError(f"tracked timeline bytes for {logical_path!r} do not match the expected content")
    if isinstance(fixture, dict):
        try:
            assert_valid_mod_fixture(payload.read_bytes(), fixture, ctx.target.minecraft)
        except AssertionError as error:
            raise AssertionError(
                f"tracked timeline bytes for {logical_path!r} are not the requested fixture: {error}; "
                f"tracked versions: {[{'sha1': str(e.get('sha1'))[:8], 'size': e.get('size')} for e in tracked]}"
            ) from error


@verb("assert_generation")
def assert_generation(ctx, step):
    """Assert installed generation metadata without coupling scenarios to Java internals."""
    try:
        state, manifest = _read_active_generation(ctx)
        if "patchNotes" in step:
            notes = _active_generation_notes(ctx, state["modpackId"], state["contentToken"])
    except (KeyError, OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"active generation metadata is invalid: {error}") from error
    policy = manifest.get("policy", {}) or {}
    groups = {
        group_id: group
        for category in (policy.get("categories", {}) or {}).values() if isinstance(category, dict)
        for group_id, group in category.items() if isinstance(group, dict)
    }
    group_categories = {
        group_id: category_name
        for category_name, category in (policy.get("categories", {}) or {}).items() if isinstance(category, dict)
        for group_id, group in category.items() if isinstance(group, dict)
    }
    for group_id, requirements in (step.get("groups", {}) or {}).items():
        if group_id not in groups:
            raise AssertionError(f"active generation is missing group {group_id!r}")
        actual = groups[group_id]
        for field, value in (requirements or {}).items():
            # 'category' names the containing category in the policy, since groups carry no category field of their own.
            expected = group_categories[group_id] if field == "category" else actual.get(field)
            if expected != value:
                raise AssertionError(f"group {group_id!r} field {field!r}: expected {value!r}, got {expected!r}")
    if "patchNotes" in step and notes != ctx.resolve(step["patchNotes"]):
        raise AssertionError("active generation patch notes do not match the scenario")
