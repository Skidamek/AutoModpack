"""Server lifecycle verbs and live server-state inspection (journal, projection, object store)."""
from __future__ import annotations

from automodpack_autotester import reconf_min

import hashlib
import json
import re
import zipfile
from pathlib import Path, PurePosixPath

from .mod_fixtures import valid_mod_jar_bytes
from .supervisor import resource_labels
from .config import expand_generated, server_cache_volume
from .engine import Context
from .engine.registry import verb
from .engine.util import await_condition, parse_duration
from .runtime.base import ServerSpec, ServerWorkspace, StageTree, StagedFile


_SERVER_DATA_ROOT = PurePosixPath("automodpack/data")


def _workspace(ctx: Context) -> ServerWorkspace:
    if ctx.server_workspace is None:
        raise RuntimeError("no server workspace is attached to this case")
    return ctx.server_workspace


def _prepare_server(ctx: Context):
    workspace = _workspace(ctx)
    workspace.copy_in("mods/automodpack.jar", ctx.artifact)
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
    workspace.write_text("automodpack/server-config.json", json.dumps(cfg, indent=2))
    workspace.ensure_dir(_SERVER_DATA_ROOT.as_posix())
    _write_server_generation(ctx, 0)


def _server_generation(ctx: Context, index: int) -> dict:
    generations = (ctx.scenario.get("serverFiles", {}) or {}).get("generations") or []
    if not generations:
        if index != 0:
            raise ValueError(f"scenario has no server generation {index}")
        files = []
        for hosted in ctx.scenario_files:
            item = {"path": str(hosted.path)}
            if hosted.size_bytes is not None:
                item["sizeBytes"] = hosted.size_bytes
            else:
                item["content"] = hosted.content
            files.append(item)
        return {"files": files}
    if not isinstance(generations, list) or index < 0 or index >= len(generations):
        raise ValueError(f"server generation index {index} is outside the declared generations")
    generation = generations[index]
    if not isinstance(generation, dict):
        raise ValueError(f"serverFiles.generations[{index}] must be a mapping")
    return generation


def _write_server_generation(ctx: Context, index: int) -> None:
    generation = _server_generation(ctx, index)
    declared_music = (ctx.scenario.get("serverFiles", {}) or {}).get("waitingMusic")
    if declared_music and "waitingMusic" not in generation:
        generation = {**generation, "waitingMusic": declared_music}
    bulk = expand_generated(generation.get("generated"))
    if bulk:
        # Per-generation bulk fixtures expand exactly like the single-generation base, so a benchmark pack can
        # carry its bulk through a generation bump without spelling out every file.
        generation = {
            **generation,
            "files": [
                *(generation.get("files") or []),
                *({"path": str(hosted.path), "sizeBytes": hosted.size_bytes, "group": "main"} for hosted in bulk),
            ],
        }
    entries = [
        StagedFile(
            path=(Path("main") / ctx.marker_rel).as_posix(),
            content=(json.dumps({"marker": ctx.modpack_name}) + "\n").encode("utf-8"),
        )
    ]
    config = ((ctx.scenario.get("topology", {}).get("server", {}).get("automodpack", {}) or {}).get("config", {}) or {})
    configured_groups = {
        str(group_id)
        for category in (config.get("modpack", {}) or {}).values() if isinstance(category, dict)
        for group_id in category
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
        logical = rel.as_posix()
        target = f"{group_id}/{logical}"
        fixture = item.get("fixture")
        if fixture is not None:
            if not isinstance(fixture, dict):
                raise ValueError(f"server generation fixture for {rel} must be a mapping")
            entries.append(StagedFile(path=target, content=valid_mod_jar_bytes(fixture, ctx.target.minecraft)))
        elif "sizeBytes" in item:
            size_bytes = item["sizeBytes"]
            if not isinstance(size_bytes, int) or isinstance(size_bytes, bool) or size_bytes < 0:
                raise ValueError(f"server generation sizeBytes for {rel} must be a non-negative integer")
            # The fill is seeded by the logical path inside the group, so moving a
            # file between groups must not change its bytes.
            entries.append(StagedFile(path=target, size_bytes=size_bytes, fill_name=logical))
        else:
            entries.append(StagedFile(path=target, content=str(item.get("content", "")).encode("utf-8")))
    patch_notes = generation.get("patchNotes", "")
    entries.append(StagedFile(path="patch-notes.md", content=str(patch_notes).encode("utf-8")))
    _workspace(ctx).stage_tree(
        StageTree(root="automodpack/host-modpack", files=tuple(entries), replace=True)
    )
    music = generation.get("waitingMusic")
    if music == "client-jar":
        # Re-host the mod's own bundled track: no binary lands in the repo, and the
        # custom track always matches what a client without one would hear by default.
        with zipfile.ZipFile(ctx.artifact) as jar:
            name = next(n for n in jar.namelist() if n.endswith("sounds/music/waiting.ogg"))
            _workspace(ctx).write_bytes("automodpack/waiting-music.ogg", jar.read(name))


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
    env["AUTOMODPACK_DATA_ROOT"] = _SERVER_DATA_ROOT.as_posix()
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
    cache_volume = None
    if sc.get("enabled", True):
        cache_volume = server_cache_volume(target.id, sc.get("volumePrefix", "amp-server-cache"))
    mounts = _workspace(ctx).data_mounts(cache_volume)
    img = str(topo.get("image") or settings.get("images", {}).get("server", "itzg/minecraft-server"))
    if ":" not in img:
        tag = str(settings.get("images", {}).get("serverTagTemplate", "java{java}")).format(java=target.java)
        img = f"{img}:{tag}"
    if ctx.server is None:
        raise RuntimeError("no server runtime is attached to this case")
    ctx.server.start(
        ctx,
        ServerSpec(
            image=img,
            env=env,
            mounts=tuple(mounts),
            # NET_ADMIN is only needed so the --loss / --server-netem qdiscs can be applied from inside; never granted otherwise.
            cap_add=("NET_ADMIN",) if ctx.loss or ctx.server_netem else (),
            cache_volume=cache_volume,
            clean_cache=bool(sc.get("clean", False)),
            labels=resource_labels(ctx.resource_scope),
        ),
    )


@verb("launch_server")
def _v_launch_server(ctx: Context, step):
    _launch_server(ctx)


@verb("write_server_conf")
def _v_write_server_conf(ctx: Context, step):
    """Stage an explicit server.conf before boot; its presence makes the staged JSON predecessor inert."""
    _workspace(ctx).write_text("automodpack/server.conf", str(step.get("content", "")))


@verb("assert_server_conf")
def _v_assert_server_conf(ctx: Context, step):
    """Assert the live server.conf: dotted-path expectations, absent paths, and raw substrings."""
    rel = "automodpack/server.conf"
    try:
        text = _workspace(ctx).read_text(rel)
        config = reconf_min.read_config_text(text)
    except (OSError, ValueError) as error:
        raise RuntimeError(f"server.conf is not readable at {rel}: {error}") from error

    def walk(dotted):
        node = config
        for segment in str(dotted).split("."):
            if not isinstance(node, dict) or segment not in node:
                return None, False
            node = node[segment]
        return node, True

    for dotted, expected in (step.get("expect") or {}).items():
        actual, present = walk(dotted)
        if not present:
            raise RuntimeError(f"server.conf has no value at {dotted}; actual file:\n{text}")
        if actual != expected:
            raise RuntimeError(f"server.conf at {dotted}: expected {expected!r}, got {actual!r}; actual file:\n{text}")
    for dotted in step.get("absent") or []:
        _, present = walk(dotted)
        if present:
            raise RuntimeError(f"server.conf still has {dotted}; actual file:\n{text}")
    for needle in step.get("textContains") or []:
        if str(needle) not in text:
            raise RuntimeError(f"server.conf is missing {needle!r}; actual file:\n{text}")


@verb("wait_server")
def _v_wait_server(ctx: Context, step):
    to = ctx.scenario.get("timeouts", {}) or ctx.settings.get("timeouts", {})
    timeout = parse_duration(step.get("timeout"), default=float(to.get("serverStartSeconds", 180)))
    ctx.server.wait_for_log("Done (", timeout=timeout)


@verb("restart_server")
def _v_restart_server(ctx: Context, step):
    """Restart the server container in place; persisted state stays, so the modpack host must come back on its own."""
    to = ctx.scenario.get("timeouts", {}) or ctx.settings.get("timeouts", {})
    timeout = parse_duration(step.get("timeout"), default=float(to.get("serverStartSeconds", 180)))
    # Container stdout survives a restart, so readiness is a *new* "Done (" line, not any old one.
    booted = ctx.server.logs().count("Done (")
    ctx.server.restart(timeout=60)
    await_condition(lambda: ctx.server.logs().count("Done (") > booted or None, timeout, msg="server did not finish loading after restart")


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
    result = ctx.server.exec(command)
    output = result.output
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
    rel = f"automodpack/server/{relative}"
    try:
        value = json.loads(_workspace(ctx).read_text(rel))
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"{description} is not readable: {rel}: {error}") from error
    if not isinstance(value, dict):
        raise AssertionError(f"{description} must be a JSON object: {rel}")
    return value


def _server_projection_state(ctx: Context) -> tuple[str, int]:
    """The published (contentToken, journalHead) pair, or ("", -1) before the first publish."""
    try:
        projection = _read_server_json(ctx, "current-projection.json", "server current projection")
    except AssertionError:
        return "", -1
    return str(projection.get("contentToken", "")), int(projection.get("journalHead", -1))


def _server_journal(ctx: Context) -> list[dict]:
    rel = "automodpack/server/journal.jsonl"
    try:
        raw = _workspace(ctx).read_text(rel)
    except (OSError, UnicodeDecodeError) as error:
        raise AssertionError(f"server journal is not readable: {rel}: {error}") from error
    entries = []
    for line in raw.splitlines():
        if not line.strip():
            continue
        try:
            entry = json.loads(line)
        except ValueError as error:
            raise AssertionError(f"server journal has an unreadable entry: {rel}: {error}") from error
        if not isinstance(entry, dict):
            raise AssertionError(f"server journal entries must be JSON objects: {rel}")
        entries.append(entry)
    return entries


_ORPHAN_PAYLOAD = b"autotester-orphan-object\n"


def _server_object_root() -> str:
    return (_SERVER_DATA_ROOT / "objects").as_posix()


def _server_object_rel(object_root: str, object_hash: str) -> str:
    return f"{object_root}/{object_hash[:2]}/{object_hash[2:]}"


def _write_server_orphan_object(ctx: Context, object_root: str) -> str:
    object_hash = hashlib.sha1(_ORPHAN_PAYLOAD).hexdigest()
    rel = _server_object_rel(object_root, object_hash)
    workspace = _workspace(ctx)
    if workspace.exists(rel):
        try:
            matches = workspace.read_text(rel) == _ORPHAN_PAYLOAD.decode("utf-8")
        except (OSError, UnicodeDecodeError):
            matches = False
        if not matches:
            raise RuntimeError(f"existing server object {object_hash} does not match its address")
    else:
        workspace.write_bytes(rel, _ORPHAN_PAYLOAD)
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
    result = ctx.server.exec(command)
    output = result.output
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
    """Create a valid workspace orphan, run real RCON collection, and verify its receipt."""
    object_root = _server_object_root()
    orphan_hash = _write_server_orphan_object(ctx, object_root)
    orphan_rel = _server_object_rel(object_root, orphan_hash)
    before = _workspace(ctx).measure_tree(object_root)
    result = ctx.server.exec(["rcon-cli", "automodpack", "generate", "storage", "collect", "confirm"])
    output = result.output
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
        workspace = _workspace(ctx)
        after = workspace.measure_tree(object_root)
        orphan_exists = workspace.exists(orphan_rel)
        if orphan_exists or (after.count, after.bytes) != (receipt["afterCount"], receipt["afterBytes"]):
            return None
        return after

    after = await_condition(
        completed_state,
        parse_duration(step.get("timeout"), default=120),
        step.get("poll"),
        "server object collection did not durably remove the valid orphan object",
    )
    if (before.count, before.bytes) != (receipt["beforeCount"], receipt["beforeBytes"]):
        raise AssertionError(f"server object collection receipt disagrees with workspace measurement: {output}")
    if receipt["beforeCount"] - receipt["afterCount"] != receipt["deletedCount"] or receipt["beforeBytes"] - receipt["afterBytes"] != receipt["deletedBytes"]:
        raise AssertionError(f"server object collection receipt has inconsistent object transition: {output}")
    ctx.vars["server_object_collection"] = {
        **receipt,
        "objectRoot": object_root,
        "orphanHash": orphan_hash,
        "measuredAfterCount": after.count,
        "measuredAfterBytes": after.bytes,
        "command": ["rcon-cli", "automodpack", "generate", "storage", "collect", "confirm"],
    }


@verb("seed_bootstrap")
def _v_seed_bootstrap(ctx: Context, step):
    """Write a real game-root bootstrap file from the live server state."""
    fingerprint = str(ctx.vars.get("fingerprint", "")).strip()
    if not fingerprint:
        raise RuntimeError("seed_bootstrap requires read_server_fingerprint first")
    workspace = _workspace(ctx)
    projection_rel = "automodpack/server/current-projection.json"
    config_rel = "automodpack/server.conf"
    if not workspace.exists(config_rel):
        config_rel = "automodpack/server-config.json"
    try:
        projection = json.loads(workspace.read_text(projection_rel))
        server_config = reconf_min.read_config_text(
            workspace.read_text(config_rel), json_format=config_rel.endswith(".json")
        )
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise RuntimeError(f"live server bootstrap state is not readable: {error}") from error
    modpack_id = str((projection.get("policy", {}) or {}).get("modpackId", "")).strip()
    if not modpack_id:
        raise RuntimeError(f"live server projection has no modpackId: {projection_rel}")
    origin = str(ctx.resolve(step.get("origin", "${server.host}"))).strip()
    endpoint = str(ctx.resolve(step.get("endpoint", "${server.endpoint}"))).strip()
    connection_mode = str(step.get("connectionMode") or server_config.get("connection-mode") or server_config.get("connectionMode") or "").strip().upper()
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
    bootstrap_path.write_text(json.dumps(fields, indent=2) + "\n", encoding="utf-8", newline="")
    ctx.vars.update({
        "bootstrap_origin": origin,
        "bootstrap_endpoint": endpoint,
        "bootstrap_fingerprint": fingerprint,
        "bootstrap_modpack_id": modpack_id,
        "bootstrap_connection_mode": connection_mode,
        "bootstrap_path": "automodpack/automodpack-bootstrap.json",
    })
