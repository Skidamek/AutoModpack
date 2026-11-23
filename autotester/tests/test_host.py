"""Host process supervisor: ephemeral loopback ports and a killable child tree."""
from __future__ import annotations

import socket
import sys
import time

from automodpack_autotester.host import Process, allocate, load_farm, machine, open_slot
from automodpack_autotester.host.server import write_server_properties


def test_allocate_returns_distinct_loopback_ports():
    ports = allocate(2)
    assert len(ports) == 2
    assert ports[0] != ports[1]
    for port in ports:
        assert 1 <= port <= 65535
        with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as sock:
            sock.bind(("127.0.0.1", port))


def test_allocate_rejects_zero():
    try:
        allocate(0)
    except ValueError as error:
        assert "at least one port" in str(error)
    else:
        raise AssertionError("allocate(0) must fail")


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
    (cache / "mods").mkdir()
    (cache / "mods" / "ferritecore.jar").write_bytes(b"ferrite")
    dest.mkdir()
    (dest / "mods").mkdir()
    (dest / "mods" / "automodpack.jar").write_bytes(b"staged-pack")
    (dest / "automodpack").mkdir()
    (dest / "automodpack" / "server-config.json").write_text("{}", encoding="utf-8")
    settings = {"paths": {"serverCacheDir": str(tmp_path / "cache")}}
    spec = ServerSpec(image="itzg/minecraft-server:java21", env={}, mounts=(), cap_add=())
    jar = materialize(dest, spec, target_id="1.21.1-fabric", settings=settings)
    assert jar.name == "fabric-server-launch.jar"
    assert (dest / "mods" / "automodpack.jar").read_bytes() == b"staged-pack"
    assert (dest / "mods" / "ferritecore.jar").read_bytes() == b"ferrite"
    assert (dest / "automodpack" / "server-config.json").read_text(encoding="utf-8") == "{}"
