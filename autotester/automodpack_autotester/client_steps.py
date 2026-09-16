"""Client lifecycle verbs: containers, HMC profiles, bridge wait, connect/quit, client CAS."""
from __future__ import annotations

import hashlib
import json
import logging
import os
import re
import shutil
import time
from pathlib import Path

import docker as docker_py

from .bridge import BridgeClient
from .config import CLIENT_GENERATION_STATE_PATHS, Target
from .mods import resolve_mod
from .supervisor import resource_labels
from .docker_harness import _assert_running, _container_logs, _docker, _exit_code, _inspect_container, _jitter_sleep, _remove_container, _run_container, _uid, _gid, _wait_exited
from .engine import Context
from .engine.registry import verb
from .engine.util import await_condition, parse_duration


logger = logging.getLogger(__name__)

_CLIENT_DATA_ROOT = Path("automodpack/client/data")


def cas_object(objects_dir: Path, sha1: str) -> Path:
    digest = str(sha1).lower()
    return objects_dir / digest[:2] / digest[2:]


def _bridge_state(ctx: Context) -> Path:
    return ctx.game_dir / "automodpack" / "autotest" / "bridge-state.json"


def _ensure_client_data_root(game_dir: Path) -> Path:
    """Create the one local data root used by every client lifecycle step."""
    data_root = game_dir / _CLIENT_DATA_ROOT
    data_root.mkdir(parents=True, exist_ok=True)
    return data_root


def _is_connecting_screen(screen: str) -> bool:
    # Fabric 1.20.1's generated intermediary mapping names ConnectScreen class_412.
    return "FirstConnectScreen" not in screen and any(name in screen for name in ("ConnectScreen", "class_397", "class_412"))


def _is_connection_failure_screen(screen: str) -> bool:
    # Fabric 1.20.1's generated intermediary mapping names DisconnectedScreen class_419.
    return "DisconnectedScreen" in screen or "class_419" in screen


def _load_ver(t):
    if t.loader == "fabric":
        return getattr(t, "fabric_loader", None) or ""
    if t.loader == "forge":
        return getattr(t, "forge_version", None) or ""
    if t.loader == "neoforge":
        return getattr(t, "neoforge_version", None) or ""
    return ""


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


def _client_preparation_name(ctx: Context) -> str:
    return ctx.cli_name.replace("-c-", "-p-", 1)


@verb("prepare_client")
def _v_prepare_client(ctx: Context, _step):
    """Materialise the HMC loader profile while an independent server starts."""
    hmc_cache_root, shared_versions = _client_cache_paths(ctx)
    if _client_profile_is_prepared(ctx, hmc_cache_root, shared_versions):
        return
    _prepare_client_files(ctx)
    ctx.vars["client_preparation"] = _launch_preparation_container(ctx)
    ctx.vars["client_preparation_attempts"] = 1


def _launch_preparation_container(ctx: Context) -> str:
    name = _client_preparation_name(ctx)
    _remove_container(name)
    _start_client_container(ctx, name, prepare_only=True)
    return name


_PREPARE_ATTEMPTS = 3


def _await_client_preparation(ctx: Context) -> None:
    name = ctx.vars.pop("client_preparation", None)
    if not name:
        return
    attempts = int(ctx.vars.pop("client_preparation_attempts", 1) or 1)
    deadline = time.monotonic() + float(ctx.settings.get("timeouts", {}).get("clientStartSeconds", 600))
    try:
        while True:
            remaining = deadline - time.monotonic()
            if remaining <= 0:
                raise TimeoutError(f"Client profile preparation did not complete within the startup budget (attempt {attempts})")
            _wait_exited(name, remaining)
            state = _inspect_container(name).get("State", {})
            if state.get("ExitCode") == 0:
                _record_prepared_client_profile(ctx)
                hmc_cache_root, shared_versions = _client_cache_paths(ctx)
                if _client_profile_is_prepared(ctx, hmc_cache_root, shared_versions):
                    return
                raise RuntimeError("Client profile preparation exited successfully without a valid profile receipt")
            tail = "\n".join(_container_logs(name).splitlines()[-80:])
            if attempts >= _PREPARE_ATTEMPTS:
                raise RuntimeError(f"Client profile preparation failed after {attempts} attempts (code={state.get('ExitCode', -1)})\n--- logs ---\n{tail}")
            logger.warning("Client profile preparation failed for %s (code=%s, attempt %d); reinstalling\n--- logs ---\n%s", ctx.target.id, state.get("ExitCode", -1), attempts, tail)
            attempts += 1
            _remove_container(name)
            name = _launch_preparation_container(ctx)
    finally:
        _remove_container(name)


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


def _published_objects(ctx: Context, only_groups: list[str] | None = None) -> dict[str, int]:
    projection_path = ctx.server_dir / "automodpack" / "server" / "current-projection.json"
    try:
        projection = json.loads(projection_path.read_text(encoding="utf-8"))
    except (OSError, TypeError, ValueError, json.JSONDecodeError) as error:
        raise AssertionError(f"published projection is not readable: {error}") from error
    groups = (projection.get("policy", {}) or {}).get("groups", {}) or {}
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
            missing = _missing_cache_jar_paths(logs)
            if missing is not None and not ctx.vars.get("client_cache_recovered") and time.monotonic() < deadline:
                # The installer exited 0 but left installer-produced jars (old Forge's *-srg/*-extra outputs)
                # unwritten; every launch from this per-target cache crashes the same way. Reinstall once and
                # prove the exact jars the crash named now exist, so later shards inherit a healed cache.
                ctx.vars["client_cache_recovered"] = True
                logger.warning("Client launch crashed on missing cache jars for %s; reinstalling the loader profile once: %s", ctx.target.id, missing or "unparsed")
                _recover_client_cache(ctx, missing or [])
                _remove_container(ctx.cli_name)
                _launch_client(ctx)
                continue
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


def _missing_cache_jar_paths(logs: str) -> list[str] | None:
    """Extract the cache jars a crashed launch named, or None when this is not a missing-jars crash.

    The Forge/NeoForge mod scanner fails with ``Invalid paths argument, contained no existing paths: [...]``
    when the installer left its produced jars unwritten. The crash names the exact jars, so recovery can prove
    the reinstall produced them instead of trusting another exit code.
    """
    marker = "contained no existing paths:"
    index = logs.find(marker)
    if index < 0:
        return None
    bracketed = re.search(r"\[(.*?)\]", logs[index + len(marker):index + len(marker) + 4096], re.DOTALL)
    if bracketed is None:
        return []
    return [path.strip() for path in bracketed.group(1).split(",") if path.strip()]


def _recover_client_cache(ctx: Context, missing: list[str]) -> None:
    """Reinstall the loader profile after a launch proved the cache is poisoned."""
    hmc_cache_root, _shared_versions = _client_cache_paths(ctx)
    _client_profile_receipt(hmc_cache_root).unlink(missing_ok=True)
    ctx.vars["client_preparation"] = _launch_preparation_container(ctx)
    ctx.vars["client_preparation_attempts"] = 1
    _await_client_preparation(ctx)
    prefix = "/work/hmc-cache/"
    still_missing = [path for path in missing if path.startswith(prefix) and not (hmc_cache_root / path[len(prefix):]).is_file()]
    if still_missing:
        raise RuntimeError(f"Client profile reinstall did not produce required jars for {ctx.target.id}: {still_missing}")


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
