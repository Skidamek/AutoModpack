"""Staged-modpack authoring: the client-side generation records, manifests, and CAS seeding."""
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

    The client rejects any other shape as a non-canonical generation timestamp, so a staged record stamped with a
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


_STAGED_JOURNAL_TAIL_LIMIT = 25


def _record_tree(record: dict) -> dict[str, tuple[str, int]]:
    """The served file set (path -> (sha1, size)) described by one head document."""
    tree = {}
    for group in ((record.get("policy") or {}).get("groups") or {}).values():
        if not isinstance(group, dict):
            continue
        for path, file in (group.get("files") or {}).items():
            if isinstance(file, dict) and file.get("sha1"):
                tree[str(path)] = (str(file["sha1"]).lower(), int(file["size"]))
    return tree


def _staged_head_document(modpack_id: str, policy: dict, file_map: dict[str, tuple[str, int]], notes: str, created_at: str) -> dict:
    """The head-document record the client stores for one staged pack with no prior history."""
    token = content_token(file_map)
    policy_sha1 = hashlib.sha1(json.dumps(policy, sort_keys=True).encode("utf-8")).hexdigest()
    ledger_entries = [
        {"logicalPath": path, "historicalHashes": [{"sha1": file_map[path][0], "size": file_map[path][1]}], "historicalGroupIds": ["main"], "currentStatus": "PRESENT"}
        for path in sorted(file_map)
    ]
    journal = [{
        "seq": 1,
        "contentToken": token,
        "policySha1": policy_sha1,
        "createdAt": created_at,
        "notes": notes,
        "restoreOf": -1,
        "snapshot": True,
        "changes": [{"path": path, "toSha1": file_map[path][0], "toSize": file_map[path][1]} for path in sorted(file_map)],
    }]
    return {
        "contentToken": token,
        "policySha1": policy_sha1,
        "createdAt": created_at,
        "journalHead": 1,
        "journalTruncated": False,
        "journal": journal,
        "ownershipLedger": {"modpackId": modpack_id, "entries": ledger_entries, "digest": ownership_ledger_digest(modpack_id, ledger_entries)},
        "policy": policy,
    }


def _latest_staged_record(client_root: Path, modpack_id: str) -> dict | None:
    """The newest staged record for one modpack, or None when no record exists yet."""
    def created_at(record: dict):
        try:
            return datetime.fromisoformat(str(record.get("createdAt", "")).replace("Z", "+00:00"))
        except ValueError:
            return datetime.min.replace(tzinfo=timezone.utc)

    candidates = []
    for path in (client_root / "records").glob("*/manifest.json"):
        try:
            record = json.loads(path.read_text(encoding="utf-8"))
        except (OSError, ValueError, TypeError):
            continue
        if isinstance(record, dict) and (record.get("policy") or {}).get("modpackId") == modpack_id:
            candidates.append((created_at(record), record))
    return max(candidates, key=lambda candidate: candidate[0])[1] if candidates else None


def _staged_journal_tail(record: dict, previous: dict, notes: str, created_at: str) -> list[dict]:
    """Continue the previous staged journal with the content diff this record publishes."""
    previous_journal = [entry for entry in (previous.get("journal") or []) if isinstance(entry, dict)]
    if not previous_journal:
        return list(record["journal"])
    if str(previous.get("contentToken", "")) == str(record["contentToken"]):
        return previous_journal
    previous_tree, current_tree = _record_tree(previous), _record_tree(record)
    changes = []
    for path in sorted(set(previous_tree) | set(current_tree)):
        old, new = previous_tree.get(path), current_tree.get(path)
        if old is None:
            changes.append({"path": path, "toSha1": new[0], "toSize": new[1]})
        elif new is None:
            changes.append({"path": path, "fromSha1": old[0]})
        elif old != new:
            changes.append({"path": path, "fromSha1": old[0], "toSha1": new[0], "toSize": new[1]})
    tail = previous_journal[-_STAGED_JOURNAL_TAIL_LIMIT + 1:]
    seq = int(tail[-1].get("seq", 0)) + 1 if tail else 1
    tail.append({"seq": seq, "contentToken": record["contentToken"], "policySha1": record["policySha1"], "createdAt": created_at, "notes": notes, "restoreOf": -1, "snapshot": False, "changes": changes})
    return tail


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
    record = _staged_head_document(modpack_id, policy, file_map, patch_notes, created_at)
    previous = _latest_staged_record(client_root, modpack_id)
    if previous is not None:
        record["journal"] = _staged_journal_tail(record, previous, patch_notes, created_at)
        record["journalHead"] = int(record["journal"][-1]["seq"])
    generation_path = client_root / "records" / record["contentToken"] / "manifest.json"
    generation_path.parent.mkdir(parents=True, exist_ok=True)
    generation_path.write_text(json.dumps(record, indent=2) + "\n", encoding="utf-8")
    _verify_staged_manifest(generation_path, patch_notes)
    objects = data_root / "objects"
    objects.mkdir(parents=True, exist_ok=True)
    for entry in files:
        object_path = cas_object(objects, entry["sha1"])
        if not object_path.is_file():
            object_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(root / entry["logicalPath"], object_path)
    return {"contentToken": record["contentToken"], "policySha1": record["policySha1"]}


def _verify_staged_manifest(record_path: Path, patch_notes: str) -> None:
    """Re-read a staged generation record and prove it is strictly valid before any client can see it.

    A malformed staged record (non-canonical timestamp, mismatched content token or ledger digest) stays invisible
    until a client storage validation reads it — potentially minutes and hundreds of steps later, on a random shard.
    Fail here instead, where the cause is obvious.
    """
    try:
        record = json.loads(record_path.read_text(encoding="utf-8"))
    except (OSError, ValueError) as error:
        raise ValueError(f"staged generation record is not readable: {record_path}: {error}") from error
    if not isinstance(record, dict):
        raise TypeError(f"staged generation record is not an object: {record_path}")
    policy = record.get("policy")
    if not isinstance(policy, dict):
        raise TypeError(f"staged generation record has no policy document: {record_path}")
    _check_canonical_timestamp(record.get("createdAt"))
    if content_token(_record_tree(record)) != record.get("contentToken"):
        raise ValueError(f"staged generation content token does not match its policy files: {record_path}")
    ledger = record.get("ownershipLedger")
    if not isinstance(ledger, dict) or str(ledger.get("modpackId", "")) != str(policy.get("modpackId", "")) or ownership_ledger_digest(str(ledger.get("modpackId", "")), list(ledger.get("entries") or [])) != ledger.get("digest"):
        raise ValueError(f"staged generation ledger digest does not match its entries: {record_path}")
    journal = list(record.get("journal") or [])
    head_entry = journal[-1] if journal else None
    if head_entry is None or int(head_entry.get("seq", 0)) != int(record.get("journalHead", -1)):
        raise ValueError(f"staged generation journal head does not match its entries: {record_path}")
    if str(head_entry.get("contentToken", "")) != str(record.get("contentToken", "")):
        raise ValueError(f"staged generation head entry does not carry the record's content token: {record_path}")
    _check_canonical_timestamp(head_entry.get("createdAt"))
    if str(head_entry.get("notes", "")) != patch_notes:
        raise ValueError(f"staged generation patch notes drifted between planning and writing: {record_path}")


@verb("stage_modpack")
def _v_stage_modpack(ctx: Context, step):
    """Pre-stage a modpack into the client game dir for offline / client-only runs.

    Lays down the fixed active projection plus its immutable generation record and
    writes a client config that selects it with ``updateSelectedModpackOnLaunch=false``,
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
