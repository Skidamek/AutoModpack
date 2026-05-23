from __future__ import annotations

import json
import logging
import os
import random
import re
import secrets
import shutil
import time
from collections.abc import Callable
from fnmatch import fnmatch
from pathlib import Path

import docker as docker_py

from .bridge import BridgeClient
from .config import Target


logger = logging.getLogger(__name__)

_docker = docker_py.from_env()


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
        image=image, detach=True, name=name, network=network,
        environment=dict(env), volumes=volumes, command=command, user=user,
    )
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


PHASES: dict[str, Callable] = {}


def _reg(name: str) -> Callable:
    def wrapper(fn: Callable) -> Callable:
        PHASES[name] = fn
        return fn
    return wrapper


def _uid():
    return int(os.environ.get("AUTOTEST_DOCKER_UID", os.getuid()))


def _gid():
    return int(os.environ.get("AUTOTEST_DOCKER_GID", os.getgid()))


def _load_ver(t):
    return t.fabric_loader or t.forge_version or t.neoforge_version or ""


def _bridge_state(ctx):
    return ctx["game_dir"] / "automodpack" / "autotest" / "bridge-state.json"


def _await(pred, timeout, msg):
    dl = time.monotonic() + timeout
    while time.monotonic() < dl:
        r = pred()
        if r is not None:
            return r
        _jitter_sleep(0.5)
    raise TimeoutError(msg)


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
    case_dir = out_dir / f"{target.id}-{int(time.time())}-{secrets.token_hex(3)}"
    server_dir = case_dir / "server"
    game_dir = case_dir / "client" / "game"
    net_name = f"amp-{secrets.token_hex(4)}"[:63]
    srv_name = f"amp-s-{secrets.token_hex(4)}"[:63]
    cli_name = f"amp-c-{secrets.token_hex(4)}"[:63]
    token = secrets.token_hex(16)
    ctx = dict(locals())

    for d in (server_dir, game_dir):
        d.mkdir(parents=True, exist_ok=True)

    try:
        pattern = f"automodpack-mc{target.minecraft}-{target.loader}-*.jar"
        matches = sorted(artifact_dir.glob(pattern))
        if not matches:
            raise FileNotFoundError(f"No artifact for {target.id} in {artifact_dir}")
        ctx["artifact"] = matches[-1].resolve()

        sf = scenario.get("serverFiles", {})
        ctx["modpack_name"] = str(sf.get("modpackName", "amp-autotest"))
        ctx["marker_rel"] = Path(
            str(sf.get("marker", "config/amp-autotest-marker.json"))
        )
        ctx["scenario_files"] = [
            (Path(str(f["path"])), str(f.get("content", "")))
            for f in sf.get("files", [])
        ]
        ctx["expected_mods"] = [str(m) for m in sf.get("expectedMods", [])]

        _prepare_server(ctx, target, settings)
        _ensure_network(net_name)

        flow = scenario.get("flow", [])
        if not flow:
            raise ValueError("scenario has no 'flow' list")
        for phase_name in flow:
            fn = PHASES.get(phase_name)
            if not fn:
                raise ValueError(f"unknown phase: {phase_name!r}")
            logger.info("[%s] Phase: %s", target.id, phase_name)
            fn(ctx)

        return {
            "target": target.id,
            "scenario": scenario.get("id", "?"),
            "ok": True,
            "duration": time.monotonic() - started,
        }

    except Exception as e:
        return {
            "target": target.id,
            "scenario": scenario.get("id", "?"),
            "ok": False,
            "duration": time.monotonic() - started,
            "error": str(e),
        }

    finally:
        for name in [cli_name, srv_name]:
            try:
                logs = _container_logs(name)
                if logs:
                    (case_dir / f"{name}.log").write_text(
                        logs, encoding="utf-8", errors="replace"
                    )
            except Exception:
                pass
            try:
                _remove_container(name)
            except Exception:
                logger.warning("Failed to remove container %s", name)
        _remove_network(net_name)


# === infrastructure (not flow phases) ===


def _prepare_server(ctx, target, settings):
    srv_dir = ctx["server_dir"]
    (srv_dir / "mods").mkdir(parents=True, exist_ok=True)
    shutil.copy2(ctx["artifact"], srv_dir / "mods" / "automodpack.jar")
    cfg = dict(settings.get("automodpack", {}).get("config", {}))
    cfg["modpackName"] = ctx["modpack_name"]
    cfg["acceptedLoaders"] = [target.loader]
    (srv_dir / "automodpack").mkdir(parents=True, exist_ok=True)
    (srv_dir / "automodpack" / "automodpack-server.json").write_text(
        json.dumps(cfg, indent=2)
    )
    host_root = srv_dir / "automodpack" / "host-modpack" / "main"
    host_root.mkdir(parents=True, exist_ok=True)
    (host_root / ctx["marker_rel"]).parent.mkdir(parents=True, exist_ok=True)
    (host_root / ctx["marker_rel"]).write_text(
        json.dumps({"marker": ctx["modpack_name"]}) + "\n"
    )
    for rel, content in ctx["scenario_files"]:
        f = host_root / rel
        f.parent.mkdir(parents=True, exist_ok=True)
        f.write_text(content)


def _launch_server(ctx, target, scenario, settings):
    topo = scenario.get("topology", {}).get("server", {})
    srv_type = topo.get("type") or settings.get("serverTypes", {}).get(target.loader)
    if not srv_type:
        raise ValueError(f"No server type for {target.loader}")

    env = dict(settings.get("server", {}).get("env", {}))
    env.update({
        "TYPE": str(srv_type),
        "VERSION": target.minecraft,
        "MEMORY": str(topo.get("memory", "2G")),
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
    mr = topo.get("modrinth", {})
    if mr:
        projs = list(
            dict.fromkeys(
                str(p).strip()
                for p in (
                    list(mr.get("projects", []))
                    + list(
                        (mr.get("projectsByLoader", {}) or {}).get(target.loader, [])
                        or []
                    )
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
            (ctx["server_dir"] / sub).mkdir(parents=True, exist_ok=True)
            mounts.append((ctx["server_dir"] / sub, f"/data/{sub}", False))
    else:
        mounts = [(ctx["server_dir"], "/data", False)]
    img = str(
        topo.get("image")
        or settings.get("images", {}).get("server", "itzg/minecraft-server")
    )
    if ":" not in img:
        img = f"{img}:{str(settings.get('images', {}).get('serverTagTemplate', 'java{java}')).format(java=target.java)}"
    _run_container(
        name=ctx["srv_name"], image=img, network=ctx["net_name"], env=env, mounts=mounts
    )


def _launch_client(ctx, target, client_image):
    game_dir = ctx["game_dir"]
    (game_dir / "mods").mkdir(parents=True, exist_ok=True)
    shutil.copy2(ctx["artifact"], game_dir / "mods" / "automodpack.jar")

    # Per-target HMC cache (isolated to prevent concurrent NeoForge installer corruption)
    hmc_cache_root = (ctx["out_dir"].parent / ".hmc-cache" / target.id.replace(".", "_")).resolve()
    hmc_cache_root.mkdir(parents=True, exist_ok=True)

    _run_container(
        name=ctx["cli_name"],
        image=client_image,
        network=ctx["net_name"],
        env={
            "AM_AUTOTEST_BRIDGE_TOKEN": ctx["token"],
            "AM_AUTOTEST_GAME_DIR": "/work/game",
            "AM_AUTOTEST_HMC_CACHE_DIR": "/work/hmc-cache",
        },
        mounts=[
            (game_dir, "/work/game", False),
            (hmc_cache_root, "/work/hmc-cache", False),
        ],
        command=[
            "/opt/automodpack/run-headlessmc-client",
            target.loader,
            target.minecraft,
            "localhost",
            "25565",
            str(target.java),
            _load_ver(target),
        ],
        user=f"{_uid()}:{_gid()}",
    )
    _jitter_sleep(1)
    _assert_running(ctx["cli_name"])


def _wait_server(ctx, target, scenario, settings):
    to = scenario.get("timeouts", {}) or settings.get("timeouts", {})
    _wait_for_log(
        ctx["srv_name"], "Done (", timeout=float(to.get("serverStartSeconds", 180))
    )


# === flow phases ===


@_reg("wait_bridge")
def _phase_wait_bridge(ctx):
    if "bridge" in ctx:
        return
    ctx["bridge"] = BridgeClient(ctx["game_dir"], ctx["token"])
    to = float(ctx["scenario"].get("timeouts", {}).get("clientStartSeconds", 180))
    dl = time.monotonic() + to
    while time.monotonic() < dl:
        try:
            _assert_running(ctx["cli_name"])
        except RuntimeError as e:
            logs = _container_logs(ctx["cli_name"])
            raise TimeoutError(
                f"Client exited before bridge: {e}\n--- logs ---\n{logs[-2000:]}"
            )
        if _bridge_state(ctx).exists():
            try:
                ctx["bridge"].request("ping", timeout=5)
                return
            except Exception:
                pass
        _jitter_sleep(1)
    raise TimeoutError(f"Bridge for {ctx['target'].id} did not become available within {to}s")


@_reg("ensure_ready")
def _phase_ensure_ready(ctx):
    bridge = ctx["bridge"]
    dl = time.monotonic() + 30
    while time.monotonic() < dl:
        try:
            r = bridge.request("get_widgets")
        except (TimeoutError, RuntimeError):
            _jitter_sleep(1)
            continue
        if "TitleScreen" in str(r.get("screenClass", "")) or "class_442" in str(
            r.get("screenClass", "")
        ):
            return
        if any("Continue" in str(w.get("text", "")) for w in r.get("widgets", [])):
            try:
                bridge.request("click", selector={"text": "Continue"})
            except (TimeoutError, RuntimeError):
                pass
            _jitter_sleep(1)
            continue
        _jitter_sleep(0.5)


@_reg("read_fingerprint")
def _phase_read_fingerprint(ctx):
    to = float(ctx["scenario"].get("timeouts", {}).get("serverStartSeconds", 180))
    dl = time.monotonic() + to
    while time.monotonic() < dl:
        logs = _container_logs(ctx["srv_name"], tail=200)
        for line in logs.splitlines():
            m = re.search(
                r"(?:certificate\s+)?fingerprint[:\s]+([0-9A-Fa-f:]+)", line, re.IGNORECASE
            )
            if m:
                ctx["fingerprint"] = m.group(1)
                return
        _jitter_sleep(1)
    raise RuntimeError("No TLS fingerprint found in server logs")


@_reg("connect")
def _phase_connect(ctx):
    bridge = ctx["bridge"]
    host = ctx["srv_name"]
    deadline = time.monotonic() + 90

    _TITLE = ("TitleScreen", "class_442")
    _CONNECT = ("ConnectScreen", "class_397")

    while time.monotonic() < deadline:
        _assert_running(ctx["cli_name"])
        bridge.request("connect", host=host, port=25565)
        remaining = deadline - time.monotonic()
        poll_dl = time.monotonic() + min(remaining, 45)
        while time.monotonic() < poll_dl:
            screen = str(bridge.request("get_screen").get("screenClass") or "")
            if any(n in screen for n in _TITLE):
                break
            if not any(n in screen for n in _CONNECT):
                return
            _jitter_sleep(0.5)
        bridge.request("set_screen")
        _jitter_sleep(1)
    raise RuntimeError("Could not connect after multiple attempts")


@_reg("wait_fingerprint")
def _phase_wait_fingerprint(ctx):
    fp = ctx.get("fingerprint")
    if not fp:
        raise RuntimeError("No fingerprint — run read_fingerprint phase first")
    _await(
        lambda: (
            "FingerprintVerificationScreen"
            in str(ctx["bridge"].request("get_screen").get("screenClass", ""))
            or None
        ),
        180,
        f"FingerprintVerificationScreen did not appear for {ctx['target'].id} within 180s",
    )


@_reg("accept_fingerprint")
def _phase_accept_fingerprint(ctx):
    fp = ctx.get("fingerprint")
    if not fp:
        raise RuntimeError("No fingerprint — run read_fingerprint phase first")
    ctx["bridge"].request("verify_fingerprint", fingerprint=fp)

    def _check():
        screen_class = str(ctx["bridge"].request("get_screen").get("screenClass", ""))
        if any(n in screen_class for n in ("DangerScreen", "DownloadScreen", "RestartScreen")):
            return True
        if "FingerprintVerificationScreen" not in screen_class:
            return True
        return None

    _await(_check, 20, "Fingerprint verification did not complete")


@_reg("skip_fingerprint")
def _phase_skip_fingerprint(ctx):
    bridge = ctx["bridge"]
    bridge.request("click", selector={"text": "Skip"})
    _await(
        lambda: (
            "SkipVerificationScreen"
            in str(bridge.request("get_screen").get("screenClass", ""))
            or None
        ),
        15,
        "Skip screen not shown",
    )
    bridge.request(
        "set_text", selector={"type": "EditBox", "index": 0}, text="I accept the risk"
    )
    dl = time.monotonic() + 30
    while time.monotonic() < dl:
        for w in bridge.request("get_widgets").get("widgets", []):
            if (
                w.get("type") == "Button"
                and "Skip" in str(w.get("text", ""))
                and w.get("active", False)
            ):
                bridge.request("click", selector={"widgetId": w["id"]})
                return
        _jitter_sleep(1)
    raise RuntimeError("Skip button did not activate")


@_reg("wait_danger")
def _phase_wait_danger(ctx):
    bridge = ctx["bridge"]
    _await(
        lambda: (
            "DangerScreen" in str(bridge.request("get_screen").get("screenClass", ""))
            or None
        ),
        90,
        "DangerScreen did not appear within 90s",
    )


@_reg("click_confirm")
def _phase_click_confirm(ctx):
    bridge = ctx["bridge"]
    dl = time.monotonic() + 5
    while time.monotonic() < dl:
        widgets = bridge.request("get_widgets").get("widgets", [])
        if widgets:
            break
        _jitter_sleep(0.2)
    for w in reversed(widgets):
        if w.get("type") == "Button" and w.get("active", False):
            bridge.request("click", widgetId=int(w.get("id", -1)))
            return
    raise RuntimeError("No active button on DangerScreen")


@_reg("wait_download")
def _phase_wait_download(ctx):
    marker = (
        ctx["game_dir"]
        / "automodpack"
        / "modpacks"
        / ctx["modpack_name"]
        / ctx["marker_rel"]
    )
    timeout = float(ctx["scenario"].get("timeouts", {}).get("downloadFileSeconds", 300))
    _await(
        lambda: marker if marker.exists() else None,
        timeout,
        f"Download marker file {marker} did not appear within {timeout}s",
    )
    if not marker.exists():
        raise FileNotFoundError(f"Missing marker: {marker}")


@_reg("verify_files")
def _phase_verify_files(ctx):
    mp_root = ctx["game_dir"] / "automodpack" / "modpacks" / ctx["modpack_name"]
    dl = time.monotonic() + 120
    while time.monotonic() < dl:
        if all((mp_root / rel).exists() for rel, _ in ctx["scenario_files"]):
            return
        _jitter_sleep(2)
    missing = [
        str(rel) for rel, _ in ctx["scenario_files"] if not (mp_root / rel).exists()
    ]
    raise TimeoutError(f"Files missing after sync: {', '.join(missing)}")


@_reg("verify_mods")
def _phase_verify_mods(ctx):
    if not ctx["expected_mods"]:
        return
    mp_root = ctx["game_dir"] / "automodpack" / "modpacks" / ctx["modpack_name"]
    dl = time.monotonic() + 120
    mod_dir = mp_root / "mods"
    while time.monotonic() < dl:
        mods = {p.name for p in mod_dir.glob("*.jar")} if mod_dir.exists() else set()
        if all(any(fnmatch(m, p) for m in mods) for p in ctx["expected_mods"]):
            return
        _jitter_sleep(2)
    existing = {p.name for p in mod_dir.glob("*.jar")} if mod_dir.exists() else set()
    missing = [
        p for p in ctx["expected_mods"] if not any(fnmatch(m, p) for m in existing)
    ]
    raise TimeoutError(f"Mods missing after sync: {', '.join(missing)}")


@_reg("click_restart")
def _phase_click_restart(ctx):
    bridge = ctx["bridge"]
    dl = time.monotonic() + 20
    while time.monotonic() < dl:
        try:
            screen = bridge.request("get_screen")
        except TimeoutError:
            continue
        if "RestartScreen" in str(screen.get("screenClass", "")):
            widgets = bridge.request("get_widgets").get("widgets", [])
            clicked = False
            action_labels = ("close", "restart", "quit")
            for w in reversed(widgets):
                txt = str(w.get("text", "")).lower()
                if w.get("type") == "Button" and w.get("active", False) and any(label in txt for label in action_labels):
                    bridge.request("click", widgetId=int(w.get("id", -1)))
                    clicked = True
                    break
            if not clicked:
                raise RuntimeError("No restart button found on RestartScreen")
            _wait_exited(ctx["cli_name"], timeout=90)
            return
        _jitter_sleep(0.5)


@_reg("quit")
def _phase_quit(ctx):
    try:
        state = _inspect_container(ctx["cli_name"]).get("State", {})
        if state.get("Running", False):
            ctx["bridge"].request("quit")
    except (RuntimeError, TimeoutError):
        pass


@_reg("launch_server")
def _phase_launch_server(ctx):
    _launch_server(ctx, ctx["target"], ctx["scenario"], ctx["settings"])


@_reg("wait_server")
def _phase_wait_server(ctx):
    _wait_server(ctx, ctx["target"], ctx["scenario"], ctx["settings"])


@_reg("launch_client")
def _phase_launch_client(ctx):
    _remove_container(ctx["cli_name"])
    if "bridge" in ctx:
        del ctx["bridge"]
    _launch_client(ctx, ctx["target"], ctx["client_image"])


@_reg("wait_join")
def _phase_wait_join(ctx):
    bridge = ctx["bridge"]
    to = float(ctx["scenario"].get("timeouts", {}).get("rejoinSeconds", 180))

    def _check():
        screen = bridge.request("get_screen")
        screen_class = screen.get("screenClass")
        if screen_class is None:
            return True
        name = str(screen_class)
        if "FingerprintVerificationScreen" in name:
            return None
        if "DownloadScreen" in name:
            return None
        if "RestartScreen" in name:
            return None
        return True

    _await(_check, to, f"{ctx['target'].id}: Player did not join in-game within {to}s")
