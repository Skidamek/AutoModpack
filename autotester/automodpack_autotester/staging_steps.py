"""Staged-modpack authoring: the client-side mirror history, policy documents, and CAS seeding."""
from __future__ import annotations

import hashlib
import json
import secrets
import shutil
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

from .config import REPO_ROOT
from .generation_identity import content_token, ownership_ledger_digest
from .mod_fixtures import write_valid_mod_fixture
from .mods import resolve_mod
from .client_steps import _ensure_client_data_root, _load_ver, cas_object
from .engine import Context
from .engine.registry import verb


def _sha1(path: Path) -> str:
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha1").hexdigest()


def _canonical_timestamp(moment: datetime) -> str:
    """Format a timestamp the way java.time.Instant.toString does: no fraction, or 3/6/9 digits, never trailing zeros.

    The client rejects any other shape as a non-canonical generation timestamp, so a staged generation stamped with a
    fixed-width fraction (e.g. ``.800000Z``) is strictly invalid and crashes the first storage validation that reads
    it — a ~10% flake per staged record. Verified against jshell: Instant.parse("...32.800000Z").toString() gives
    "...32.800Z", while minimal forms like "...32.8Z" are equally rejected. Keep this next to the only writer.
    """
    if moment.tzinfo is None:
        raise ValueError("staged generation timestamps must carry a timezone")
    moment = moment.astimezone(timezone.utc)
    if moment.microsecond == 0:
        return moment.strftime("%Y-%m-%dT%H:%M:%SZ")
    if moment.microsecond % 1000 == 0:
        return moment.strftime("%Y-%m-%dT%H:%M:%S") + f".{moment.microsecond // 1000:03d}Z"
    return moment.strftime("%Y-%m-%dT%H:%M:%S") + f".{moment.microsecond:06d}Z"


def _check_canonical_timestamp(created_at: str) -> None:
    """Fail fast unless ``created_at`` round-trips through Instant.parse(Instant.toString) semantics."""
    if not isinstance(created_at, str):
        raise TypeError(f"staged generation timestamp is not a string: {created_at!r}")
    try:
        parsed = datetime.fromisoformat(created_at.replace("Z", "+00:00"))
    except ValueError as error:
        raise ValueError(f"staged generation timestamp is not parseable: {created_at!r}") from error
    if _canonical_timestamp(parsed) != created_at:
        raise ValueError(f"staged generation timestamp is not canonical: {created_at!r}")


def _policy_bytes(policy: dict) -> bytes:
    """The exact policy document bytes the client stores in its CAS: deterministic key-sorted JSON."""
    return json.dumps(policy, sort_keys=True).encode("utf-8")


def _policy_tree(policy: dict) -> dict[str, tuple[str, int]]:
    """The served file set (path -> (sha1, size)) described by one policy document."""
    tree = {}
    for group in (policy.get("groups") or {}).values():
        if not isinstance(group, dict):
            continue
        for path, file in (group.get("files") or {}).items():
            if isinstance(file, dict) and file.get("sha1"):
                tree[str(path)] = (str(file["sha1"]).lower(), int(file["size"]))
    return tree


def _staged_ledger(modpack_id: str, file_map: dict[str, tuple[str, int]]) -> dict:
    """The ownership ledger of a staged pack: one PRESENT entry per served path, as a first publish materializes it."""
    ledger_entries = [
        {"logicalPath": path, "historicalHashes": [{"sha1": file_map[path][0], "size": file_map[path][1]}], "historicalGroupIds": ["main"], "currentStatus": "PRESENT"}
        for path in sorted(file_map)
    ]
    return {"modpackId": modpack_id, "entries": ledger_entries, "digest": ownership_ledger_digest(modpack_id, ledger_entries)}


def _mirror_entries(mirror_path: Path) -> list[dict]:
    """The parsed journal mirror lines, or an empty list when the mirror does not exist yet."""
    if not mirror_path.is_file():
        return []
    entries = []
    for line in mirror_path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if line:
            entries.append(json.loads(line))
    return entries


def _mirror_tree(entries: list[dict]) -> dict[str, tuple[str, int]]:
    """The served file set described by folding the staged mirror entries, snapshot-aware like the Java replay."""
    tree: dict[str, tuple[str, int]] = {}
    for entry in entries:
        if entry.get("snapshot"):
            tree = {}
        for change in entry.get("changes") or []:
            if change.get("toSha1"):
                tree[str(change["path"])] = (str(change["toSha1"]).lower(), int(change.get("toSize") or 0))
            else:
                tree.pop(str(change["path"]), None)
    return tree


def _append_staged_mirror(client_root: Path, modpack_id: str, token: str, policy_sha1: str, file_map: dict[str, tuple[str, int]], notes: str, created_at: str) -> list[dict]:
    """Append the generation's journal entry to the pack's mirror, the client's replica of the server journal."""
    mirror_path = client_root / "history" / modpack_id / "journal.jsonl"
    entries = _mirror_entries(mirror_path)
    last = entries[-1] if entries else None
    if last is not None and str(last.get("contentToken", "")) == token:
        return entries
    previous_tree = _mirror_tree(entries)
    changes = []
    for path in sorted(set(previous_tree) | set(file_map)):
        old, new = previous_tree.get(path), file_map.get(path)
        if old is None:
            changes.append({"path": path, "toSha1": new[0], "toSize": new[1]})
        elif new is None:
            changes.append({"path": path, "fromSha1": old[0]})
        elif old != new:
            changes.append({"path": path, "fromSha1": old[0], "toSha1": new[0], "toSize": new[1]})
    entry = {
        "seq": int(last.get("seq", 0)) + 1 if last else 1,
        "contentToken": token,
        "policySha1": policy_sha1,
        "createdAt": created_at,
        "notes": notes,
        "restoreOf": -1,
        "snapshot": last is None,
        "changes": changes,
    }
    mirror_path.parent.mkdir(parents=True, exist_ok=True)
    with mirror_path.open("a", encoding="utf-8") as handle:
        handle.write(json.dumps(entry) + "\n")
    entries.append(entry)
    return entries


def _write_staged_generation(
    ctx: Context,
    root: Path,
    modpack_id: str,
    data_root: Path,
    *,
    client_root: Path | None = None,
    modpack_name: str | None = None,
    patch_notes: str = "",
    editable_paths: set[str] | frozenset[str] = frozenset(),
) -> dict:
    files = []
    for path in sorted(p for p in root.rglob("*") if p.is_file()):
        if path.name == "automodpack-content.json":
            continue
        rel = path.relative_to(root)
        files.append({
            "logicalPath": rel.as_posix(),
            "size": str(path.stat().st_size),
            "type": "mod" if rel.parts and rel.parts[0] == "mods" else "config",
            "editable": rel == ctx.marker_rel or rel.as_posix() in editable_paths,
            "sha1": _sha1(path),
        })

    files.sort(key=lambda entry: entry["logicalPath"])
    modpack_name = modpack_name if modpack_name is not None else ctx.modpack_name
    policy = {
        "modpackId": modpack_id,
        "modpackName": modpack_name,
        "automodpackVersion": "",
        "loader": ctx.target.loader,
        "loaderVersion": _load_ver(ctx.target),
        "mcVersion": ctx.target.minecraft,
        "groups": {
            "main": {
                "displayName": modpack_name,
                "description": "",
                "category": "",
                "required": True,
                "defaultSelected": True,
                "breaksWith": [],
                "requires": [],
                "compatiblePlatforms": [],
                "files": {
                    entry["logicalPath"]: {
                        "size": entry["size"],
                        "type": entry["type"],
                        "editable": entry["editable"],
                        "sha1": entry["sha1"],
                        "murmur": "",
                    }
                    for entry in files
                },
            }
        },
    }
    file_map = {entry["logicalPath"]: (entry["sha1"], int(entry["size"])) for entry in files}
    created_at = _canonical_timestamp(datetime.now(timezone.utc))
    client_root = client_root or root.parent
    policy_sha1 = hashlib.sha1(_policy_bytes(policy)).hexdigest()
    token = content_token(file_map)
    ledger = _staged_ledger(modpack_id, file_map)
    objects = data_root / "objects"
    objects.mkdir(parents=True, exist_ok=True)
    policy_object = cas_object(objects, policy_sha1)
    if not policy_object.is_file():
        policy_object.parent.mkdir(parents=True, exist_ok=True)
        policy_object.write_bytes(_policy_bytes(policy))
    for entry in files:
        object_path = cas_object(objects, entry["sha1"])
        if not object_path.is_file():
            object_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(root / entry["logicalPath"], object_path)
    mirror_entries = _append_staged_mirror(client_root, modpack_id, token, policy_sha1, file_map, patch_notes, created_at)
    _verify_staged_generation(policy_object, token, policy_sha1, created_at, ledger, client_root / "history" / modpack_id / "journal.jsonl", patch_notes)
    return {"contentToken": token, "policySha1": policy_sha1, "ledger": ledger, "mirrorEntries": len(mirror_entries)}


def _verify_staged_generation(policy_object: Path, token: str, policy_sha1: str, created_at: str, ledger: dict, mirror_path: Path, patch_notes: str) -> None:
    """Re-read a staged generation's policy object and its mirror line and prove both are strictly valid before any client can see them.

    A malformed staged generation (non-canonical timestamp, mismatched content token, or ledger digest drift) stays
    invisible until a client storage validation reads it — potentially minutes and hundreds of steps later, on a
    random shard. Fail here instead, where the cause is obvious.
    """
    try:
        stored_policy = json.loads(policy_object.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise ValueError(f"staged generation policy document is not readable: {policy_object}: {error}") from error
    if not isinstance(stored_policy, dict):
        raise TypeError(f"staged generation policy document is not an object: {policy_object}")
    _check_canonical_timestamp(created_at)
    if hashlib.sha1(policy_object.read_bytes()).hexdigest() != policy_sha1:
        raise ValueError(f"staged generation policy object does not match its hash: {policy_object}")
    if content_token(_policy_tree(stored_policy)) != token:
        raise ValueError(f"staged generation content token does not match its policy files: {policy_object}")
    if not isinstance(ledger, dict) or str(ledger.get("modpackId", "")) != str(stored_policy.get("modpackId", "")) or ownership_ledger_digest(str(ledger.get("modpackId", "")), list(ledger.get("entries") or [])) != ledger.get("digest"):
        raise ValueError(f"staged generation ledger digest does not match its entries: {policy_object}")
    entries = _mirror_entries(mirror_path)
    head_entry = entries[-1] if entries else None
    if head_entry is None:
        raise ValueError(f"staged generation mirror has no journal entry: {mirror_path}")
    _check_canonical_timestamp(head_entry.get("createdAt"))
    if str(head_entry.get("contentToken", "")) != token:
        raise ValueError(f"staged generation mirror entry does not carry the generation's content token: {mirror_path}")
    if str(head_entry.get("policySha1", "")) != policy_sha1:
        raise ValueError(f"staged generation mirror entry does not carry the generation's policy hash: {mirror_path}")
    if str(head_entry.get("notes", "")) != patch_notes:
        raise ValueError(f"staged generation patch notes drifted between planning and writing: {mirror_path}")


@verb("stage_modpack")
def _v_stage_modpack(ctx: Context, step):
    """Pre-stage a modpack into the client game dir for offline / client-only runs.

    Lays down the fixed active projection plus its mirror history, CAS policy document, and active-state pointer,
    and writes a client config that selects it with ``updateSelectedModpackOnLaunch=false``,
    so the client loads the staged generation on boot without contacting a server.
    Run this before ``launch_client`` in a ``mode: client-only`` scenario.

    Args: ``from`` (a ready modpack dir to copy wholesale, path relative to the
    repo root), ``mods`` (extra jars to drop into the pack's ``mods/`` - each a
    repo-relative path or a pinned ``{url, sha512}`` remote jar, see
    :mod:`automodpack_autotester.mods`), and ``config`` (extra client-config
    overrides).
    """
    game = ctx.game_dir
    record_only = bool(step.get("recordOnly", False))
    modpack_id = str(step.get("packId") or "".join(secrets.choice("abcdefghijklmnopqrstuvwxyz0123456789") for _ in range(7)))
    modpack_name = str(step.get("packName") or ctx.modpack_name)
    automodpack = game / "automodpack"
    client_root = automodpack / "client"
    data_root = _ensure_client_data_root(game)
    root = client_root / "staging" / modpack_id if record_only else client_root / "active"
    if root.exists():
        shutil.rmtree(root)
    if not record_only:
        ctx.vars["active_dir"] = "automodpack/client/active"
    root.mkdir(parents=True, exist_ok=True)
    src = step.get("from")
    if src:
        src_path = Path(ctx.resolve(str(src)))
        if not src_path.is_absolute():
            src_path = REPO_ROOT / src_path
        src_path = src_path.resolve()
        if not src_path.is_dir():
            raise FileNotFoundError(f"stage_modpack 'from' is not a directory: {src_path}")
        shutil.copytree(src_path, root, dirs_exist_ok=True)

    declared_files = step.get("files")
    if declared_files is None:
        declared_files = [{"path": str(rel), "content": content} for rel, content in ctx.scenario_files]
    editable_paths = {
        Path(str(item["path"])).as_posix()
        for item in declared_files
        if isinstance(item, dict) and item.get("editable") is True
    }
    (root / ctx.marker_rel).parent.mkdir(parents=True, exist_ok=True)
    (root / ctx.marker_rel).write_text(json.dumps({"marker": modpack_name}) + "\n", encoding="utf-8")
    for item in declared_files:
        if not isinstance(item, dict) or "path" not in item:
            raise ValueError("stage_modpack files entries need path/content")
        rel, content = Path(str(item["path"])), str(item.get("content", ""))
        if rel.is_absolute() or ".." in rel.parts:
            raise ValueError(f"stage_modpack path escapes the pack: {rel}")
        f = root / rel
        if item.get("fixture") is not None:
            fixture = item["fixture"]
            if not isinstance(fixture, dict):
                raise ValueError("stage_modpack fixture must be a mapping")
            write_valid_mod_fixture(f, fixture, ctx.target.minecraft)
        else:
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(content, encoding="utf-8")

    mods = step.get("mods") or []
    if mods:
        (root / "mods").mkdir(parents=True, exist_ok=True)
        download_timeout = float(ctx.settings.get("timeouts", {}).get("downloadFileSeconds", 180))

        def resolve(entry):
            return resolve_mod(
                entry,
                ctx.resolve,
                target_id=getattr(ctx.target, "id", None),
                timeout=download_timeout,
            )

        with ThreadPoolExecutor(max_workers=min(4, len(mods))) as executor:
            resolved_mods = executor.map(resolve, mods)
            for mod in resolved_mods:
                shutil.copy2(mod, root / "mods" / mod.name)

    (root / "automodpack-content.json").unlink(missing_ok=True)
    generation = _write_staged_generation(
        ctx,
        root,
        modpack_id,
        data_root,
        client_root=client_root,
        modpack_name=modpack_name,
        patch_notes=str(step.get("patchNotes", "")),
        editable_paths=editable_paths,
    )
    if record_only:
        shutil.rmtree(root)
        ctx.vars["staged_pack_id"] = modpack_id
        return
    state = {
        "modpackId": modpack_id,
        "contentToken": generation["contentToken"],
        "status": "ACTIVE",
        "ownershipLedger": generation["ledger"],
    }
    (client_root / "active-state.json").write_text(json.dumps(state, indent=2) + "\n", encoding="utf-8")

    selection_store = client_root / "selections.json"
    selection_store.write_text(json.dumps({
        "DO_NOT_CHANGE_IT": 1,
        "selections": {modpack_id: {"requestedGroups": [], "excludedGroups": []}},
    }, indent=2) + "\n", encoding="utf-8")

    # A client config that selects the staged pack and disables the launch update,
    # so Preload loads it locally (no server contact, no file reconciliation).
    host = ctx.server_host or "127.0.0.1"
    addr = host if ":" in host else f"{host}:25565"
    cfg = {
        "DO_NOT_CHANGE_IT": 3,
        "selectedModpackId": modpack_id,
        "updateSelectedModpackOnLaunch": False,
    }
    cfg.update(ctx.resolve(step.get("config", {}) or {}))
    automodpack.mkdir(parents=True, exist_ok=True)
    (automodpack / "client-config.json").write_text(json.dumps(cfg, indent=2), encoding="utf-8")

    # The offline fallback that explicitly enables launch updates still needs the
    # current production connection-store record. It is deliberately created only
    # for that path; ordinary offline staging and recordOnly staging stay local.
    if cfg["updateSelectedModpackOnLaunch"]:
        connection_path = data_root / "packs" / modpack_id / "connection.json"
        connection_path.parent.mkdir(parents=True, exist_ok=True)
        connection_path.write_text(
            json.dumps(
                {
                    "connection": {
                        "origin": addr,
                        "endpoint": addr,
                        "connectionMode": "DIRECT",
                    },
                    "secrets": {},
                },
                indent=2,
            )
            + "\n",
            encoding="utf-8",
        )
