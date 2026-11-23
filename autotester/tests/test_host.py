"""Host process supervisor: reserved loopback ports and a killable child tree."""
from __future__ import annotations

import socket
import sys
import time

from automodpack_autotester.host import Process, load_farm, machine, open_slot, reserve
from automodpack_autotester.host.server import write_server_properties


def test_reserve_returns_distinct_loopback_ports():
    held = reserve(2)
    try:
        assert len(held.ports) == 2
        assert held.ports[0] != held.ports[1]
        for port in held.ports:
            assert 1 <= port <= 65535
    finally:
        held.release()


def test_reserve_holds_the_ports_until_released():
    held = reserve(1)
    port = held.ports[0]
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        try:
            sock.bind(("127.0.0.1", port))
        except OSError:
            pass
        else:
            raise AssertionError(f"port {port} was still free while the reservation held it")
    held.release()
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", port))


def test_reserve_rejects_zero():
    try:
        reserve(0)
    except ValueError as error:
        assert "at least one port" in str(error)
    else:
        raise AssertionError("reserve(0) must fail")


def test_process_spawn_logs_wait_and_kill(tmp_path):
    child = Process()
    log = tmp_path / "child.log"
    child.spawn([sys.executable, "-c", "import sys; sys.stdout.write('hello-host\\n'); sys.stdout.flush(); import time; time.sleep(30)"], log=log)
    deadline = time.monotonic() + 5
    while time.monotonic() < deadline and "hello-host" not in child.logs():
        time.sleep(0.05)
    assert child.alive()
    assert "hello-host" in child.logs()
    child.kill()
    assert not child.alive()


def test_process_wait_returns_the_exit_code(tmp_path):
    child = Process()
    child.spawn([sys.executable, "-c", "raise SystemExit(17)"], log=tmp_path / "exit.log")
    assert child.wait(timeout=5) == 17
    assert not child.alive()


def test_open_slot_gives_distinct_loopback_ports_and_dirs(tmp_path):
    first = open_slot(tmp_path / "s1", "s1")
    second = open_slot(tmp_path / "s2", "s2")
    assert first.game_port != second.game_port
    assert first.endpoint_port != second.endpoint_port
    assert first.rcon_port != second.rcon_port
    assert first.game_dir.is_dir()
    first.spawn("child", [sys.executable, "-c", "import time; time.sleep(30)"])
    assert first.processes["child"].alive()
    first.kill_all()
    assert not first.processes
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
        sock.bind(("127.0.0.1", first.game_port))


def test_farm_box_is_one_slot_and_unknown_provide_fails():
    farm = load_farm({"farm": {"mode": "farm", "slots": 4, "provide": "native"}})
    box = load_farm({"farm": {"mode": "box", "slots": 9, "provide": "docker"}})
    assert farm.slots == 4 and farm.mode == "farm" and farm.provide == "native"
    assert box.slots == 1 and box.mode == "box" and box.provide == "docker"
    try:
        load_farm({"farm": {"provide": "qemu"}})
    except ValueError as error:
        assert "native or docker" in str(error)
    else:
        raise AssertionError("qemu must fail")


def test_machine_selects_native_or_docker():
    assert machine({}).name == "native"
    assert machine({"farm": {"provide": "docker"}}).name == "docker"


def test_write_server_properties_binds_loopback(tmp_path):
    write_server_properties(tmp_path, game_port=23456, rcon_port=23457)
    text = (tmp_path / "server.properties").read_text(encoding="utf-8")
    assert "server-port=23456" in text
    assert "server-ip=127.0.0.1" in text
    assert "rcon.port=23457" in text
    (tmp_path / "fabric-server-mc.26.2-loader.0.20.1-launcher.1.1.2.jar").write_bytes(b"jar")
    from automodpack_autotester.host.seed import launch_jar as seed_launch_jar
    from automodpack_autotester.host.seed import materialize
    from automodpack_autotester.runtime.base import ServerSpec

    assert seed_launch_jar(tmp_path).name.startswith("fabric-server")


def test_materialize_keeps_a_staged_automodpack_jar(tmp_path):
    from automodpack_autotester.host.seed import materialize
    from automodpack_autotester.runtime.base import ServerSpec

    cache = tmp_path / "cache" / "1.21.1-fabric"
    dest = tmp_path / "server"
    cache.mkdir(parents=True)
    (cache / "fabric-server-launch.jar").write_bytes(b"seed-jar")
    (cache / ".amp-seed-complete").write_text("1\n", encoding="utf-8")
    (cache / ".amp-seed-complete").write_text("1\n", encoding="utf-8")
    (cache / "mods").mkdir()
    (cache / "mods" / "ferritecore.jar").write_bytes(b"ferrite")
    dest.mkdir()
    (dest / "mods").mkdir()
    (dest / "mods" / "automodpack.jar").write_bytes(b"staged-pack")
    (dest / "automodpack").mkdir()
    (dest / "automodpack" / "server-config.json").write_text("{}", encoding="utf-8")
    settings = {"paths": {"serverCacheDir": str(tmp_path / "cache")}}
    spec = ServerSpec(image="itzg/minecraft-server:java21", env={}, mounts=(), cap_add=())
    materialize(dest, spec, target_id="1.21.1-fabric", settings=settings)
    assert (dest / "fabric-server-launch.jar").is_file()
    assert (dest / "mods" / "automodpack.jar").read_bytes() == b"staged-pack"
    assert (dest / "mods" / "ferritecore.jar").read_bytes() == b"ferrite"
    assert (dest / "automodpack" / "server-config.json").read_text(encoding="utf-8") == "{}"


def test_materialize_never_merges_the_seeds_world_or_logs(tmp_path):
    from automodpack_autotester.host.seed import materialize
    from automodpack_autotester.runtime.base import ServerSpec

    cache = tmp_path / "cache" / "1.21.1-fabric"
    dest = tmp_path / "server"
    cache.mkdir(parents=True)
    (cache / "fabric-server-launch.jar").write_bytes(b"seed-jar")
    (cache / ".amp-seed-complete").write_text("1\n", encoding="utf-8")
    (cache / ".amp-seed-complete").write_text("1\n", encoding="utf-8")
    (cache / "world" / "DIM-1").mkdir(parents=True)
    (cache / "world" / "DIM-1" / "region.mca").write_bytes(b"seed-nether")
    (cache / "logs").mkdir()
    (cache / "logs" / "latest.log").write_text("seed boot\n", encoding="utf-8")
    settings = {"paths": {"serverCacheDir": str(tmp_path / "cache")}}
    spec = ServerSpec(image="itzg/minecraft-server:java21", env={})

    materialize(dest, spec, target_id="1.21.1-fabric", settings=settings)
    assert not (dest / "world").exists(), "a case generates its own world, even on the first materialize"
    assert not (dest / "logs").exists(), "the seed's boot log is not the case's log"

    (dest / "world").mkdir()
    (dest / "world" / "level.dat").write_bytes(b"this case's world")
    materialize(dest, spec, target_id="1.21.1-fabric", settings=settings)
    assert (dest / "world" / "level.dat").read_bytes() == b"this case's world", "a restart keeps the world it ran in"
    assert not (dest / "world" / "DIM-1").exists(), "a restart never mixes the seed's world into the case's"


def test_itzg_seed_refuses_to_mark_a_seed_that_never_booted(tmp_path, monkeypatch):
    from automodpack_autotester.host import seed as seed_module
    from automodpack_autotester.runtime.base import ServerSpec

    class FakeProc:
        stdout = None

        def poll(self):
            return 1

        def wait(self, timeout=None):
            return 1

    monkeypatch.setattr(seed_module.shutil, "which", lambda _name: "/usr/bin/docker")
    monkeypatch.setattr(seed_module.subprocess, "Popen", lambda *_args, **_kwargs: FakeProc())
    monkeypatch.setattr(seed_module.subprocess, "run", lambda *_args, **_kwargs: None)
    root = tmp_path / "cache"
    root.mkdir()
    (root / "fabric-server-launch.jar").write_bytes(b"half-installed")

    try:
        seed_module._itzg_seed(root, ServerSpec(image="img", env={}), timeout=5)
    except RuntimeError as error:
        assert "never reached 'Done ('" in str(error)
    else:
        raise AssertionError("a seed container that exited before Done ( must not be marked complete")
    assert not (root / seed_module._SEED_MARKER).exists(), "a broken seed must reseed next run, not be trusted"


def test_cleanup_scope_kills_the_children_a_crashed_tester_leaves(tmp_path, monkeypatch):
    import types

    from automodpack_autotester import supervisor

    scope = "pid-registry-test"
    registry = supervisor.child_pid_file(scope)
    registry.parent.mkdir(parents=True, exist_ok=True)
    registry.unlink(missing_ok=True)
    monkeypatch.setenv(supervisor.CHILD_PIDS_ENV, str(registry))
    child = Process()
    child.spawn([sys.executable, "-c", "import time; time.sleep(30)"], log=tmp_path / "child.log")
    assert child.alive()
    docker = types.SimpleNamespace(
        containers=types.SimpleNamespace(list=lambda **_kwargs: []),
        networks=types.SimpleNamespace(list=lambda **_kwargs: []),
    )

    supervisor.cleanup_scope(scope, docker)

    deadline = time.monotonic() + 10
    while time.monotonic() < deadline and child.alive():
        time.sleep(0.05)
    assert not child.alive(), "the run watcher must kill a case child the tester left behind"
    assert not registry.exists()


def test_rcon_joins_a_response_split_across_packets():
    import threading

    from automodpack_autotester.host import rcon

    listener = socket.socket(socket.AF_INET, socket.SOCK_STREAM)
    listener.bind(("127.0.0.1", 0))
    listener.listen(1)
    port = listener.getsockname()[1]
    first, second = "a" * 4096, "b" * 1609

    def serve():
        with listener, listener.accept()[0] as conn:
            rcon._read_packet(conn)
            conn.sendall(rcon._packet(1, 2, "auth-ok"))
            rcon._read_packet(conn)
            # Vanilla's shape: full chunks back to back, no terminator.
            conn.sendall(rcon._packet(2, 0, first) + rcon._packet(2, 0, second))
            time.sleep(1)

    threading.Thread(target=serve, daemon=True).start()
    result = rcon.run("127.0.0.1", port, "pw", "help")

    assert result.exit_code == 0
    assert result.output == first + second


def test_launch_argv_prefers_a_plain_jar_and_uses_the_loader_arg_file_without_one(tmp_path):
    from automodpack_autotester.host.seed import launch_argv
    from automodpack_autotester.runtime.base import ServerSpec

    plain = tmp_path / "fabric"
    plain.mkdir()
    (plain / "fabric-server-launch.jar").write_bytes(b"jar")
    assert launch_argv(plain, ServerSpec(image="img", env={})) == ["-jar", "fabric-server-launch.jar", "nogui"]

    # A Forge seed installs run.sh/run.bat wrapping `java @<args file>`; the harness runs that file directly.
    loader = tmp_path / "forge"
    args = loader / "libraries/net/minecraftforge/forge/1.20.1-47.4.26/unix_args.txt"
    args.parent.mkdir(parents=True)
    args.write_text("-cp libraries/a.jar\ncpw.mods.bootstraplauncher.BootstrapLauncher\n", encoding="utf-8")
    (loader / "run.sh").write_text("java @user_jvm_args.txt @libraries/net/minecraftforge/forge/1.20.1-47.4.26/unix_args.txt \"$@\"\n", encoding="utf-8")
    spec = ServerSpec(image="img", env={"FORGE_VERSION": "47.4.26"})
    assert launch_argv(loader, spec) == ["@libraries/net/minecraftforge/forge/1.20.1-47.4.26/unix_args.txt", "nogui"]


def test_materialize_reseeds_a_cache_whose_seed_never_finished(tmp_path, monkeypatch):
    from automodpack_autotester.host import seed as seed_module
    from automodpack_autotester.runtime.base import ServerSpec

    cache = tmp_path / "cache" / "1.21.1-fabric"
    dest = tmp_path / "server"
    cache.mkdir(parents=True)
    # A killed install leaves the launch jar behind but never reaches "Done (".
    (cache / "fabric-server-launch.jar").write_bytes(b"half-seeded")
    seeds = []

    def fake_seed(root, spec, timeout):
        seeds.append(root)
        (root / seed_module._SEED_MARKER).write_text("1\n", encoding="utf-8")

    monkeypatch.setattr(seed_module, "_itzg_seed", fake_seed)
    settings = {"paths": {"serverCacheDir": str(tmp_path / "cache")}}

    seed_module.materialize(dest, ServerSpec(image="img", env={}), target_id="1.21.1-fabric", settings=settings)
    assert seeds == [cache]

    seed_module.materialize(dest, ServerSpec(image="img", env={}), target_id="1.21.1-fabric", settings=settings)
    assert seeds == [cache], "a completed seed is reused instead of reseeded"
