from __future__ import annotations

import hashlib
import json
import logging
import os
import random
import re
import secrets
import shutil
import threading
import time
import zipfile
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

import docker as docker_py

from .bridge import BridgeClient
from .config import CLIENT_GENERATION_STATE_PATHS, REPO_ROOT, Target, load_macros, parse_server_files
from .mod_fixtures import write_valid_mod_fixture
from .mods import resolve_mod
from .supervisor import resource_labels
from .engine import ClientExited, Context, run_flow
from .engine.registry import verb
from .engine.util import await_condition, parse_duration
from .generation_identity import CanonicalEncoder, write_strings


logger = logging.getLogger(__name__)

_docker = docker_py.from_env()


# ── low-level docker / container helpers ──────────────────────────────────


def _jitter_sleep(base, fraction=0.2):
    time.sleep(random.uniform(base * (1 - fraction), base * (1 + fraction)))


def _container(name):
    return _docker.containers.get(name)


def _container_logs(name, tail=None):
    try:
        kwargs = {}
        if tail is not None:
            kwargs["tail"] = tail
        return _container(name).logs(**kwargs).decode("utf-8", errors="replace")
    except docker_py.errors.NotFound:
        return ""


def _remove_container(name):
    try:
        _container(name).remove(force=True)
    except docker_py.errors.NotFound:
        pass


def _ensure_network(name, labels=None):
    _remove_network(name)
    _docker.networks.create(name, check_duplicate=True, labels=labels or {})


def _remove_network(name):
    try:
        _docker.networks.get(name).remove()
    except docker_py.errors.NotFound:
        pass


def _ensure_volume(name):
    _docker.volumes.create(name)


def _remove_volume(name):
    try:
        _docker.volumes.get(name).remove()
    except docker_py.errors.NotFound:
        pass


def _run_container(name, image, network, env, mounts, command=None, user=None, entrypoint=None, labels=None):
    volumes = {}
    for host, container_path, readonly in mounts:
        volumes[str(host)] = {"bind": container_path, "mode": "ro" if readonly else "rw"}
    kwargs = dict(
        image=image, detach=True, name=name,
        environment=dict(env), volumes=volumes, command=command, user=user, labels=labels or {},
    )
    # "host" is a network *mode*, not a user-defined network: server and client
    # share the host's network namespace (so the client reaches the server on
    # localhost). This is the only topology a --network-host-only sandbox allows.
    if network == "host":
        kwargs["network_mode"] = "host"
    else:
        kwargs["network"] = network
    if entrypoint is not None:
        kwargs["entrypoint"] = entrypoint
    return _docker.containers.run(**kwargs)


def _assert_running(name):
    c = _container(name)
    c.reload()
    state = c.attrs.get("State", {})
    if not state.get("Running", False):
        raise RuntimeError(
            f"Container {name} exited (code={state.get('ExitCode', -1)}, error={state.get('Error', '')})"
        )


def _is_connecting_screen(screen: str) -> bool:
    # Fabric 1.20.1's generated intermediary mapping names ConnectScreen class_412.
    return "FirstConnectScreen" not in screen and any(name in screen for name in ("ConnectScreen", "class_397", "class_412"))


def _is_connection_failure_screen(screen: str) -> bool:
    # Fabric 1.20.1's generated intermediary mapping names DisconnectedScreen class_419.
    return "DisconnectedScreen" in screen or "class_419" in screen


def _inspect_container(name):
    return _container(name).attrs


def _wait_for_log(name, needle, timeout):
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        if needle in _container_logs(name, tail=200):
            return
        _assert_running(name)
        _jitter_sleep(2)
    raise TimeoutError(f"Timeout waiting for {needle!r} in {name}")


def _wait_exited(name, timeout):
    c = _container(name)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        c.reload()
        if not c.attrs.get("State", {}).get("Running", False):
            return
        _jitter_sleep(1)
    raise TimeoutError(f"Timeout waiting for {name} to exit")


def _uid():
    return int(os.environ.get("AUTOTEST_DOCKER_UID", os.getuid()))


def _gid():
    return int(os.environ.get("AUTOTEST_DOCKER_GID", os.getgid()))


def _load_ver(t):
    if t.loader == "fabric":
        return getattr(t, "fabric_loader", None) or ""
    if t.loader == "forge":
        return getattr(t, "forge_version", None) or ""
    if t.loader == "neoforge":
        return getattr(t, "neoforge_version", None) or ""
    return ""


def _bridge_state(ctx: Context) -> Path:
    return ctx.game_dir / "automodpack" / "autotest" / "bridge-state.json"


_CLIENT_DATA_ROOT = Path("automodpack/client/data")
_SERVER_DATA_ROOT = Path("automodpack/data")


def cas_object(objects_dir: Path, sha1: str) -> Path:
    digest = str(sha1).lower()
    return objects_dir / digest[:2] / digest[2:]


def _ensure_client_data_root(game_dir: Path) -> Path:
    """Create the one local data root used by every client lifecycle step."""
    data_root = game_dir / _CLIENT_DATA_ROOT
    data_root.mkdir(parents=True, exist_ok=True)
    return data_root


def _exit_code(name) -> int | None:
    try:
        return _inspect_container(name).get("State", {}).get("ExitCode")
    except docker_py.errors.NotFound:
        return None


def transport(scenario: dict, settings: dict) -> str:
    """How containers talk: ``bridge`` (CI default) or ``host`` (constrained envs).

    Decoupled from flow logic, so the same scenario runs either way. Precedence:
    scenario ``network:`` > settings ``network:`` / ``run.network:`` > ``bridge``.
    """
    val = (
        scenario.get("network")
        or settings.get("network")
        or settings.get("run", {}).get("network")
        or "bridge"
    )
    return str(val).lower()


def scenario_mode(scenario: dict) -> str:
    """``full`` (server + client) or ``client-only`` (pre-staged, no server)."""
    return str(scenario.get("mode", "full")).lower()


# ── server / client setup ─────────────────────────────────────────────────


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


def _seed_client_options(game_dir: Path) -> None:
    """Keep the client's options and suppress the one-time vanilla warning."""
    options_path = game_dir / "options.txt"
    if options_path.exists():
        content = options_path.read_text(encoding="utf-8")
    else:
        content = "narrator:0\n"

    lines = content.splitlines(keepends=True)
    updated = []
    found = False
    for line in lines:
        body = line.rstrip("\r\n")
        key, separator, _value = body.partition(":")
        if separator and key == "skipMultiplayerWarning":
            newline = line[len(body):]
            updated.append(f"skipMultiplayerWarning:true{newline}")
            found = True
        else:
            updated.append(line)
    if not found:
        if updated and not updated[-1].endswith(("\n", "\r")):
            updated[-1] += "\n"
        updated.append("skipMultiplayerWarning:true\n")
    options_path.write_text("".join(updated), encoding="utf-8")


def _prepare_client_files(ctx: Context) -> None:
    game_dir = ctx.game_dir
    (game_dir / "mods").mkdir(parents=True, exist_ok=True)
    shutil.copy2(ctx.artifact, game_dir / "mods" / "automodpack.jar")
    _stage_client_runtime_mods(ctx)
    _seed_client_options(game_dir)
    _ensure_client_data_root(game_dir)
    _bridge_state(ctx).unlink(missing_ok=True)


def _client_cache_paths(ctx: Context) -> tuple[Path, Path]:
    # Per-target HMC cache (isolated to prevent concurrent NeoForge installer corruption)
    tid = ctx.target.id.replace(".", "_")
    hmc_cache_root = (ctx.out_dir.parent / ".hmc-cache" / tid).resolve()
    hmc_cache_root.mkdir(parents=True, exist_ok=True)
    # The client image symlinks <cache>/versions -> /work/hmc-shared-versions. Mount
    # a persistent host dir there so downloaded version jars survive between runs
    # instead of being re-fetched every time (the symlink target was unmounted, so
    # versions landed in ephemeral container storage). Kept per-target — sharing one
    # dir across parallel targets would reintroduce the installer-corruption race.
    shared_versions = (ctx.out_dir.parent / ".hmc-cache" / "shared-versions" / tid).resolve()
    shared_versions.mkdir(parents=True, exist_ok=True)
    return hmc_cache_root, shared_versions


def _start_client_container(ctx: Context, name: str, *, prepare_only: bool = False) -> None:
    hmc_cache_root, shared_versions = _client_cache_paths(ctx)
    prepared = _client_profile_is_prepared(ctx, hmc_cache_root, shared_versions)

    _run_container(
        name=name,
        image=ctx.client_image,
        network=ctx.net_name,
        env={
            "AM_AUTOTEST_BRIDGE_TOKEN": ctx.token,
            "AM_AUTOTEST_GAME_DIR": "/work/game",
            "AM_AUTOTEST_HMC_CACHE_DIR": "/work/hmc-cache",
            "AM_AUTOTEST_HMC_PREPARED": str(prepared).lower(),
            "AM_AUTOTEST_PREPARE_ONLY": str(prepare_only).lower(),
            "AM_AUTOTEST_DISPLAY_START_SECONDS": str(ctx.settings.get("timeouts", {}).get("displayStartSeconds", 5)),
            "AM_AUTOTEST_RENDER_CLIENT": str(bool(ctx.scenario.get("renderClient", False)) and not prepare_only).lower(),
            "AUTOMODPACK_DATA_ROOT": str(_CLIENT_DATA_ROOT),
            "JAVA_TOOL_OPTIONS": "-Xmx2G",
        },
        mounts=[
            (ctx.game_dir, "/work/game", False),
            (hmc_cache_root, "/work/hmc-cache", False),
            (shared_versions, "/work/hmc-shared-versions", False),
        ],
        command=[
            "/opt/automodpack/run-headlessmc-client",
            ctx.target.loader,
            ctx.target.minecraft,
            "localhost",
            "25565",
            str(ctx.target.java),
            _load_ver(ctx.target),
        ],
        user=f"{_uid()}:{_gid()}",
        entrypoint=["/usr/bin/tini", "--"],
        labels=resource_labels(ctx.resource_scope),
    )


def _launch_client(ctx: Context):
    _prepare_client_files(ctx)
    _start_client_container(ctx, ctx.cli_name)
    _jitter_sleep(1)
    _assert_running(ctx.cli_name)


def _stage_client_runtime_mods(ctx: Context) -> None:
    entries = (ctx.settings.get("clientRuntimeMods", {}) or {}).get(ctx.target.id, []) or []
    staged = []
    for entry in entries:
        mod = resolve_mod(entry, ctx.resolve, target_id=ctx.target.id, timeout=float(ctx.settings.get("timeouts", {}).get("downloadFileSeconds", 180)))
        destination = ctx.game_dir / "mods" / mod.name
        shutil.copy2(mod, destination)
        staged.append(destination)
    ctx.vars["client_runtime_mods"] = staged


def _detach_client_runtime_mods(ctx: Context) -> None:
    for path in ctx.vars.pop("client_runtime_mods", []):
        path.unlink(missing_ok=True)


def _client_profile_name(target: Target) -> str:
    loader_version = _load_ver(target)
    if target.loader == "fabric" and loader_version:
        return f"fabric-loader-{loader_version}-{target.minecraft}"
    if target.loader == "forge" and loader_version:
        return f"{target.minecraft}-forge-{loader_version}"
    if target.loader == "neoforge" and loader_version:
        return f"neoforge-{loader_version}"
    return f"{target.loader}:{target.minecraft}"


def _client_profile_identity(ctx: Context) -> dict:
    try:
        image_id = _docker.images.get(ctx.client_image).id
    except docker_py.errors.ImageNotFound:
        image_id = ctx.client_image
    return {
        "minecraft": ctx.target.minecraft,
        "loader": ctx.target.loader,
        "loaderVersion": _load_ver(ctx.target),
        "java": ctx.target.java,
        "clientImage": image_id,
        "profile": _client_profile_name(ctx.target),
    }


def _client_profile_receipt(hmc_cache_root: Path) -> Path:
    return hmc_cache_root / "prepared-profile.json"


def _client_profile_is_prepared(ctx: Context, hmc_cache_root: Path, shared_versions: Path) -> bool:
    profile = _client_profile_name(ctx.target)
    profile_json = shared_versions / profile / f"{profile}.json"
    try:
        return profile_json.is_file() and json.loads(_client_profile_receipt(hmc_cache_root).read_text(encoding="utf-8")) == _client_profile_identity(ctx)
    except (OSError, TypeError, ValueError, json.JSONDecodeError):
        return False


def _record_prepared_client_profile(ctx: Context) -> None:
    hmc_cache_root, _shared_versions = _client_cache_paths(ctx)
    receipt = _client_profile_receipt(hmc_cache_root)
    temporary = receipt.with_suffix(".tmp")
    temporary.write_text(json.dumps(_client_profile_identity(ctx), sort_keys=True) + "\n", encoding="utf-8")
    os.replace(temporary, receipt)


# ── lifecycle verbs (need Docker; pure UI/IO verbs live in engine/) ───────


@verb("launch_server")
def _v_launch_server(ctx: Context, step):
    _launch_server(ctx)


def _client_preparation_name(ctx: Context) -> str:
    return ctx.cli_name.replace("-c-", "-p-", 1)


@verb("prepare_client")
def _v_prepare_client(ctx: Context, _step):
    """Materialise the HMC loader profile while an independent server starts."""
    hmc_cache_root, shared_versions = _client_cache_paths(ctx)
    if _client_profile_is_prepared(ctx, hmc_cache_root, shared_versions):
        return
    _prepare_client_files(ctx)
    name = _client_preparation_name(ctx)
    _remove_container(name)
    _start_client_container(ctx, name, prepare_only=True)
    ctx.vars["client_preparation"] = name


def _await_client_preparation(ctx: Context) -> None:
    name = ctx.vars.pop("client_preparation", None)
    if not name:
        return
    timeout = float(ctx.settings.get("timeouts", {}).get("clientStartSeconds", 600))
    try:
        _wait_exited(name, timeout)
        state = _inspect_container(name).get("State", {})
        if state.get("ExitCode") != 0:
            tail = "\n".join(_container_logs(name).splitlines()[-80:])
            raise RuntimeError(f"Client profile preparation failed (code={state.get('ExitCode', -1)})\n--- logs ---\n{tail}")
        _record_prepared_client_profile(ctx)
        hmc_cache_root, shared_versions = _client_cache_paths(ctx)
        if not _client_profile_is_prepared(ctx, hmc_cache_root, shared_versions):
            raise RuntimeError("Client profile preparation exited successfully without a valid profile receipt")
    finally:
        _remove_container(name)


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
    previous_id = str(_read_server_json(ctx, "current.json", "server current pointer").get("generationId", ""))
    command = ["rcon-cli", "automodpack", "generate"]
    if notes:
        command.extend(["notes", notes.replace("\n", " ")])
    result = _container(ctx.srv_name).exec_run(command)
    output = result.output.decode("utf-8", errors="replace") if result.output else ""
    if result.exit_code != 0:
        raise RuntimeError(f"server generation command failed ({result.exit_code}): {output}")

    def published_generation():
        try:
            pointer = _read_server_json(ctx, "current.json", "server current pointer")
            generation_id = str(pointer.get("generationId", ""))
            if not generation_id or generation_id == previous_id:
                return None
            generation = _read_server_json(ctx, f"commits/{generation_id}.json", "published server generation")
        except AssertionError:
            return None
        if str(generation.get("generationId", "")) != generation_id or str(generation.get("patchNotes", "")) != notes:
            return None
        return generation_id

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


def _server_data_root_container(ctx: Context) -> Path:
    return Path("/data") / _SERVER_DATA_ROOT


def _exec_output(result) -> str:
    output = result.output
    if isinstance(output, bytes):
        return output.decode("utf-8", errors="replace")
    return str(output or "")


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
    """Select a durable retained ancestor and publish a confirmed real RCON revert."""
    projection = _read_server_json(ctx, "current-projection.json", "server current projection")
    pointer = _read_server_json(ctx, "current.json", "server current pointer")
    current = projection.get("generation")
    history = projection.get("patchNotesHistory")
    if not isinstance(current, dict) or not isinstance(history, list) or len(history) < 2:
        raise AssertionError("server rollback scenario needs durable current generation metadata and history")
    current_id = str(current.get("generationId", ""))
    if str(pointer.get("generationId", "")) != current_id:
        raise AssertionError("server current pointer and projection disagree before rollback")
    history_by_id = {
        str(entry.get("generationId", "")): entry
        for entry in history
        if isinstance(entry, dict) and entry.get("generationId")
    }
    ancestor_ids = []
    next_id = str(current.get("parentGenerationId", ""))
    while next_id:
        if next_id in ancestor_ids:
            raise AssertionError(f"server generation history contains a parent cycle at {next_id}")
        ancestor_ids.append(next_id)
        entry = history_by_id.get(next_id)
        if entry is None:
            raise AssertionError(f"server generation history omits retained ancestor {next_id}")
        next_id = str(entry.get("parentGenerationId", ""))
    target_id = next((
        generation_id
        for generation_id in reversed(ancestor_ids)
        if (ctx.server_dir / "automodpack" / "server" / "commits" / f"{generation_id}.json").is_file()
        and isinstance(_read_server_json(ctx, f"commits/{generation_id}.json", "retained generation commit").get("stateDigest"), str)
    ), None)
    if target_id is None:
        raise AssertionError(f"server rollback has no valid retained ancestor: {ancestor_ids}")
    target_commit = _read_server_json(ctx, f"commits/{target_id}.json", "retained generation commit")
    state_digest = str(target_commit.get("stateDigest", ""))
    target_ledger_digest = str(target_commit.get("ledgerDigest", ""))
    if not re.fullmatch(r"[0-9a-f]{40}", state_digest) or not re.fullmatch(r"[0-9a-f]{40}", target_ledger_digest) or not (ctx.server_dir / "automodpack" / "server" / "catalogues" / f"{state_digest}.json").is_file():
        raise AssertionError(f"server rollback target metadata is not retained: {target_id}")
    notes = str(step.get("notes", "Release gate rollback verification.")).strip()
    if not notes:
        raise AssertionError("server rollback notes must not be empty")
    command = ["rcon-cli", "automodpack", "generate", "revert", target_id, "confirm", "notes", notes]
    result = _container(ctx.srv_name).exec_run(command)
    output = _exec_output(result)
    if result.exit_code != 0 or "unknown command" in output.lower() or "incorrect argument" in output.lower():
        raise RuntimeError(f"server generation rollback command was rejected: {output}")

    def completed_state():
        try:
            after_projection = _read_server_json(ctx, "current-projection.json", "server rollback projection")
            after_pointer = _read_server_json(ctx, "current.json", "server rollback pointer")
        except AssertionError:
            return None
        after_current = after_projection.get("generation")
        after_history = after_projection.get("patchNotesHistory")
        if not isinstance(after_current, dict) or not isinstance(after_history, list):
            return None
        new_id = str(after_current.get("generationId", ""))
        if not new_id or new_id == current_id or str(after_pointer.get("generationId", "")) != new_id:
            return None
        if str(after_current.get("parentGenerationId", "")) != current_id:
            return None
        if str(after_current.get("rollbackTargetGenerationId", "")) != target_id or str(after_current.get("patchNotes", "")) != notes:
            return None
        after_ledger_digest = str(after_current.get("ledgerDigest", ""))
        if str(after_current.get("stateDigest", "")) != state_digest or not re.fullmatch(r"[0-9a-f]{40}", after_ledger_digest):
            return None
        history_ids = [str(entry.get("generationId", "")) for entry in after_history if isinstance(entry, dict)]
        expected_history_ids = [str(entry.get("generationId", "")) for entry in history] + [new_id]
        if history_ids != expected_history_ids:
            return None
        current_history = {str(entry.get("generationId", "")): entry for entry in after_history if isinstance(entry, dict)}
        if target_id not in current_history or current_history[target_id].get("patchNotes") != history_by_id[target_id].get("patchNotes"):
            return None
        if current_history.get(new_id, {}).get("patchNotes") != notes:
            return None
        commit = _read_server_json(ctx, f"commits/{new_id}.json", "server rollback commit")
        if str(commit.get("parentGenerationId", "")) != current_id or str(commit.get("rollbackTargetGenerationId", "")) != target_id or str(commit.get("stateDigest", "")) != state_digest or str(commit.get("ledgerDigest", "")) != after_ledger_digest or str(commit.get("patchNotes", "")) != notes:
            return None
        return after_pointer, after_projection

    after_pointer, after_projection = await_condition(
        completed_state,
        parse_duration(step.get("timeout"), default=180),
        step.get("poll"),
        "server rollback did not publish a durable pointer, projection, and patch-note history",
    )
    after_current = after_projection["generation"]
    ctx.vars["server_rollback"] = {
        "targetGenerationId": target_id,
        "currentGenerationId": str(after_pointer["generationId"]),
        "rollbackTargetGenerationId": str(after_current["rollbackTargetGenerationId"]),
        "stateDigest": str(after_current["stateDigest"]),
        "ledgerDigest": str(after_current["ledgerDigest"]),
        "patchNotes": str(after_current["patchNotes"]),
        "historyEntries": len(after_projection["patchNotesHistory"]),
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
    """Compact the live server lineage and verify its durable receipt and projection."""
    server_root = ctx.server_dir / "automodpack" / "server"
    projection_path = server_root / "current-projection.json"
    checkpoint_path = server_root / "checkpoint.json"
    before_checkpoint_bytes = checkpoint_path.read_bytes() if checkpoint_path.is_file() else None
    before_projection_bytes = projection_path.read_bytes()
    before_projection = json.loads(projection_path.read_text(encoding="utf-8"))
    before_history = list(before_projection.get("patchNotesHistory") or [])
    if len(before_history) < 2:
        raise AssertionError("server compaction scenario needs at least two patch-note history entries")
    expected_ids = [str(entry.get("generationId", "")) for entry in before_history]
    expected_notes = [str(entry.get("patchNotes", "")) for entry in before_history]
    expected_superseded_ids = set(expected_ids[:-1])
    before_counts = {
        name: len(list((server_root / name).glob("*.json")))
        for name in ("catalogues", "commits", "deltas")
    }
    before_deletion_paths = {
        server_root / "commits" / f"{generation_id}.json"
        for generation_id in expected_superseded_ids
    } | {
        server_root / "deltas" / f"{generation_id}.json"
        for generation_id in expected_superseded_ids
    }
    boundary_id = expected_ids[-1]
    result = _container(ctx.srv_name).exec_run(["rcon-cli", "automodpack", "generate", "storage", "compact", "before", boundary_id, "confirm"])
    output = result.output.decode("utf-8", errors="replace") if result.output else ""
    if result.exit_code != 0:
        raise RuntimeError(f"server history compaction command failed ({result.exit_code}): {output}")
    if "incorrect argument for command" in output.lower() or "unknown command" in output.lower():
        raise RuntimeError(f"server history compaction command was rejected: {output}")

    def completed_state():
        if not checkpoint_path.is_file() or not projection_path.is_file():
            return None
        try:
            checkpoint_bytes = checkpoint_path.read_bytes()
            checkpoint = json.loads(checkpoint_bytes)
            after_projection = json.loads(projection_path.read_text(encoding="utf-8"))
        except (OSError, TypeError, ValueError, json.JSONDecodeError):
            return None
        if not _completed_compaction_receipt(checkpoint, after_projection, expected_ids, expected_notes):
            return None
        if any(path.exists() for path in before_deletion_paths):
            return None
        after_counts = {
            name: len(list((server_root / name).glob("*.json")))
            for name in ("catalogues", "commits", "deltas")
        }
        if not any(after_counts[name] < before_counts[name] for name in before_counts):
            return None
        if checkpoint_bytes == before_checkpoint_bytes and projection_path.read_bytes() == before_projection_bytes:
            return None
        return checkpoint, after_projection, after_counts

    checkpoint, after_projection, after_counts = await_condition(
        completed_state,
        parse_duration(step.get("timeout"), default=120),
        step.get("poll"),
        "server generation compaction did not publish a new validated receipt, projection, and deletion state",
    )
    checkpoint_history = list(checkpoint.get("patchNotesHistory") or [])
    ctx.vars["server_compaction"] = {"before": before_counts, "after": after_counts, "historyEntries": len(checkpoint_history)}


def _completed_compaction_receipt(checkpoint, projection, expected_ids, expected_notes):
    checkpoint_history = list(checkpoint.get("patchNotesHistory") or [])
    projection_history = list(projection.get("patchNotesHistory") or [])
    if [str(entry.get("generationId", "")) for entry in checkpoint_history] != expected_ids:
        return False
    if [str(entry.get("generationId", "")) for entry in projection_history] != expected_ids:
        return False
    if [str(entry.get("patchNotes", "")) for entry in checkpoint_history] != expected_notes:
        return False
    if [str(entry.get("patchNotes", "")) for entry in projection_history] != expected_notes:
        return False
    boundary_id = expected_ids[-1]
    if str(checkpoint.get("boundaryGenerationId", "")) != boundary_id:
        return False
    if str(checkpoint.get("record", {}).get("generation", {}).get("generationId", "")) != boundary_id:
        return False
    if checkpoint.get("supersededGenerationIds") or checkpoint.get("supersededCatalogueStateDigests"):
        return False
    for history in (checkpoint.get("historyIndex", {}), projection.get("generationHistory", {})):
        entries = list(history.get("entries") or [])
        if str(history.get("currentGenerationId", "")) != boundary_id or str(history.get("compactionBoundaryGenerationId", "")) != boundary_id:
            return False
        if [str(entry.get("generationId", "")) for entry in entries] != expected_ids:
            return False
        if any(bool(entry.get("rollbackAvailable")) for entry in entries[:-1]) or not bool(entries[-1].get("rollbackAvailable")):
            return False
    return True


@verb("launch_client")
def _v_launch_client(ctx: Context, step):
    _await_client_preparation(ctx)
    _remove_container(ctx.cli_name)
    ctx.bridge = None
    _launch_client(ctx)


@verb("reset_client_generation")
def _v_reset_client_generation(ctx: Context, _step):
    """Reset generation-owned durable state for a fresh-client test.

    Trust and connection data remain in place deliberately, as do all ordinary game files such as mods/.
    """
    client = ctx.game_dir / "automodpack" / "client"
    for relative in CLIENT_GENERATION_STATE_PATHS:
        path = client / relative
        if path.is_dir():
            shutil.rmtree(path)
        else:
            path.unlink(missing_ok=True)
    ctx.vars["client_generation_reset"] = True


@verb("reset_isolated_client_objects")
def _v_reset_isolated_client_objects(ctx: Context, _step):
    """Remove the test client's CAS (the test client always owns an isolated local root)."""
    data_root = _ensure_client_data_root(ctx.game_dir)
    objects = data_root / "objects"
    if objects.is_dir():
        shutil.rmtree(objects)
    else:
        objects.unlink(missing_ok=True)


@verb("assert_client_objects_absent")
def _v_assert_client_objects_absent(ctx: Context, _step):
    objects = _ensure_client_data_root(ctx.game_dir) / "objects"
    if objects.exists():
        raise AssertionError(f"fresh-client CAS was not removed: {objects}")


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
    modpack_id = str(projection.get("modpackId", "")).strip()
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


def _published_objects(ctx: Context, only_groups: list[str] | None = None) -> dict[str, int]:
    projection_path = ctx.server_dir / "automodpack" / "server" / "current-projection.json"
    try:
        projection = json.loads(projection_path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"published projection is not readable: {error}") from error
    groups = projection.get("groups", {}) or {}
    if only_groups:
        unknown = [group for group in only_groups if group not in groups]
        if unknown:
            raise AssertionError(f"published projection has no such groups: {unknown}")
        groups = {name: groups[name] for name in only_groups}
    expected = {}
    for group in groups.values():
        for file in (group.get("files", {}) or {}).values():
            sha1 = str(file.get("sha1", "")).strip().lower()
            if not sha1:
                continue
            size = int(file["size"])
            previous_size = expected.setdefault(sha1, size)
            if previous_size != size:
                raise AssertionError(f"published projection gives object {sha1} conflicting sizes: {previous_size} and {size}")
    return expected


@verb("assert_preload_rejected")
def _v_assert_preload_rejected(ctx: Context, _step):
    """Assert that bootstrap import ran without letting an anonymous secret preload the catalogue."""
    expected = _published_objects(ctx)
    if not expected:
        raise AssertionError("published projection contains no object hashes")
    objects = _ensure_client_data_root(ctx.game_dir) / "objects"
    present = [sha1 for sha1 in expected if cas_object(objects, sha1).is_file()]
    if present:
        raise AssertionError(f"anonymous bootstrap preload acquired catalogue objects: count={len(present)}")
    connection_path = ctx.game_dir / "automodpack" / "client" / "data" / "packs" / str(ctx.vars["bootstrap_modpack_id"]) / "connection.json"
    try:
        connection = json.loads(connection_path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"bootstrap connection state is not readable: {error}") from error
    secrets = connection.get("secrets", {})
    if isinstance(secrets, dict) and secrets.get(str(ctx.vars["bootstrap_origin"])):
        raise AssertionError("anonymous bootstrap preload unexpectedly persisted a client secret")


@verb("assert_preload_acquired")
def _v_assert_preload_acquired(ctx: Context, _step):
    """Assert that launch apply put every object of the client's selected target into CAS."""
    expected = _published_objects(ctx, only_groups=_step.get("groups"))
    if not expected:
        raise AssertionError("published projection contains no object hashes")
    objects = _ensure_client_data_root(ctx.game_dir) / "objects"
    missing = []
    invalid = []
    for sha1, size in expected.items():
        object_path = cas_object(objects, sha1)
        try:
            if not object_path.is_file() or object_path.stat().st_size != size:
                missing.append(sha1)
            elif hashlib.sha1(object_path.read_bytes()).hexdigest() != sha1:
                invalid.append(sha1)
        except OSError:
            missing.append(sha1)
    if missing or invalid:
        raise AssertionError(f"preload CAS is incomplete: missing={missing}, invalid={invalid}")
    log = ctx.container_logs("client")
    client_log = ctx.game_dir / "logs" / "latest.log"
    if client_log.is_file():
        log += "\n" + client_log.read_text(encoding="utf-8", errors="replace")
    acquired = f"Launch apply acquired {len(expected)} complete modpack objects"
    restored = f"All {len(expected)} selected modpack objects are already acquired locally"
    if acquired not in log and restored not in log:
        raise AssertionError(f"client log did not prove launch object acquisition: {acquired!r} or {restored!r}")
    ctx.vars["preloaded_object_count"] = len(expected)


@verb("wait_bridge")
def _v_wait_bridge(ctx: Context, step):
    if ctx.bridge is None:
        ctx.bridge = BridgeClient(ctx.game_dir, ctx.token)
    timeout = _client_start_timeout(ctx, step)
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            _assert_running(ctx.cli_name)
        except RuntimeError as e:
            logs = _container_logs(ctx.cli_name)
            if _transient_dependency_download_failure(logs) and time.monotonic() < deadline:
                # Successful downloads survive in the target cache. Continue cold-cache discovery within the existing startup
                # budget; unrelated crashes still fail immediately and an unavailable dependency host still reaches the deadline.
                logger.warning("Client dependency download failed transiently for %s; continuing within the startup deadline", ctx.target.id)
                _remove_container(ctx.cli_name)
                _launch_client(ctx)
                continue
            # Keep whole trailing lines (not a mid-line byte slice) so the crash
            # tail — including the exception header — stays readable.
            tail = "\n".join(logs.splitlines()[-80:])
            raise TimeoutError(f"Client exited before bridge: {e}\n--- logs ---\n{tail}")
        try:
            sf = _bridge_state(ctx)
            if sf.exists():
                data = json.loads(sf.read_text(encoding="utf-8"))
                if data.get("status") == "ready":
                    ctx.bridge.request("ping", timeout=5)
                    _detach_client_runtime_mods(ctx)
                    _record_prepared_client_profile(ctx)
                    return
        except Exception:
            pass
        _jitter_sleep(1)
    raise TimeoutError(f"Bridge for {ctx.target.id} did not become available within {timeout}s")


def _transient_dependency_download_failure(logs: str) -> bool:
    if "[LibraryDownloader]" not in logs:
        return False
    return any(marker in logs for marker in ("HTTP connect timed out", "Connection reset", "Temporary failure in name resolution"))


def _client_start_timeout(ctx: Context, step):
    if "timeout" in step:
        return parse_duration(step["timeout"])
    configured = {**ctx.settings.get("timeouts", {}), **ctx.scenario.get("timeouts", {})}
    return float(configured.get("clientStartSeconds", 600))


@verb("connect")
def _v_connect(ctx: Context, step):
    host = ctx.resolve(step.get("host") or "${server.host}")
    timeout = parse_duration(step.get("timeout"), default=90)
    deadline = time.monotonic() + timeout
    _TITLE = ("TitleScreen", "class_442")
    last_screen = "<not observed>"
    while time.monotonic() < deadline:
        _assert_running(ctx.cli_name)
        ctx.bridge.connect(host)
        poll_dl = time.monotonic() + min(deadline - time.monotonic(), 45)
        while time.monotonic() < poll_dl:
            screen = str(ctx.bridge.gui().get("screenClass") or "")
            last_screen = screen or "<none>"
            if any(n in screen for n in _TITLE):
                break
            if _is_connection_failure_screen(screen):
                break
            if not _is_connecting_screen(screen):
                return
            _jitter_sleep(0.5)
        ctx.bridge.request("disconnect")
        _jitter_sleep(1)
    log_tail = "\n".join(ctx.container_logs("client").splitlines()[-20:])
    raise RuntimeError(f"Could not connect to {host} after multiple attempts; last_screen={last_screen!r}\n--- client log tail ---\n{log_tail}")


@verb("disconnect")
def _v_disconnect(ctx: Context, step):
    try:
        if ctx.bridge is not None:
            ctx.bridge.request("disconnect")
    except (RuntimeError, TimeoutError):
        pass


@verb("quit")
def _v_quit(ctx: Context, step):
    try:
        state = _inspect_container(ctx.cli_name).get("State", {})
        if state.get("Running", False) and ctx.bridge is not None:
            ctx.bridge.request("quit")
    except (RuntimeError, TimeoutError):
        pass


@verb("wait_exit", "wait_client_exit")
def _v_wait_client_exit(ctx: Context, step):
    """Wait for the client container to exit, optionally asserting *how* it exited.

    ``expect:`` makes "loaded then crashed/idled" a first-class outcome:
      any   (default) — exited for any reason; don't judge the code
      clean — exit code 0
      crash — non-zero exit code
    The ``timeout`` wrapper around the client exits 124, which counts as a crash.

    ``or_alive: true`` tolerates the client *still running* after the grace period
    instead of failing — for "loaded then idles" on a real-GPU host where the
    client never crashes. Pair it with a positive ``wait_for`` marker beforehand
    so the step still proves the client got far enough. Only meaningful with
    ``expect: any`` (a still-alive client has no exit code to judge).
    """
    timeout = parse_duration(step.get("timeout"), default=90)
    or_alive = bool(step.get("or_alive") or step.get("tolerate_alive"))
    try:
        _wait_exited(ctx.cli_name, timeout=timeout)
    except TimeoutError:
        if or_alive:
            return  # still loaded and running after the grace period — acceptable
        raise
    expect = str(step.get("expect", "any")).lower()
    if expect == "any":
        return
    code = _exit_code(ctx.cli_name)
    if expect == "clean" and code not in (0, None):
        raise AssertionError(f"expected clean client exit, got exit code {code}")
    if expect == "crash" and code in (0, None):
        raise AssertionError(f"expected client crash, got exit code {code}")


@verb("wait_join")
def _v_wait_join(ctx: Context, step):
    timeout = parse_duration(
        step.get("timeout"),
        default=float(ctx.scenario.get("timeouts", {}).get("rejoinSeconds", 180)),
    )
    await_condition(
        lambda: True if ctx.gui(timeout=10).get("screenClass") is None else None,
        timeout,
        step.get("poll"),
        f"{ctx.target.id}: player did not reach in-game",
    )


def _sha1(path: Path) -> str:
    with path.open("rb") as f:
        return hashlib.file_digest(f, "sha1").hexdigest()


def _staged_generation_id(
    modpack_id: str,
    created_at: str,
    state_digest: str,
    ledger_digest: str,
    patch_notes: str = "",
    parent_generation_id: str = "",
) -> str:
    notes_digest = hashlib.sha1(patch_notes.encode("utf-8")).hexdigest()
    return (
        CanonicalEncoder()
        .string("automodpack-generation-v1")
        .integer(1)
        .string(modpack_id)
        .string(parent_generation_id)
        .string(created_at)
        .string(state_digest)
        .string(ledger_digest)
        .string(notes_digest)
        .string("")
        .digest()
    )


def _staged_ledger_digest(modpack_id: str, entries: list[dict]) -> str:
    encoder = CanonicalEncoder().string("automodpack-ownership-ledger-v1").string(modpack_id).integer(len(entries))
    for entry in entries:
        hashes = sorted(entry["historicalHashes"], key=lambda content: (content["sha1"], int(content["size"])))
        groups = sorted(entry["historicalGroupIds"])
        encoder.string(entry["logicalPath"]).integer(len(hashes))
        for content in hashes:
            encoder.string(content["sha1"]).long(int(content["size"]))
        encoder.integer(len(groups))
        for group in groups:
            encoder.string(group)
        encoder.string(entry["currentStatus"])
    return encoder.digest()


def _staged_state_digest(ctx: Context, modpack_id: str, files: list[dict], modpack_name: str | None = None) -> str:
    encoder = (
        CanonicalEncoder()
        .string("automodpack-state-v1")
        .string(modpack_id)
        .string(modpack_name if modpack_name is not None else ctx.modpack_name)
        .string("")
        .string(ctx.target.loader)
        .string(_load_ver(ctx.target))
        .string(ctx.target.minecraft)
        .integer(1)
        .string("main")
        .string(modpack_name if modpack_name is not None else ctx.modpack_name)
        .string("")
        .string("")
        .boolean(True)
        .boolean(True)
    )
    write_strings(encoder, [])
    write_strings(encoder, [])
    encoder.integer(0).integer(len(files))
    for entry in files:
        encoder.string(entry["logicalPath"]).long(int(entry["size"])).string(entry["type"])
        encoder.boolean(entry["editable"]).string(entry["sha1"]).string("")
    return encoder.digest()


def _write_staged_generation(
    ctx: Context,
    root: Path,
    modpack_id: str,
    data_root: Path,
    *,
    client_root: Path | None = None,
    modpack_name: str | None = None,
    patch_notes: str = "",
    parent_generation_id: str = "",
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
    ledger_entries = [
        {
            "logicalPath": entry["logicalPath"],
            "historicalHashes": [{"sha1": entry["sha1"], "size": int(entry["size"])}],
            "historicalGroupIds": ["main"],
            "firstPublishedGenerationId": "0" * 40,
            "lastPublishedGenerationId": "0" * 40,
            "currentStatus": "PRESENT",
        }
        for entry in files
    ]
    modpack_name = modpack_name if modpack_name is not None else ctx.modpack_name
    state_digest = _staged_state_digest(ctx, modpack_id, files, modpack_name)
    provisional_ledger_digest = _staged_ledger_digest(modpack_id, ledger_entries)
    created_at = datetime.now(timezone.utc).isoformat(timespec="microseconds").replace("+00:00", "Z")
    client_root = client_root or root.parent
    generation_id = _staged_generation_id(modpack_id, created_at, state_digest, provisional_ledger_digest, patch_notes, parent_generation_id)
    for entry in ledger_entries:
        entry["firstPublishedGenerationId"] = generation_id
        entry["lastPublishedGenerationId"] = generation_id

    manifest = {
        "modpackName": modpack_name,
        "modpackId": modpack_id,
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
        "ownershipLedger": {
            "modpackId": modpack_id,
            "entries": ledger_entries,
            "digest": provisional_ledger_digest,
        },
        "generation": {
            "schemaVersion": 1,
            "generationId": generation_id,
            "parentGenerationId": parent_generation_id,
            "createdAt": created_at,
            "stateDigest": state_digest,
            "ledgerDigest": provisional_ledger_digest,
            "patchNotes": patch_notes,
            "patchNotesDigest": hashlib.sha1(patch_notes.encode("utf-8")).hexdigest(),
            "rollbackTargetGenerationId": "",
        },
    }
    if parent_generation_id:
        parent_manifest_path = client_root / "records" / parent_generation_id / "manifest.json"
        parent_manifest = json.loads(parent_manifest_path.read_text(encoding="utf-8"))
        parent_generation = parent_manifest["generation"]
        parent_history = parent_manifest.get("patchNotesHistory") or [_staged_patch_note_entry(parent_generation)]
        manifest["patchNotesHistory"] = [*parent_history, _staged_patch_note_entry(manifest["generation"])]
    generation_path = client_root / "records" / generation_id / "manifest.json"
    generation_path.parent.mkdir(parents=True, exist_ok=True)
    generation_path.write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    objects = data_root / "objects"
    objects.mkdir(parents=True, exist_ok=True)
    for entry in files:
        object_path = cas_object(objects, entry["sha1"])
        if not object_path.is_file():
            object_path.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(root / entry["logicalPath"], object_path)
    return {"generationId": generation_id, "stateDigest": state_digest, "ledgerDigest": provisional_ledger_digest}


def _staged_patch_note_entry(generation: dict) -> dict:
    return {
        "schemaVersion": generation["schemaVersion"],
        "generationId": generation["generationId"],
        "parentGenerationId": generation["parentGenerationId"],
        "createdAt": generation["createdAt"],
        "patchNotes": generation["patchNotes"],
        "patchNotesDigest": generation["patchNotesDigest"],
    }


def _latest_staged_generation_id(client_root: Path, modpack_id: str) -> str:
    records_root = client_root / "records"
    candidates = []
    if not records_root.is_dir():
        return ""
    for path in records_root.glob("*/manifest.json"):
        try:
            manifest = json.loads(path.read_text(encoding="utf-8"))
            generation = manifest.get("generation")
            generation_id = str(generation.get("generationId", "")) if isinstance(generation, dict) else ""
            if manifest.get("modpackId") == modpack_id and generation_id:
                candidates.append((str(generation.get("createdAt", "")), generation_id))
        except (OSError, ValueError, TypeError):
            continue
    return max(candidates)[1] if candidates else ""


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
    parent_generation_id = _latest_staged_generation_id(client_root, modpack_id) if record_only else ""
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
        parent_generation_id=parent_generation_id,
        editable_paths=editable_paths,
    )
    if record_only:
        shutil.rmtree(root)
        ctx.vars["staged_pack_id"] = modpack_id
        return
    state = {
        "schemaVersion": 1,
        "modpackId": modpack_id,
        "generationId": generation["generationId"],
        "platform": "linux",
        "stateDigest": generation["stateDigest"],
        "ledgerDigest": generation["ledgerDigest"],
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


@verb("seed_cas")
def _v_seed_cas(ctx: Context, _step):
    """Put the scenario's server files in the client CAS without installing them."""
    objects = _ensure_client_data_root(ctx.game_dir) / "objects"
    objects.mkdir(parents=True, exist_ok=True)
    payloads = [json.dumps({"marker": ctx.modpack_name}).encode("utf-8") + b"\n"]
    payloads.extend(content.encode("utf-8") for _, content in ctx.scenario_files)
    for payload in payloads:
        object_path = cas_object(objects, hashlib.sha1(payload).hexdigest())
        object_path.parent.mkdir(parents=True, exist_ok=True)
        object_path.write_bytes(payload)


# ── case orchestration ────────────────────────────────────────────────────


def _resolve_artifact(target: Target, artifact_dir: Path) -> Path:
    pattern = target.artifact_pattern.format(
        id=target.id,
        minecraft=target.minecraft,
        loader=target.loader,
    )
    matches = sorted(artifact_dir.glob(pattern))
    if not matches:
        raise FileNotFoundError(
            f"No artifact for {target.id} matching {pattern!r} in {artifact_dir}"
        )
    if len(matches) != 1:
        names = ", ".join(path.name for path in matches)
        raise RuntimeError(
            f"Ambiguous artifacts for {target.id} matching {pattern!r}: {names}"
        )
    artifact = matches[0].resolve()
    with zipfile.ZipFile(artifact) as jar:
        names = set(jar.namelist())
    if "META-INF/jarjar/automodpack-mod.jar" in names:
        with zipfile.ZipFile(artifact) as jar, jar.open("META-INF/jarjar/automodpack-mod.jar") as nested, zipfile.ZipFile(nested) as mod_jar:
            names.update(mod_jar.namelist())
    if not any(name.startswith("pl/skidam/automodpack/client/autotest/") and name.endswith(".class") for name in names):
        raise RuntimeError(
            f"Artifact {artifact} has no AutoTestBridge classes; rebuild with -Pautomodpack.autotest before running the autotester"
        )
    return artifact


def run_case(
    target: Target,
    scenario: dict,
    *,
    out_dir: Path,
    artifact_dir: Path,
    client_image: str,
    settings: dict,
    resource_scope: str,
) -> dict:
    started = time.monotonic()
    scenario_id = scenario.get("id", "?")
    connection_mode = str((scenario.get("connectionPath") or {}).get("mode", "")).upper() or None
    net_mode = transport(scenario, settings)
    mode = scenario_mode(scenario)
    case_dir = out_dir / f"{target.id}-{int(time.time())}-{secrets.token_hex(3)}"
    server_dir = case_dir / "server"
    game_dir = case_dir / "client" / "game"
    # On host networking the two containers share the host namespace, so there is
    # no user-defined network to create and the client reaches the server locally.
    resource_prefix = f"amp-{resource_scope}"
    net_name = "host" if net_mode == "host" else f"{resource_prefix}-n-{secrets.token_hex(4)}"[:63]
    srv_name = f"{resource_prefix}-s-{secrets.token_hex(4)}"[:63]
    cli_name = f"{resource_prefix}-c-{secrets.token_hex(4)}"[:63]
    prep_name = cli_name.replace("-c-", "-p-", 1)
    server_host = "127.0.0.1" if net_mode == "host" else srv_name
    token = secrets.token_hex(16)
    case_seconds = float(settings.get("timeouts", {}).get("caseSeconds", 1800))
    if case_seconds <= 0:
        raise ValueError("timeouts.caseSeconds must be positive")
    deadline_expired = threading.Event()
    case_finished = threading.Event()

    def expire_case() -> None:
        if case_finished.wait(case_seconds):
            return
        deadline_expired.set()
        logger.error("[%s] case exceeded its %.0fs deadline; removing its Docker resources", target.id, case_seconds)
        for name in (cli_name, prep_name, srv_name):
            try:
                _remove_container(name)
            except Exception:
                logger.warning("Failed to remove expired case container %s", name)
        if net_name != "host":
            try:
                _remove_network(net_name)
            except Exception:
                logger.warning("Failed to remove expired case network %s", net_name)

    threading.Thread(target=expire_case, name=f"deadline-{target.id}", daemon=True).start()

    for d in (server_dir, game_dir):
        d.mkdir(parents=True, exist_ok=True)

    step_results: list[dict] = []
    try:
        artifact = _resolve_artifact(target, artifact_dir)

        sf = parse_server_files(scenario)

        ctx = Context(
            target=target,
            scenario=scenario,
            settings=settings,
            game_dir=game_dir,
            server_dir=server_dir,
            out_dir=out_dir,
            client_image=client_image,
            srv_name=srv_name,
            cli_name=cli_name,
            net_name=net_name,
            token=token,
            artifact=artifact,
            modpack_name=sf.modpack_name,
            marker_rel=sf.marker,
            scenario_files=sf.files,
            expected_mods=sf.expected_mods,
            server_host=server_host,
            resource_scope=resource_scope,
            vars={
                **dict(scenario.get("vars", {}) or {}),
                "server_endpoint_port": int((scenario.get("connectionPath") or {}).get("endpointPort", 25565)),
            },
        )
        ctx.logs_provider = lambda which, tail=None: _container_logs(
            srv_name if which == "server" else cli_name, tail=tail
        )

        def _running():
            try:
                _assert_running(cli_name)
            except RuntimeError as e:
                raise ClientExited(str(e))
            client_log = game_dir / "logs" / "latest.log"
            try:
                recent_log = client_log.read_text(encoding="utf-8", errors="replace")
            except OSError:
                return
            if 'Uncaught exception in thread "automodpack-net"' in recent_log:
                tail = "\n".join(recent_log.splitlines()[-80:])
                raise ClientExited(f"AutoModpack network worker crashed\n--- logs ---\n{tail}")

        ctx.running_provider = _running

        # Client-only (pre-staged) runs never launch a server; full runs do.
        if mode != "client-only":
            _prepare_server(ctx)
        if net_name != "host":
            _ensure_network(net_name, resource_labels(resource_scope))

        run_flow(ctx, scenario, lib=load_macros(), results=step_results)

        if deadline_expired.is_set():
            raise TimeoutError(f"Case exceeded the configured {case_seconds:.0f}s deadline")

        return {
            "target": target.id,
            "scenario": scenario_id,
            "connectionMode": connection_mode,
            "ok": True,
            "duration": time.monotonic() - started,
            "steps": step_results,
        }

    except Exception as e:
        error = f"Case exceeded the configured {case_seconds:.0f}s deadline" if deadline_expired.is_set() else str(e)
        return {
            "target": target.id,
            "scenario": scenario_id,
            "connectionMode": connection_mode,
            "ok": False,
            "duration": time.monotonic() - started,
            "error": error,
            "steps": step_results,
        }

    finally:
        case_finished.set()
        for name in [cli_name, prep_name, srv_name]:
            try:
                logs = _container_logs(name)
                if logs:
                    (case_dir / f"{name}.log").write_text(logs, encoding="utf-8", errors="replace")
            except Exception:
                pass
            try:
                _remove_container(name)
            except Exception:
                logger.warning("Failed to remove container %s", name)
        if net_name != "host":
            _remove_network(net_name)
