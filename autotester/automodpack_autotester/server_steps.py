"""Server lifecycle verbs and live server-state inspection (journal, projection, object store)."""
from __future__ import annotations

import hashlib
import json
import re
import shutil
from pathlib import Path

from .mod_fixtures import write_valid_mod_fixture
from .supervisor import resource_labels
from .client_steps import cas_object
from .docker_harness import _container, _ensure_volume, _exec_output, _remove_volume, _run_container, _uid, _gid, _wait_for_log
from .engine import Context
from .engine.registry import verb
from .engine.util import await_condition, parse_duration


_SERVER_DATA_ROOT = Path("automodpack/data")


def _prepare_server(ctx: Context):
    srv_dir = ctx.server_dir
    (srv_dir / "mods").mkdir(parents=True, exist_ok=True)
    shutil.copy2(ctx.artifact, srv_dir / "mods" / "automodpack.jar")
    cfg = dict(ctx.settings.get("automodpack", {}).get("config", {}))
    topology_config = (ctx.scenario.get("topology", {}).get("server", {}).get("automodpack", {}) or {}).get("config", {})
    cfg.update(topology_config)
    connection_path = ctx.scenario.get("connectionPath") or {}
    if connection_path:
        cfg["connectionMode"] = str(connection_path["mode"]).upper()
        if "bindPort" in connection_path:
            cfg["bindPort"] = connection_path["bindPort"]
        if "advertisedEndpointHost" in connection_path:
            cfg["advertisedEndpointHost"] = connection_path["advertisedEndpointHost"]
        if "endpointPort" in connection_path:
            cfg["advertisedEndpointPort"] = connection_path["endpointPort"]
    cfg["modpackName"] = ctx.modpack_name
    cfg["acceptedLoaders"] = [ctx.target.loader]
    (srv_dir / "automodpack").mkdir(parents=True, exist_ok=True)
    (srv_dir / "automodpack" / "server-config.json").write_text(json.dumps(cfg, indent=2), encoding="utf-8")
    (srv_dir / _SERVER_DATA_ROOT).mkdir(parents=True, exist_ok=True)
    _write_server_generation(ctx, 0)


def _server_generation(ctx: Context, index: int) -> dict:
    generations = (ctx.scenario.get("serverFiles", {}) or {}).get("generations") or []
    if not generations:
        if index != 0:
            raise ValueError(f"scenario has no server generation {index}")
        return {"files": [{"path": str(path), "content": content} for path, content in ctx.scenario_files]}
    if not isinstance(generations, list) or index < 0 or index >= len(generations):
        raise ValueError(f"server generation index {index} is outside the declared generations")
    generation = generations[index]
    if not isinstance(generation, dict):
        raise ValueError(f"serverFiles.generations[{index}] must be a mapping")
    return generation


def _write_server_generation(ctx: Context, index: int) -> None:
    generation = _server_generation(ctx, index)
    srv_dir = ctx.server_dir
    host_root = srv_dir / "automodpack" / "host-modpack"
    if host_root.exists():
        shutil.rmtree(host_root)
    host_root.mkdir(parents=True, exist_ok=True)
    main_root = host_root / "main"
    (main_root / ctx.marker_rel).parent.mkdir(parents=True, exist_ok=True)
    (main_root / ctx.marker_rel).write_text(json.dumps({"marker": ctx.modpack_name}) + "\n", encoding="utf-8")
    configured_groups = {
        str(group_id)
        for group_id in ((ctx.scenario.get("topology", {}).get("server", {}).get("automodpack", {}) or {}).get("config", {}).get("groups", {}) or {})
    }
    for item in generation.get("files", []):
        if not isinstance(item, dict) or "path" not in item:
            raise ValueError(f"serverFiles.generations[{index}].files entries need path/content")
        rel = Path(str(item["path"]))
        if rel.is_absolute() or ".." in rel.parts:
            raise ValueError(f"server generation path escapes the host modpack: {rel}")
        group_id = str(item.get("group", "main"))
        if configured_groups and group_id not in configured_groups:
            raise ValueError(f"server generation file {rel} refers to undeclared group {group_id!r}")
        if Path(group_id).is_absolute() or ".." in Path(group_id).parts or len(Path(group_id).parts) != 1:
            raise ValueError(f"server generation group is not a single safe identifier: {group_id!r}")
        f = host_root / group_id / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        fixture = item.get("fixture")
        if fixture is not None:
            if not isinstance(fixture, dict):
                raise ValueError(f"server generation fixture for {rel} must be a mapping")
            write_valid_mod_fixture(f, fixture, ctx.target.minecraft)
        else:
            f.write_text(str(item.get("content", "")), encoding="utf-8")
    patch_notes = generation.get("patchNotes", "")
    patch_path = srv_dir / "automodpack" / "patch-notes.md"
    patch_path.parent.mkdir(parents=True, exist_ok=True)
    patch_path.write_text(str(patch_notes), encoding="utf-8")


def _launch_server(ctx: Context):
    target, scenario, settings = ctx.target, ctx.scenario, ctx.settings
    topo = scenario.get("topology", {}).get("server", {})
    srv_type = topo.get("type") or settings.get("serverTypes", {}).get(target.loader)
    if not srv_type:
        raise ValueError(f"No server type for {target.loader}")

    env = dict(settings.get("server", {}).get("env", {}))
    env.update({
        "TYPE": str(srv_type),
        "VERSION": target.minecraft,
        "MEMORY": str(topo.get("memory", settings.get("server", {}).get("memory", "2G"))),
    })
    for k, v in [
        ("fabric_loader", "FABRIC_LOADER_VERSION"),
        ("forge_version", "FORGE_VERSION"),
        ("neoforge_version", "NEOFORGE_VERSION"),
    ]:
        val = getattr(target, k, None)
        if val:
            env[v] = val
    env.update({str(k): str(v) for k, v in (topo.get("env", {}) or {}).items()})
    # Keep holepunch transport tracing on so any mid-login close leaves a receipt.
    env.setdefault("JVM_OPTS", "-Dmcholepunch.debug=true")
    if scenario.get("serverFiles", {}).get("generations"):
        env.setdefault("ENABLE_RCON", "true")
        env.setdefault("RCON_PASSWORD", "amp-autotest")
    # Run the itzg server as the same user the runner uses, so it can write to the
    # bind-mounted mods/automodpack dirs (which the runner created). Without this the
    # server runs as itzg's default UID 1000 and fails on hosts with a different UID
    # (e.g. the GitHub runner's 1001): AccessDenied writing /data/mods/*.download.
    env.setdefault("UID", str(_uid()))
    env.setdefault("GID", str(_gid()))
    env["AUTOMODPACK_DATA_ROOT"] = str(_SERVER_DATA_ROOT)
    mr = topo.get("modrinth", {})
    if mr:
        projs = list(
            dict.fromkeys(
                str(p).strip()
                for p in (
                    list(mr.get("projects", []))
                    + list((mr.get("projectsByLoader", {}) or {}).get(target.loader, []) or [])
                )
                if p
            )
        )
        if projs:
            env["MODRINTH_PROJECTS"] = ",".join(projs)
        if mr.get("version"):
            env["MODRINTH_VERSION"] = str(mr["version"])
        if mr.get("versionType"):
            env["MODRINTH_PROJECTS_DEFAULT_VERSION_TYPE"] = str(mr["versionType"])
    sc = topo.get("serverCache", {}) or settings.get("serverCache", {})
    if sc.get("enabled", True):
        vol = f"{sc.get('volumePrefix', 'amp-server-cache')}-{target.id}"
        if sc.get("clean", False):
            _remove_volume(vol)
        _ensure_volume(vol)
        mounts = [(vol, "/data", False)]
        for sub in ("mods", "automodpack"):
            (ctx.server_dir / sub).mkdir(parents=True, exist_ok=True)
            mounts.append((ctx.server_dir / sub, f"/data/{sub}", False))
    else:
        mounts = [(ctx.server_dir, "/data", False)]
    img = str(topo.get("image") or settings.get("images", {}).get("server", "itzg/minecraft-server"))
    if ":" not in img:
        tag = str(settings.get("images", {}).get("serverTagTemplate", "java{java}")).format(java=target.java)
        img = f"{img}:{tag}"
    _run_container(name=ctx.srv_name, image=img, network=ctx.net_name, env=env, mounts=mounts, labels=resource_labels(ctx.resource_scope))


@verb("launch_server")
def _v_launch_server(ctx: Context, step):
    _launch_server(ctx)


@verb("wait_server")
def _v_wait_server(ctx: Context, step):
    to = ctx.scenario.get("timeouts", {}) or ctx.settings.get("timeouts", {})
    timeout = parse_duration(step.get("timeout"), default=float(to.get("serverStartSeconds", 180)))
    _wait_for_log(ctx.srv_name, "Done (", timeout=timeout)


@verb("publish_server_generation")
def _v_publish_server_generation(ctx: Context, step):
    """Replace the fixture host files and publish the next generation in the real server."""
    index = int(step.get("generation", 1))
    _write_server_generation(ctx, index)
    notes = str(_server_generation(ctx, index).get("patchNotes", "")).strip()
    previous_token, previous_head = _server_projection_state(ctx)
    command = ["rcon-cli", "automodpack", "generate"]
    if notes:
        command.extend(["notes", notes.replace("\n", " ")])
    result = _container(ctx.srv_name).exec_run(command)
    output = result.output.decode("utf-8", errors="replace") if result.output else ""
    if result.exit_code != 0:
        raise RuntimeError(f"server generation command failed ({result.exit_code}): {output}")

    def published_generation():
        token, head = _server_projection_state(ctx)
        if not token or (token, head) == (previous_token, previous_head):
            return None
        try:
            journal = _server_journal(ctx)
        except AssertionError:
            return None
        head_entry = next((entry for entry in reversed(journal) if int(entry.get("seq", -1)) == head), None)
        if head_entry is None or str(head_entry.get("contentToken", "")) != token or str(head_entry.get("notes", "")) != notes:
            return None
        return token

    published_id = await_condition(
        published_generation,
        parse_duration(step.get("timeout"), default=120),
        step.get("poll"),
        f"server generation {index} was not published",
    )
    ctx.vars["published_server_generation"] = index
    ctx.vars["published_server_generation_id"] = published_id


def _read_server_json(ctx: Context, relative: str, description: str) -> dict:
    path = ctx.server_dir / "automodpack" / "server" / relative
    try:
        value = json.loads(path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"{description} is not readable: {path}: {error}") from error
    if not isinstance(value, dict):
        raise AssertionError(f"{description} must be a JSON object: {path}")
    return value


def _server_projection_state(ctx: Context) -> tuple[str, int]:
    """The published (contentToken, journalHead) pair, or ("", -1) before the first publish."""
    try:
        projection = _read_server_json(ctx, "current-projection.json", "server current projection")
    except AssertionError:
        return "", -1
    return str(projection.get("contentToken", "")), int(projection.get("journalHead", -1))


def _server_journal(ctx: Context) -> list[dict]:
    path = ctx.server_dir / "automodpack" / "server" / "journal.jsonl"
    try:
        raw = path.read_text(encoding="utf-8")
    except (OSError, UnicodeDecodeError) as error:
        raise AssertionError(f"server journal is not readable: {path}: {error}") from error
    entries = []
    for line in raw.splitlines():
        if not line.strip():
            continue
        try:
            entry = json.loads(line)
        except ValueError as error:
            raise AssertionError(f"server journal has an unreadable entry: {path}: {error}") from error
        if not isinstance(entry, dict):
            raise AssertionError(f"server journal entries must be JSON objects: {path}")
        entries.append(entry)
    return entries


def _server_data_root_container(ctx: Context) -> Path:
    return Path("/data") / _SERVER_DATA_ROOT


def _server_object_measurement(ctx: Context, object_root: Path) -> tuple[int, int]:
    result = _container(ctx.srv_name).exec_run([
        "sh",
        "-c",
        'set -eu; count=0; bytes=0; for shard in "$1"/*; do [ -d "$shard" ] || continue; for path in "$shard"/*; do [ -f "$path" ] || continue; count=$((count + 1)); size=$(wc -c < "$path"); bytes=$((bytes + size)); done; done; printf "%s %s\\n" "$count" "$bytes"',
        "autotester",
        str(object_root),
    ])
    output = _exec_output(result)
    if result.exit_code != 0:
        raise RuntimeError(f"could not measure mounted server object store {object_root}: {output}")
    match = re.fullmatch(r"\s*(\d+)\s+(\d+)\s*", output)
    if match is None:
        raise AssertionError(f"mounted server object store returned an invalid measurement: {output!r}")
    return int(match.group(1)), int(match.group(2))


def _write_server_orphan_object(ctx: Context, object_root: Path) -> str:
    payload = b"autotester-orphan-object\n"
    object_hash = hashlib.sha1(payload).hexdigest()
    result = _container(ctx.srv_name).exec_run([
        "sh",
        "-c",
        'set -eu; shard="$1/$(printf %s "$3" | cut -c1-2)"; rest=$(printf %s "$3" | cut -c3-); mkdir -p "$shard"; chmod 777 "$shard"; dest="$shard/$rest"; if [ -e "$dest" ]; then test -f "$dest"; else printf "%s" "$2" > "$dest"; chmod 666 "$dest"; fi; test "$(sha1sum "$dest" | cut -d " " -f 1)" = "$3"',
        "autotester",
        str(object_root),
        payload.decode("utf-8"),
        object_hash,
    ])
    output = _exec_output(result)
    if result.exit_code != 0:
        raise RuntimeError(f"could not create the valid server object fixture {object_hash}: {output}")
    return object_hash


@verb("rollback_server_generation")
def _v_rollback_server_generation(ctx: Context, step):
    """Revert through the real RCON command to a journal entry and verify the durable restore."""
    journal = _server_journal(ctx)
    if len(journal) < 2:
        raise AssertionError("server rollback scenario needs at least two journal entries")
    head_seq = int(journal[-1].get("seq", -1))
    target_seq = int(step.get("seq", head_seq - 1))
    target = next((entry for entry in journal if int(entry.get("seq", -1)) == target_seq), None)
    if target is None:
        raise AssertionError(f"server journal has no entry {target_seq} to roll back to")
    target_token = str(target.get("contentToken", ""))
    target_policy = str(target.get("policySha1", ""))
    notes = str(step.get("notes", "Release gate rollback verification.")).strip()
    if not notes:
        raise AssertionError("server rollback notes must not be empty")
    command = ["rcon-cli", "automodpack", "generate", "revert", str(target_seq), "confirm", "notes", notes]
    result = _container(ctx.srv_name).exec_run(command)
    output = _exec_output(result)
    if result.exit_code != 0 or "unknown command" in output.lower() or "incorrect argument" in output.lower():
        raise RuntimeError(f"server generation rollback command was rejected: {output}")

    def completed_state():
        try:
            after_journal = _server_journal(ctx)
            after_projection = _read_server_json(ctx, "current-projection.json", "server rollback projection")
        except AssertionError:
            return None
        if len(after_journal) != len(journal) + 1:
            return None
        restore = after_journal[-1]
        if int(restore.get("restoreOf", -2)) != target_seq:
            return None
        if str(restore.get("contentToken", "")) != target_token or str(restore.get("policySha1", "")) != target_policy:
            return None
        if str(after_projection.get("contentToken", "")) != target_token or int(after_projection.get("journalHead", -1)) != int(restore.get("seq", -1)):
            return None
        return restore, after_projection

    restore, after_projection = await_condition(
        completed_state,
        parse_duration(step.get("timeout"), default=180),
        step.get("poll"),
        "server rollback did not append a durable restore entry and projection",
    )
    ctx.vars["server_rollback"] = {
        "targetSeq": target_seq,
        "restoreSeq": int(restore["seq"]),
        "contentToken": str(restore["contentToken"]),
        "journalHead": int(after_projection["journalHead"]),
        "notes": str(restore["notes"]),
        "journalEntries": len(journal) + 1,
        "command": command,
    }


@verb("collect_server_objects")
def _v_collect_server_objects(ctx: Context, step):
    """Create a valid mounted orphan, run real RCON collection, and verify its receipt."""
    object_root = _server_data_root_container(ctx) / "objects"
    orphan_hash = _write_server_orphan_object(ctx, object_root)
    before_count, before_bytes = _server_object_measurement(ctx, object_root)
    result = _container(ctx.srv_name).exec_run(["rcon-cli", "automodpack", "generate", "storage", "collect", "confirm"])
    output = _exec_output(result)
    if result.exit_code != 0 or "unknown command" in output.lower() or "incorrect argument" in output.lower():
        raise RuntimeError(f"server object collection command was rejected: {output}")
    objects_match = re.search(r"Objects\s*-\s*(\d+)\s*->\s*(\d+)", output)
    bytes_match = re.search(r"Bytes\s*-\s*(\d+)\s*->\s*(\d+)", output)
    deleted_match = re.search(r"Deleted\s*-\s*(\d+)\s+objects,\s*(\d+)\s+bytes", output)
    if not objects_match or not bytes_match or not deleted_match:
        raise AssertionError(f"server object collection did not return its durable receipt fields: {output!r}")
    receipt = {
        "beforeCount": int(objects_match.group(1)),
        "afterCount": int(objects_match.group(2)),
        "beforeBytes": int(bytes_match.group(1)),
        "afterBytes": int(bytes_match.group(2)),
        "deletedCount": int(deleted_match.group(1)),
        "deletedBytes": int(deleted_match.group(2)),
    }
    if receipt["deletedCount"] <= 0 or receipt["deletedBytes"] <= 0:
        raise AssertionError(f"server object collection reported no deletion for orphan {orphan_hash}: {output}")

    def completed_state():
        after_count, after_bytes = _server_object_measurement(ctx, object_root)
        orphan_exists = _container(ctx.srv_name).exec_run(["test", "-e", str(cas_object(object_root, orphan_hash))]).exit_code == 0
        if orphan_exists or (after_count, after_bytes) != (receipt["afterCount"], receipt["afterBytes"]):
            return None
        return after_count, after_bytes

    after_count, after_bytes = await_condition(
        completed_state,
        parse_duration(step.get("timeout"), default=120),
        step.get("poll"),
        "server object collection did not durably remove the valid orphan object",
    )
    if (before_count, before_bytes) != (receipt["beforeCount"], receipt["beforeBytes"]):
        raise AssertionError(f"server object collection receipt disagrees with mounted-store measurement: {output}")
    if receipt["beforeCount"] - receipt["afterCount"] != receipt["deletedCount"] or receipt["beforeBytes"] - receipt["afterBytes"] != receipt["deletedBytes"]:
        raise AssertionError(f"server object collection receipt has inconsistent object transition: {output}")
    ctx.vars["server_object_collection"] = {
        **receipt,
        "mountedObjectRoot": str(object_root),
        "orphanHash": orphan_hash,
        "measuredAfterCount": after_count,
        "measuredAfterBytes": after_bytes,
        "command": ["rcon-cli", "automodpack", "generate", "storage", "collect", "confirm"],
    }


@verb("compact_server_history")
def _v_compact_server_history(ctx: Context, step):
    """Compact the live journal under a snapshot entry and verify the durable projection."""
    journal = _server_journal(ctx)
    if len(journal) < 2:
        raise AssertionError("server compaction scenario needs at least two journal entries")
    boundary_seq = int(step.get("beforeSeq", journal[-1].get("seq", 1)))
    command = ["rcon-cli", "automodpack", "generate", "storage", "compact", "before", str(boundary_seq), "confirm"]
    result = _container(ctx.srv_name).exec_run(command)
    output = result.output.decode("utf-8", errors="replace") if result.output else ""
    if result.exit_code != 0:
        raise RuntimeError(f"server history compaction command failed ({result.exit_code}): {output}")
    if "incorrect argument for command" in output.lower() or "unknown command" in output.lower():
        raise RuntimeError(f"server history compaction command was rejected: {output}")

    def completed_state():
        try:
            after_journal = _server_journal(ctx)
            after_projection = _read_server_json(ctx, "current-projection.json", "server compaction projection")
        except AssertionError:
            return None
        if not _completed_compaction(journal, after_journal, after_projection):
            return None
        return after_journal, after_projection

    after_journal, _after_projection = await_condition(
        completed_state,
        parse_duration(step.get("timeout"), default=120),
        step.get("poll"),
        "server history compaction did not rewrite the journal under a snapshot entry",
    )
    ctx.vars["server_compaction"] = {"removedEntries": len(journal) - len(after_journal), "entriesBefore": len(journal), "entriesAfter": len(after_journal)}


def _completed_compaction(entries_before: list[dict], entries_after: list[dict], projection: dict) -> bool:
    """Whether the journal was rewritten as a head snapshot followed by renumbered survivors."""
    if not entries_after or len(entries_after) >= len(entries_before):
        return False
    snapshot = entries_after[0]
    if not snapshot.get("snapshot") or int(snapshot.get("seq", 0)) != 1:
        return False
    seqs = [int(entry.get("seq", 0)) for entry in entries_after]
    if seqs != list(range(1, len(seqs) + 1)):
        return False
    head_token = str(entries_before[-1].get("contentToken", ""))
    if str(entries_after[-1].get("contentToken", "")) != head_token or str(projection.get("contentToken", "")) != head_token:
        return False
    return int(projection.get("journalHead", -1)) == seqs[-1]


@verb("seed_bootstrap")
def _v_seed_bootstrap(ctx: Context, step):
    """Write a real game-root bootstrap file from the live server state."""
    fingerprint = str(ctx.vars.get("fingerprint", "")).strip()
    if not fingerprint:
        raise RuntimeError("seed_bootstrap requires read_server_fingerprint first")
    projection_path = ctx.server_dir / "automodpack" / "server" / "current-projection.json"
    config_path = ctx.server_dir / "automodpack" / "server-config.json"
    try:
        projection = json.loads(projection_path.read_text(encoding="utf-8"))
        server_config = json.loads(config_path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise RuntimeError(f"live server bootstrap state is not readable: {error}") from error
    modpack_id = str((projection.get("policy", {}) or {}).get("modpackId", "")).strip()
    if not modpack_id:
        raise RuntimeError(f"live server projection has no modpackId: {projection_path}")
    origin = str(ctx.resolve(step.get("origin", "${server.host}"))).strip()
    endpoint = str(ctx.resolve(step.get("endpoint", "${server.endpoint}"))).strip()
    connection_mode = str(step.get("connectionMode") or server_config.get("connectionMode") or "").strip().upper()
    if not origin or not endpoint or not connection_mode:
        raise RuntimeError("bootstrap requires origin, endpoint, and connectionMode")
    fields = {
        "origin": origin,
        "fingerprint": fingerprint,
        "modpackId": modpack_id,
        "endpoint": endpoint,
        "connectionMode": connection_mode,
    }
    bootstrap_path = ctx.game_dir / "automodpack" / "automodpack-bootstrap.json"
    bootstrap_path.parent.mkdir(parents=True, exist_ok=True)
    bootstrap_path.write_text(json.dumps(fields, indent=2) + "\n", encoding="utf-8")
    ctx.vars.update({
        "bootstrap_origin": origin,
        "bootstrap_endpoint": endpoint,
        "bootstrap_fingerprint": fingerprint,
        "bootstrap_modpack_id": modpack_id,
        "bootstrap_connection_mode": connection_mode,
        "bootstrap_path": "automodpack/automodpack-bootstrap.json",
    })
