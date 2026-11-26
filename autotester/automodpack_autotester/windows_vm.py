"""KVM/QEMU live-snapshot Windows worker provider for running real Windows NTFS tests from Linux."""
from __future__ import annotations

import base64
import itertools
import json
import os
import secrets
import shutil
import socket
import subprocess
import tempfile
import threading
import time
import uuid
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from .bridge import BridgeClient
from .config import Target
from .hmc import profile_args
from .process import PortReservation
from .provider import Capabilities, LocalServer, ServerProcess

_GUEST_PORT_COUNTER = itertools.count(19550)
_GUEST_PREP_LOCK = threading.Lock()
_GUEST_PREPARED_TARGETS: set[str] = set()


@dataclass(frozen=True)
class WindowsVmConfig:
    vm_dir: Path
    golden_disk: Path
    memory: str
    cpus: int
    machine: str
    disk_interface: str
    nic: str
    tpm: bool
    snapshot_name: str
    guest_agent_port: int

    @classmethod
    def from_settings(cls, settings: dict) -> "WindowsVmConfig":
        cfg = settings.get("windowsVm") or {}
        vm_dir = Path(str(cfg.get("dir", "~/.cache/automodpack-autotest/windows-vm"))).expanduser().resolve()
        return cls(
            vm_dir=vm_dir,
            golden_disk=vm_dir / "windows-golden.qcow2",
            memory=str(cfg.get("memory", "6G")),
            cpus=int(cfg.get("cpus", 6)),
            machine=str(cfg.get("machine", "pc")),
            disk_interface=str(cfg.get("diskInterface", "ide")),
            nic=str(cfg.get("nic", "e1000e")),
            tpm=bool(cfg.get("tpm", True)),
            snapshot_name=str(cfg.get("snapshotName", "ready")),
            guest_agent_port=int(cfg.get("agentPort", 19450)),
        )


def build_qemu_argv(
    cfg: WindowsVmConfig,
    *,
    host_agent_port: int,
    qmp_socket: Path,
    tpm_socket: Path | None = None,
    ssh_host_port: int | None = None,
    load_snapshot: bool = True,
    ephemeral: bool = True,
    iso: Path | None = None,
    virtio_iso: Path | None = None,
    display: str = "none",
) -> list[str]:
    """Construct the QEMU/KVM command line for booting or restoring the Windows worker."""
    hostfwds = [f"hostfwd=tcp:127.0.0.1:{host_agent_port}-:{cfg.guest_agent_port}"]
    if ssh_host_port:
        hostfwds.append(f"hostfwd=tcp:127.0.0.1:{ssh_host_port}-:22")
    drive_spec = f"file={cfg.golden_disk},format=qcow2,if={cfg.disk_interface},cache=writeback"
    if cfg.disk_interface == "ide":
        drive_spec += ",index=0"
    argv = [
        "qemu-system-x86_64",
        "-enable-kvm",
        "-machine",
        f"{cfg.machine},accel=kvm",
        "-cpu",
        "host",
        "-smp",
        str(cfg.cpus),
        "-m",
        cfg.memory,
        "-drive",
        drive_spec,
        "-netdev",
        "user,id=net0," + ",".join(hostfwds),
        "-device",
        f"{cfg.nic},netdev=net0",
        "-qmp",
        f"unix:{qmp_socket},server=on,wait=off",
        "-display",
        display,
    ]
    if tpm_socket is not None:
        argv.extend(
            [
                "-chardev",
                f"socket,id=chrtpm,path={tpm_socket}",
                "-tpmdev",
                "emulator,id=tpm0,chardev=chrtpm",
                "-device",
                "tpm-tis,tpmdev=tpm0",
            ]
        )
    if ephemeral:
        argv.append("-snapshot")
    elif load_snapshot and cfg.snapshot_name:
        argv.extend(["-loadvm", cfg.snapshot_name])
    if iso is not None:
        argv.extend(["-cdrom", str(iso)])
    if virtio_iso is not None:
        argv.extend(["-drive", f"file={virtio_iso},media=cdrom"])
    return argv


def _start_swtpm(tpm_dir: Path) -> tuple[subprocess.Popen, Path]:
    tpm_dir.mkdir(parents=True, exist_ok=True)
    sock = tpm_dir / "swtpm.sock"
    sock.unlink(missing_ok=True)
    proc = subprocess.Popen(
        [
            "swtpm",
            "socket",
            "--tpm2",
            "--tpmstate",
            f"dir={tpm_dir}",
            "--ctrl",
            f"type=unixio,path={sock}",
            "--log",
            f"file={tpm_dir / 'swtpm.log'}",
        ],
        stdout=subprocess.DEVNULL,
        stderr=subprocess.DEVNULL,
    )
    deadline = time.monotonic() + 10.0
    while time.monotonic() < deadline:
        if sock.exists():
            return proc, sock
        time.sleep(0.05)
    proc.terminate()
    raise RuntimeError(f"swtpm did not create socket at {sock}")


class AgentRpcClient:
    """TCP JSON-RPC client speaking to windows_agent.py inside the KVM Windows VM."""

    def __init__(self, host: str, port: int, timeout: float = 30.0):
        self.host = host
        self.port = port
        self.timeout = timeout
        self._lock = threading.Lock()
        self._next_id = 1

    def call(self, op: str, **params: Any) -> dict[str, Any]:
        with self._lock:
            req_id = self._next_id
            self._next_id += 1
        payload = {"id": req_id, "op": op, **params}
        raw = (json.dumps(payload, separators=(",", ":")) + "\n").encode("utf-8")
        with socket.create_connection((self.host, self.port), timeout=self.timeout) as sock:
            sock.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
            sock.sendall(raw)
            line = sock.makefile("r", encoding="utf-8").readline()
        if not line:
            raise ConnectionError(f"Windows guest agent closed connection on {op!r}")
        resp = json.loads(line)
        if not resp.get("ok"):
            raise RuntimeError(f"Windows guest agent {op!r} failed: {resp.get('error')}")
        return resp


class _VmBridgeClient(BridgeClient):
    """BridgeClient that tunnels JSON-RPC calls through the Windows guest agent."""

    def __init__(self, agent: AgentRpcClient, guest_bridge_port: int, token: str, timeout: float = 20.0):
        super().__init__(host="127.0.0.1", port=guest_bridge_port, token=token, timeout=timeout)
        self._agent = agent

    def call(self, op: str, timeout: float | None = None, **params: Any) -> dict[str, Any]:
        req_id = self._next_id
        self._next_id += 1
        payload = {"reqId": req_id, "token": self.token, "op": op, **params}
        res = self._agent.call("bridge_call", port=self.port, payload=payload, timeout=timeout or self.timeout)
        inner = res["response"]
        if not inner.get("ok"):
            from .bridge import BridgeError

            raise BridgeError(str(inner.get("error", "bridge call failed")), code=str(inner.get("code", "")))
        return inner


class _SharedVmSession:
    """Owns one ephemeral -snapshot -loadvm Windows KVM worker per autotester run (or reuses a live agent)."""

    def __init__(self, cfg: WindowsVmConfig):
        self.cfg = cfg
        self._lock = threading.Lock()
        self._agent: AgentRpcClient | None = None
        self._failed: Exception | None = None
        self._vm_proc: subprocess.Popen | None = None
        self._tpm_proc: subprocess.Popen | None = None
        self._tmp_dir: Path | None = None

    def is_connected(self) -> bool:
        return self._agent is not None

    def agent(self) -> AgentRpcClient:
        with self._lock:
            if self._agent is not None:
                return self._agent
            if self._failed is not None:
                raise self._failed
            env_agent = os.environ.get("AUTOMODPACK_WINDOWS_AGENT", f"127.0.0.1:{self.cfg.guest_agent_port}")
            host, port_str = env_agent.rsplit(":", 1)
            try:
                probe = AgentRpcClient(host, int(port_str), timeout=2.0)
                probe.call("ping")
                self._agent = AgentRpcClient(host, int(port_str), timeout=30.0)
                return self._agent
            except Exception:
                pass
            if not self.cfg.golden_disk.is_file():
                err = RuntimeError(
                    f"No running Windows agent at {env_agent} and golden image not found at {self.cfg.golden_disk}. "
                    "Run `autotester windows-vm init --from-disk <win.qcow2>` and `autotester windows-vm snapshot` first."
                )
                self._failed = err
                raise err
            tmp_dir = Path(tempfile.mkdtemp(prefix="amp-winvm-"))
            self._tmp_dir = tmp_dir
            tpm_sock: Path | None = None
            if self.cfg.tpm:
                tpm_copy = tmp_dir / "tpm"
                golden_tpm = self.cfg.vm_dir / "tpm"
                if golden_tpm.is_dir():
                    shutil.copytree(golden_tpm, tpm_copy, ignore=shutil.ignore_patterns("*.sock"))
                self._tpm_proc, tpm_sock = _start_swtpm(tpm_copy)
            ports = PortReservation(1)
            host_port = ports.ports[0]
            ports.release()
            qmp = tmp_dir / "qmp.sock"
            # QEMU forbids combining -snapshot (anonymous overlay) with -loadvm (internal snapshot on the backing image);
            # an ephemeral -snapshot boot starts Windows + AutoModpackWindowsAgent cleanly in ~25s without mutating golden_disk.
            argv = build_qemu_argv(
                self.cfg,
                host_agent_port=host_port,
                qmp_socket=qmp,
                tpm_socket=tpm_sock,
                load_snapshot=False,
                ephemeral=True,
            )
            log = (tmp_dir / "qemu.log").open("wb")
            self._vm_proc = subprocess.Popen(argv, stdout=log, stderr=subprocess.STDOUT)
            probe_agent = AgentRpcClient("127.0.0.1", host_port, timeout=3.0)
            deadline = time.monotonic() + 180.0
            while time.monotonic() < deadline:
                if self._vm_proc.poll() is not None:
                    err = RuntimeError(f"QEMU exited early (code={self._vm_proc.returncode})\n{(tmp_dir / 'qemu.log').read_text(errors='replace')}")
                    self._failed = err
                    self._close_locked()
                    raise err
                try:
                    probe_agent.call("ping")
                    self._agent = AgentRpcClient("127.0.0.1", host_port, timeout=30.0)
                    return self._agent
                except Exception:
                    time.sleep(0.25)
            self._close_locked()
            err = TimeoutError("Windows KVM worker agent did not respond after boot/snapshot restore")
            self._failed = err
            raise err

    def close(self) -> None:
        with self._lock:
            self._close_locked()

    def _close_locked(self) -> None:
        if self._vm_proc is not None:
            self._vm_proc.terminate()
            try:
                self._vm_proc.wait(timeout=10)
            except subprocess.TimeoutExpired:
                self._vm_proc.kill()
            self._vm_proc = None
        if self._tpm_proc is not None:
            self._tpm_proc.terminate()
            try:
                self._tpm_proc.wait(timeout=5)
            except subprocess.TimeoutExpired:
                self._tpm_proc.kill()
            self._tpm_proc = None
        if self._tmp_dir is not None:
            shutil.rmtree(self._tmp_dir, ignore_errors=True)
            self._tmp_dir = None
        self._agent = None


def _disk_has_snapshot(disk: Path, name: str) -> bool:
    if not name or not shutil.which("qemu-img"):
        return False
    res = subprocess.run(["qemu-img", "snapshot", "-l", str(disk)], capture_output=True, text=True, check=False)
    return any(len(line.split()) >= 2 and line.split()[1] == name for line in res.stdout.splitlines())


class WindowsVmClient:
    """Runs a real Windows Minecraft client inside the provider's shared KVM Windows VM."""

    def __init__(self, name: str, target: Target, settings: dict, client_root: Path, artifact: Path, session: _SharedVmSession):
        self.name = name
        self.target = target
        self.settings = settings
        self.client_root = client_root
        self.game_dir = client_root / "game"
        self.artifact = artifact
        self._session = session
        self.token = secrets.token_hex(16)
        self.bridge: BridgeClient | None = None
        case_tag = f"{client_root.parent.parent.name}-{name}"
        self._proc_id = f"{case_tag}-{secrets.token_hex(2)}"
        self._guest_case_dir = rf"C:\amp\work\{case_tag}"
        self._guest_game_dir = rf"{self._guest_case_dir}\game"
        self._guest_appdata = rf"{self._guest_case_dir}\appdata"
        self._guest_hmc_cache = rf"C:\amp\hmc-cache\{target.id}"
        self._guest_java_home = rf"C:\jdk{target.java}"
        self._guest_bridge_port = next(_GUEST_PORT_COUNTER)

    def _agent(self) -> AgentRpcClient:
        return self._session.agent()

    def _win_path(self, rel_path: str) -> str:
        return self._guest_game_dir + "\\" + rel_path.replace("/", "\\")

    def _write_win_text(self, agent: AgentRpcClient, path: str, text: str) -> None:
        agent.call("write_file", path=path, contentB64=base64.b64encode(text.encode("utf-8")).decode("ascii"))

    def read_game_file(self, rel_path: str) -> str | None:
        try:
            res = self._agent().call("read_file", path=self._win_path(rel_path))
            return base64.b64decode(res["contentB64"]).decode("utf-8", errors="replace")
        except Exception:
            return None

    def write_game_file(self, rel_path: str, content: bytes) -> None:
        self._agent().call("write_file", path=self._win_path(rel_path), contentB64=base64.b64encode(content).decode("ascii"))

    def hold_file_lock(self, rel_path: str) -> str:
        return str(self._agent().call("hold_file_lock", path=self._win_path(rel_path))["lockId"])

    def release_file_lock(self, lock_id: str) -> None:
        self._agent().call("release_file_lock", lockId=lock_id)

    def create_junction(self, rel_link: str, rel_target: str) -> None:
        self._agent().call("create_junction", linkPath=self._win_path(rel_link), targetPath=self._win_path(rel_target))

    def _guest_env(self) -> dict[str, str]:
        return {
            "APPDATA": self._guest_appdata,
            "HMC_HOME": self._guest_hmc_cache,
            "JAVA_HOME": self._guest_java_home,
            "AUTOMODPACK_DATA_ROOT": "automodpack",
        }

    def _write_guest_hmc_config(self, agent: AgentRpcClient) -> None:
        player_name = f"amp_{self.name}"[:16]
        player_uuid = str(uuid.uuid3(uuid.NAMESPACE_DNS, f"OfflinePlayer:{player_name}"))
        props = (
            f"hmc.files.mc={self._guest_hmc_cache.replace(chr(92), '/')}\n"
            f"hmc.files.game={self._guest_game_dir.replace(chr(92), '/')}\n"
            "hmc.files.game-for-each-version=false\n"
            "hmc.java.download=false\n"
            f"hmc.java.versions={self._guest_java_home.replace(chr(92), '/')}\n"
            "hmc.launcher.crash-report-watcher=true\n"
            "hmc.jline.enabled=false\n"
            "hmc.xvfb.check=false\n"
            f"hmc.offline.username={player_name}\n"
            f"hmc.offline.uuid={player_uuid}\n"
        )
        self._write_win_text(agent, rf"{self._guest_appdata}\headlessmc\config.properties", props)

    def _ensure_guest_profile(self, agent: AgentRpcClient) -> None:
        with _GUEST_PREP_LOCK:
            if self.target.id in _GUEST_PREPARED_TARGETS:
                return
            receipt = rf"{self._guest_hmc_cache}\prepared-{self.target.id}.ok"
            if agent.call("file_exists", path=receipt).get("exists"):
                _GUEST_PREPARED_TARGETS.add(self.target.id)
                return
            prep_id = f"prep-{self.target.id}"
            argv = [r"C:\amp\hmc.exe", "version", "install", *profile_args(self.target)]
            agent.call("spawn", id=prep_id, argv=argv, cwd=self._guest_hmc_cache, env=self._guest_env())
            deadline = time.monotonic() + 300.0
            while time.monotonic() < deadline:
                st = agent.call("proc_status", id=prep_id)
                if not st.get("alive"):
                    if int(st.get("returncode") or 0) != 0:
                        raise RuntimeError(f"Windows HMC install failed for {self.target.id} (code={st.get('returncode')})")
                    self._write_win_text(agent, receipt, "ok\n")
                    _GUEST_PREPARED_TARGETS.add(self.target.id)
                    return
                time.sleep(0.5)
            raise TimeoutError(f"Windows HMC install for {self.target.id} timed out")

    def start(self, *, fast_timers: bool = True, render: bool = False, extra_jvm_args: list[str] | None = None) -> None:
        agent = self._agent()
        self._write_guest_hmc_config(agent)
        self._ensure_guest_profile(agent)
        self._write_win_text(agent, rf"{self._guest_game_dir}\options.txt", "onboardAccessibility:false\nskipMultiplayerWarning:true\n")
        if self.target.loader in ("forge", "neoforge"):
            self._write_win_text(agent, rf"{self._guest_game_dir}\config\fml.toml", "earlyWindowControl = false\n")
        jar_b64 = base64.b64encode(self.artifact.read_bytes()).decode("ascii")
        agent.call("write_file", path=rf"{self._guest_game_dir}\mods\{self.artifact.name}", contentB64=jar_b64)
        memory = str((self.settings.get("jvm") or {}).get("clientMemory", "1G"))
        jvm_args = [
            "-Xms256M",
            f"-Xmx{memory}",
            "-XX:TieredStopAtLevel=1",
            f"-Dautomodpack.autotest.gamedir={self._guest_game_dir.replace(chr(92), '/')}",
            f"-Dautomodpack.autotest.token={self.token}",
            "-Dautomodpack.autotest.port=0",
            f"-Dautomodpack.autotest.fastTimers={'true' if fast_timers else 'false'}",
            "-Dautomodpack.autotest.render=false",
            "-Dcom.mojang.text2speech=false",
            "-Djava.awt.headless=true",
            "-Dmcholepunch.debug=true",
            *(extra_jvm_args or []),
        ]
        argv = [
            r"C:\amp\hmc.exe",
            "launch",
            *profile_args(self.target),
            "--offline",
            "-lwjgl",
            "--jvm",
            " ".join(jvm_args),
            "--game",
            "--width 854 --height 480",
            "--resolution",
            "854x480",
        ]
        agent.call("spawn", id=self._proc_id, argv=argv, cwd=self._guest_game_dir, env=self._guest_env())

    def wait_ready(self, timeout: float = 180.0) -> BridgeClient:
        agent = self._agent()
        deadline = time.monotonic() + timeout
        last_err: Exception | None = None
        while time.monotonic() < deadline:
            st = agent.call("proc_status", id=self._proc_id)
            if not st.get("alive"):
                raise RuntimeError(f"Windows VM client {self.name!r} exited early (code={st.get('returncode')})\n--- log ---\n{self.logs(60)}")
            raw = self.read_game_file("automodpack/autotest/bridge-state.json")
            if raw:
                try:
                    data = json.loads(raw)
                    if data.get("status") == "ready" and data.get("port"):
                        self._guest_bridge_port = int(data["port"])
                        bridge = _VmBridgeClient(agent, self._guest_bridge_port, self.token)
                        bridge.call("ping", timeout=5.0)
                        self.bridge = bridge
                        return bridge
                except Exception as e:
                    last_err = e
            time.sleep(0.2)
        raise TimeoutError(f"Windows VM client {self.name!r} bridge did not become ready within {timeout:.0f}s (last={last_err})\n--- log ---\n{self.logs(60)}")

    def wait_exit(self, timeout: float = 60.0) -> int:
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            st = self._agent().call("proc_status", id=self._proc_id)
            if not st.get("alive"):
                return int(st.get("returncode") or 0)
            time.sleep(0.2)
        return 0

    def stop(self, *, force: bool = True) -> None:
        if not self._session.is_connected():
            return
        if self.bridge is not None and not force:
            try:
                self.bridge.quit()
            except Exception:
                pass
        try:
            if not force:
                self.wait_exit(timeout=15.0)
            self._agent().call("kill", id=self._proc_id)
        except Exception:
            pass

    def alive(self) -> bool:
        try:
            return bool(self._agent().call("proc_status", id=self._proc_id).get("alive"))
        except Exception:
            return False

    def logs(self, tail: int = 120) -> str:
        try:
            res = self._agent().call("read_file", path=rf"{self._guest_game_dir}\{self._proc_id}.stdout.log")
            text = base64.b64decode(res["contentB64"]).decode("utf-8", errors="replace")
            return "\n".join(text.splitlines()[-tail:])
        except Exception:
            return ""

    def game_log(self) -> str:
        return self.read_game_file("logs/latest.log") or ""


class WindowsVmProvider:
    """Runs the Minecraft server natively on Linux and the client inside a shared ephemeral KVM Windows VM."""

    def __init__(self, settings: dict):
        self.vm_cfg = WindowsVmConfig.from_settings(settings)
        self._session = _SharedVmSession(self.vm_cfg)
        self.capabilities = Capabilities(name="windows-vm", os_family="windows", native_windows_ntfs=True, rendered_ui=True)

    def prerequisites(self, settings: dict, targets: list[Target]) -> list[tuple[str, bool, str]]:
        kvm_ok = os.access("/dev/kvm", os.R_OK | os.W_OK)
        qemu = shutil.which("qemu-system-x86_64")
        disk_ok = self.vm_cfg.golden_disk.is_file()
        snap_ok = disk_ok and _disk_has_snapshot(self.vm_cfg.golden_disk, self.vm_cfg.snapshot_name)
        checks = [
            ("kvm", kvm_ok, "/dev/kvm readable & writable" if kvm_ok else "/dev/kvm not accessible"),
            ("qemu", bool(qemu), qemu or "qemu-system-x86_64 not on PATH"),
            ("windowsGoldenImage", disk_ok, str(self.vm_cfg.golden_disk) if disk_ok else f"missing ({self.vm_cfg.golden_disk})"),
            ("windowsSnapshot", snap_ok, f"{self.vm_cfg.snapshot_name!r} present" if snap_ok else f"{self.vm_cfg.snapshot_name!r} not saved yet (run `autotester windows-vm snapshot`)"),
        ]
        if self.vm_cfg.tpm:
            swtpm = shutil.which("swtpm")
            checks.append(("swtpm", bool(swtpm), swtpm or "swtpm not on PATH"))
        return checks

    def create_server(self, target: Target, settings: dict, server_dir: Path, artifact: Path) -> ServerProcess:
        return LocalServer(target, settings, server_dir, artifact, bind_ip="0.0.0.0", advertise_host="10.0.2.2")

    def create_client(self, name: str, target: Target, settings: dict, client_root: Path, artifact: Path) -> WindowsVmClient:
        return WindowsVmClient(name, target, settings, client_root, artifact, self._session)

    def close(self) -> None:
        self._session.close()


def cli_windows_vm(args, settings: dict) -> int:
    cfg = WindowsVmConfig.from_settings(settings)
    if args.vm_action == "status":
        kvm = os.access("/dev/kvm", os.R_OK | os.W_OK)
        qemu = shutil.which("qemu-system-x86_64") or "MISSING"
        snap = _disk_has_snapshot(cfg.golden_disk, cfg.snapshot_name) if cfg.golden_disk.is_file() else False
        print(f"KVM:          {'OK (/dev/kvm)' if kvm else 'UNAVAILABLE'}")
        print(f"QEMU:         {qemu}")
        print(f"Golden image: {cfg.golden_disk} ({'PRESENT' if cfg.golden_disk.is_file() else 'NOT CREATED'})")
        print(f"Snapshot:     {cfg.snapshot_name} ({'SAVED' if snap else 'NOT SAVED'})")
        return 0 if kvm and qemu != "MISSING" else 1

    if args.vm_action == "init":
        cfg.vm_dir.mkdir(parents=True, exist_ok=True)
        if getattr(args, "from_disk", None):
            backing = args.from_disk.expanduser().resolve()
            if not backing.is_file():
                print(f"Backing disk not found: {backing}", file=os.sys.stderr)
                return 1
            if cfg.golden_disk.exists():
                cfg.golden_disk.unlink()
            subprocess.run(["qemu-img", "create", "-f", "qcow2", "-b", str(backing), "-F", "qcow2", str(cfg.golden_disk)], check=True)
            src_tpm = backing.parent / "tpm"
            if src_tpm.is_dir():
                dst_tpm = cfg.vm_dir / "tpm"
                shutil.rmtree(dst_tpm, ignore_errors=True)
                shutil.copytree(src_tpm, dst_tpm, ignore=shutil.ignore_patterns("*.sock"))
            print(f"Created CoW golden overlay {cfg.golden_disk} backed by {backing}")
            return 0
        if not cfg.golden_disk.is_file():
            subprocess.run(["qemu-img", "create", "-f", "qcow2", str(cfg.golden_disk), args.disk_size], check=True)
            print(f"Created {cfg.golden_disk} ({args.disk_size})")
        if args.iso:
            qmp = cfg.vm_dir / "init-qmp.sock"
            tpm_proc, tpm_sock = (_start_swtpm(cfg.vm_dir / "tpm") if cfg.tpm else (None, None))
            try:
                argv = build_qemu_argv(
                    cfg,
                    host_agent_port=cfg.guest_agent_port,
                    qmp_socket=qmp,
                    tpm_socket=tpm_sock,
                    load_snapshot=False,
                    ephemeral=False,
                    iso=args.iso,
                    virtio_iso=args.virtio_iso,
                    display="gtk",
                )
                print("Launching Windows installer VM:", " ".join(argv))
                subprocess.run(argv, check=True)
            finally:
                if tpm_proc is not None:
                    tpm_proc.terminate()
        return 0

    if args.vm_action == "snapshot":
        if not cfg.golden_disk.is_file():
            print(f"Missing golden disk {cfg.golden_disk}; run `autotester windows-vm init` first.", file=os.sys.stderr)
            return 1
        qmp_path = cfg.vm_dir / "snap-qmp.sock"
        qmp_path.unlink(missing_ok=True)
        tpm_proc, tpm_sock = (_start_swtpm(cfg.vm_dir / "tpm") if cfg.tpm else (None, None))
        argv = build_qemu_argv(
            cfg,
            host_agent_port=cfg.guest_agent_port,
            qmp_socket=qmp_path,
            tpm_socket=tpm_sock,
            ssh_host_port=2222,
            load_snapshot=False,
            ephemeral=False,
        )
        proc = subprocess.Popen(argv, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        try:
            agent = AgentRpcClient("127.0.0.1", cfg.guest_agent_port, timeout=3.0)
            deadline = time.monotonic() + 180.0
            while time.monotonic() < deadline:
                if proc.poll() is not None:
                    raise RuntimeError(f"QEMU exited early while waiting for Windows agent (code={proc.returncode})")
                try:
                    agent.call("ping")
                    break
                except Exception:
                    time.sleep(1.0)
            else:
                raise TimeoutError("Windows guest agent did not respond within 180s (ensure windows_agent.py autostart is configured)")
            with socket.socket(socket.AF_UNIX, socket.SOCK_STREAM) as qmp:
                qmp.settimeout(180.0)
                qmp.connect(str(qmp_path))
                qmp_r = qmp.makefile("r", encoding="utf-8")
                qmp_r.readline()  # greeting
                qmp.sendall(b'{"execute":"qmp_capabilities"}\n')
                qmp_r.readline()  # capabilities ack
                cmd = json.dumps({"execute": "human-monitor-command", "arguments": {"command-line": f"savevm {cfg.snapshot_name}"}}) + "\n"
                qmp.sendall(cmd.encode("utf-8"))
                while True:
                    line = qmp_r.readline()
                    if not line:
                        raise RuntimeError("QMP closed while saving snapshot")
                    msg = json.loads(line)
                    if "error" in msg:
                        raise RuntimeError(f"QMP savevm failed: {msg['error']}")
                    if "return" in msg:
                        break
                qmp.sendall(b'{"execute":"quit"}\n')
            proc.wait(timeout=60)
            print(f"Saved live RAM snapshot {cfg.snapshot_name!r} in {cfg.golden_disk}")
            return 0
        finally:
            if proc.poll() is None:
                proc.terminate()
            if tpm_proc is not None:
                tpm_proc.terminate()
    return 1
