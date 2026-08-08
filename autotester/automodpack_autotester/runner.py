from __future__ import annotations

import hashlib
import json
import logging
import os
import random
import secrets
import shutil
import time
from concurrent.futures import ThreadPoolExecutor
from datetime import datetime, timezone
from pathlib import Path

import docker as docker_py

from .bridge import BridgeClient
from .config import REPO_ROOT, Target, load_macros, parse_server_files
from .mod_fixtures import write_valid_mod_fixture
from .mods import resolve_mod
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


def _ensure_network(name):
    _remove_network(name)
    _docker.networks.create(name, check_duplicate=True)


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


def _run_container(name, image, network, env, mounts, command=None, user=None, entrypoint=None):
    volumes = {}
    for host, container_path, readonly in mounts:
        volumes[str(host)] = {"bind": container_path, "mode": "ro" if readonly else "rw"}
    kwargs = dict(
        image=image, detach=True, name=name,
        environment=dict(env), volumes=volumes, command=command, user=user,
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
_CLIENT_DATA_ROOT_IN_CONTAINER = "/work/game/automodpack/client/data"


def _ensure_client_data_root(game_dir: Path) -> Path:
    """Create the one local data root used by every client lifecycle step."""
    automodpack_dir = game_dir / "automodpack"
    marker = automodpack_dir / "data-root.json"
    data_root = game_dir / _CLIENT_DATA_ROOT
    if not marker.exists():
        data_root.mkdir(parents=True, exist_ok=True)
        marker.write_text(json.dumps({"root": _CLIENT_DATA_ROOT_IN_CONTAINER, "shared": False}, indent=2) + "\n")
    else:
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
    cfg.update((ctx.scenario.get("topology", {}).get("server", {}).get("automodpack", {}) or {}).get("config", {}))
    cfg["modpackName"] = ctx.modpack_name
    cfg["acceptedLoaders"] = [ctx.target.loader]
    (srv_dir / "automodpack").mkdir(parents=True, exist_ok=True)
    (srv_dir / "automodpack" / "server-config.json").write_text(json.dumps(cfg, indent=2))
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
    (main_root / ctx.marker_rel).write_text(json.dumps({"marker": ctx.modpack_name}) + "\n")
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
        f.write_text(str(item.get("content", "")))
    patch_notes = generation.get("patchNotes", "")
    patch_path = srv_dir / "automodpack" / "server" / "patch-notes.md"
    patch_path.parent.mkdir(parents=True, exist_ok=True)
    patch_path.write_text(str(patch_notes))


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
    if scenario.get("serverFiles", {}).get("generations"):
        env.setdefault("ENABLE_RCON", "true")
        env.setdefault("RCON_PASSWORD", "amp-autotest")
    # Run the itzg server as the same user the runner uses, so it can write to the
    # bind-mounted mods/automodpack dirs (which the runner created). Without this the
    # server runs as itzg's default UID 1000 and fails on hosts with a different UID
    # (e.g. the GitHub runner's 1001): AccessDenied writing /data/mods/*.download.
    env.setdefault("UID", str(_uid()))
    env.setdefault("GID", str(_gid()))
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
    _run_container(name=ctx.srv_name, image=img, network=ctx.net_name, env=env, mounts=mounts)


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


def _launch_client(ctx: Context):
    game_dir = ctx.game_dir
    (game_dir / "mods").mkdir(parents=True, exist_ok=True)
    shutil.copy2(ctx.artifact, game_dir / "mods" / "automodpack.jar")
    _seed_client_options(game_dir)
    _ensure_client_data_root(game_dir)
    _bridge_state(ctx).unlink(missing_ok=True)

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

    client_run_seconds = int(float(
        ctx.scenario.get("timeouts", {}).get(
            "clientRunSeconds",
            ctx.settings.get("timeouts", {}).get("clientRunSeconds", 600),
        )
    ))
    _run_container(
        name=ctx.cli_name,
        image=ctx.client_image,
        network=ctx.net_name,
        env={
            "AM_AUTOTEST_BRIDGE_TOKEN": ctx.token,
            "AM_AUTOTEST_GAME_DIR": "/work/game",
            "AM_AUTOTEST_HMC_CACHE_DIR": "/work/hmc-cache",
            "AM_AUTOTEST_CLIENT_TIMEOUT_SECONDS": str(client_run_seconds),
            "AM_AUTOTEST_RENDER_CLIENT": str(bool(ctx.scenario.get("renderClient", False))).lower(),
        },
        mounts=[
            (game_dir, "/work/game", False),
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
    )
    _jitter_sleep(1)
    _assert_running(ctx.cli_name)


# ── lifecycle verbs (need Docker; pure UI/IO verbs live in engine/) ───────


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
    command = ["rcon-cli", "automodpack", "generate"]
    if notes:
        command.extend(["notes", notes.replace("\n", " ")])
    result = _container(ctx.srv_name).exec_run(command)
    output = result.output.decode("utf-8", errors="replace") if result.output else ""
    if result.exit_code != 0:
        raise RuntimeError(f"server generation command failed ({result.exit_code}): {output}")
    await_condition(
        lambda: True if "PUBLISHED" in _container_logs(ctx.srv_name, tail=200) else None,
        parse_duration(step.get("timeout"), default=120),
        step.get("poll"),
        f"server generation {index} was not published",
    )
    ctx.vars["published_server_generation"] = index


@verb("launch_client")
def _v_launch_client(ctx: Context, step):
    _remove_container(ctx.cli_name)
    ctx.bridge = None
    _launch_client(ctx)


@verb("wait_bridge")
def _v_wait_bridge(ctx: Context, step):
    if ctx.bridge is None:
        ctx.bridge = BridgeClient(ctx.game_dir, ctx.token)
    timeout = parse_duration(
        step.get("timeout"),
        default=float(ctx.scenario.get("timeouts", {}).get("clientStartSeconds", 180)),
    )
    deadline = time.monotonic() + timeout
    while time.monotonic() < deadline:
        try:
            _assert_running(ctx.cli_name)
        except RuntimeError as e:
            logs = _container_logs(ctx.cli_name)
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
                    return
        except Exception:
            pass
        _jitter_sleep(1)
    raise TimeoutError(f"Bridge for {ctx.target.id} did not become available within {timeout}s")


@verb("connect")
def _v_connect(ctx: Context, step):
    host = ctx.resolve(step.get("host") or "${server.host}")
    timeout = parse_duration(step.get("timeout"), default=90)
    deadline = time.monotonic() + timeout
    _TITLE = ("TitleScreen", "class_442")
    _CONNECT = ("ConnectScreen", "class_397")
    while time.monotonic() < deadline:
        _assert_running(ctx.cli_name)
        ctx.bridge.connect(host)
        poll_dl = time.monotonic() + min(deadline - time.monotonic(), 45)
        while time.monotonic() < poll_dl:
            screen = str(ctx.bridge.gui().get("screenClass") or "")
            if any(n in screen for n in _TITLE):
                break
            if not any(n in screen for n in _CONNECT):
                return
            _jitter_sleep(0.5)
        ctx.bridge.request("disconnect")
        _jitter_sleep(1)
    raise RuntimeError(f"Could not connect to {host} after multiple attempts")


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


def _staged_generation_id(modpack_id: str, created_at: str, state_digest: str, ledger_digest: str, patch_notes: str = "") -> str:
    notes_digest = hashlib.sha1(patch_notes.encode("utf-8")).hexdigest()
    return (
        CanonicalEncoder()
        .string("automodpack-generation-v1")
        .integer(1)
        .string(modpack_id)
        .string("")
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
        encoder.boolean(entry["editable"]).boolean(False).string(entry["sha1"]).string("")
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
            "editable": rel == ctx.marker_rel,
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
    generation_id = _staged_generation_id(modpack_id, created_at, state_digest, provisional_ledger_digest, patch_notes)
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
                "tag": "",
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
                        "overwriteEditable": False,
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
            "parentGenerationId": "",
            "createdAt": created_at,
            "stateDigest": state_digest,
            "ledgerDigest": provisional_ledger_digest,
            "patchNotes": patch_notes,
            "patchNotesDigest": hashlib.sha1(patch_notes.encode("utf-8")).hexdigest(),
            "rollbackTargetGenerationId": "",
        },
    }
    client_root = client_root or root.parent
    generation_path = client_root / "records" / generation_id / "manifest.json"
    generation_path.parent.mkdir(parents=True, exist_ok=True)
    generation_path.write_text(json.dumps(manifest, indent=2) + "\n")
    objects = data_root / "objects"
    objects.mkdir(parents=True, exist_ok=True)
    for entry in files:
        object_path = objects / entry["sha1"]
        if not object_path.is_file():
            shutil.copy2(root / entry["logicalPath"], object_path)
    return {"generationId": generation_id, "stateDigest": state_digest, "ledgerDigest": provisional_ledger_digest}


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
    (root / ctx.marker_rel).parent.mkdir(parents=True, exist_ok=True)
    (root / ctx.marker_rel).write_text(json.dumps({"marker": modpack_name}) + "\n")
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
            write_valid_mod_fixture(f, fixture)
        else:
            f.parent.mkdir(parents=True, exist_ok=True)
            f.write_text(content)

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
    (client_root / "active-state.json").write_text(json.dumps(state, indent=2) + "\n")

    selection_store = client_root / "selections.json"
    selection_store.write_text(json.dumps({
        "DO_NOT_CHANGE_IT": 1,
        "selections": {modpack_id: {"requestedGroups": [], "excludedGroups": []}},
    }, indent=2) + "\n")

    # A client config that selects the staged pack and disables the launch update,
    # so Preload loads it locally (no server contact, no file reconciliation). The
    # modpackConnections entry needs a complete origin/endpoint pair or Preload self-updates
    # instead of loading; the endpoint is never dialed when update-on-launch is off.
    host = ctx.server_host or "127.0.0.1"
    addr = host if ":" in host else f"{host}:25565"
    cfg = {
        "DO_NOT_CHANGE_IT": 3,
        "selectedModpackId": modpack_id,
        "updateSelectedModpackOnLaunch": False,
        "modpackConnections": {
            modpack_id: {
                "origin": addr,
                "endpoint": addr,
                "connectionMode": "DIRECT",
            }
        },
    }
    cfg.update(ctx.resolve(step.get("config", {}) or {}))
    automodpack.mkdir(parents=True, exist_ok=True)
    (automodpack / "client-config.json").write_text(json.dumps(cfg, indent=2))


@verb("seed_cas")
def _v_seed_cas(ctx: Context, _step):
    """Put the scenario's server files in the client CAS without installing them."""
    objects = _ensure_client_data_root(ctx.game_dir) / "objects"
    objects.mkdir(parents=True, exist_ok=True)
    payloads = [json.dumps({"marker": ctx.modpack_name}).encode("utf-8") + b"\n"]
    payloads.extend(content.encode("utf-8") for _, content in ctx.scenario_files)
    for payload in payloads:
        (objects / hashlib.sha1(payload).hexdigest()).write_bytes(payload)


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
    return matches[0].resolve()


def run_case(
    target: Target,
    scenario: dict,
    *,
    out_dir: Path,
    artifact_dir: Path,
    client_image: str,
    settings: dict,
) -> dict:
    started = time.monotonic()
    scenario_id = scenario.get("id", "?")
    net_mode = transport(scenario, settings)
    mode = scenario_mode(scenario)
    case_dir = out_dir / f"{target.id}-{int(time.time())}-{secrets.token_hex(3)}"
    server_dir = case_dir / "server"
    game_dir = case_dir / "client" / "game"
    # On host networking the two containers share the host namespace, so there is
    # no user-defined network to create and the client reaches the server locally.
    net_name = "host" if net_mode == "host" else f"amp-{secrets.token_hex(4)}"[:63]
    srv_name = f"amp-s-{secrets.token_hex(4)}"[:63]
    cli_name = f"amp-c-{secrets.token_hex(4)}"[:63]
    server_host = "127.0.0.1" if net_mode == "host" else srv_name
    token = secrets.token_hex(16)

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
            vars=dict(scenario.get("vars", {}) or {}),
        )
        ctx.logs_provider = lambda which, tail=None: _container_logs(
            srv_name if which == "server" else cli_name, tail=tail
        )

        def _running():
            try:
                _assert_running(cli_name)
            except RuntimeError as e:
                raise ClientExited(str(e))

        ctx.running_provider = _running

        # Client-only (pre-staged) runs never launch a server; full runs do.
        if mode != "client-only":
            _prepare_server(ctx)
        if net_name != "host":
            _ensure_network(net_name)

        run_flow(ctx, scenario, lib=load_macros(), results=step_results)

        return {
            "target": target.id,
            "scenario": scenario_id,
            "ok": True,
            "duration": time.monotonic() - started,
            "steps": step_results,
        }

    except Exception as e:
        return {
            "target": target.id,
            "scenario": scenario_id,
            "ok": False,
            "duration": time.monotonic() - started,
            "error": str(e),
            "steps": step_results,
        }

    finally:
        for name in [cli_name, srv_name]:
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
