"""Platform contract for the autotester.

A scenario talks to a *platform*: one client runtime, one server runtime and one
hosting runtime. Step modules only see these protocols, so a Windows native
client can replace the Docker client without touching the scenario DSL, the
bridge or the assertions.

Deletion correctness is post-restart absence on every OS. Apply timing (immediate
unlink on Unix, at-close/on-restart on Windows) lives in the Java deleter, not
in these capabilities or in scenario assertions.
"""
from __future__ import annotations

from dataclasses import dataclass, field
from pathlib import Path
from typing import Protocol, runtime_checkable

KNOWN_PLATFORMS = frozenset({"linux", "windows"})
CAPABILITY_REQUIREMENTS = frozenset({
    "netem",
    "rendered_ui",
    "junction_probe",
    "native_client",
    "native_server",
    "container_network",
})


@dataclass(frozen=True)
class Capabilities:
    """What a selected platform can actually exercise."""

    container_client: bool = True
    container_server: bool = True
    container_network: bool = True
    native_client: bool = False
    native_server: bool = False
    netem: bool = True
    rendered_ui: bool = True
    junction_probe: bool = False


@dataclass(frozen=True)
class ExecResult:
    """The platform-neutral shape of one command run inside the server/client."""

    exit_code: int
    output: str


@dataclass(frozen=True)
class RuntimeState:
    running: bool
    exit_code: int | None = None


@dataclass(frozen=True)
class StagedFile:
    """One file in a staged server tree.

    Exactly one of ``content`` / ``size_bytes`` is set. ``size_bytes`` writes the
    deterministic ASCII fill the harness has always used; ``fill_name`` is the
    logical path that seeds that fill (a file keeps its bytes when its group
    directory changes), defaulting to ``path``.
    """

    path: str
    content: bytes | None = None
    size_bytes: int | None = None
    fill_name: str | None = None


@dataclass(frozen=True)
class StageTree:
    """A replace-and-write description of one server-side tree."""

    root: str
    files: tuple[StagedFile, ...]
    replace: bool = True


@dataclass(frozen=True)
class TreeMeasurement:
    """Regular-file count and total bytes below one workspace path."""

    count: int
    bytes: int


@dataclass(frozen=True)
class ServerSpec:
    """Everything the server needs for one launch, in the dialect each strategy speaks.

    ``env`` is the container dialect plus the mod's own environment contract; the native
    server passes the same environment to its child so both strategies read one truth.
    ``memory`` and ``jvm_args`` are launch parameters: itzg takes them as ``MEMORY`` and
    ``JVM_OPTS``, a native ``java -jar`` takes them as ``-Xmx`` and plain JVM arguments.
    """

    image: str
    env: dict[str, str]
    memory: str = "2G"
    jvm_args: tuple[str, ...] = ()
    mounts: tuple[tuple[object, str, bool], ...] = ()
    cap_add: tuple[str, ...] = ()
    sysctls: dict[str, str] | None = None
    cache_volume: str | None = None
    clean_cache: bool = False
    labels: dict[str, str] = field(default_factory=dict)


@runtime_checkable
class ServerWorkspace(Protocol):
    """The server's durable files, addressed by platform-neutral relative paths."""

    def read_text(self, rel: str) -> str:
        """Read one UTF-8 text file from the workspace."""

    def write_text(self, rel: str, text: str) -> None:
        """Write one UTF-8 text file with LF endings, creating parents."""

    def write_bytes(self, rel: str, data: bytes) -> None:
        """Write one binary file, creating parents."""

    def copy_in(self, rel: str, source: Path) -> None:
        """Copy one controller file into the workspace without loading it into RAM."""

    def ensure_dir(self, rel: str) -> None:
        """Create one directory (and its parents) in the workspace."""

    def stage_tree(self, plan: StageTree) -> None:
        """Replace ``plan.root`` and materialize every declared file."""

    def measure_tree(self, rel: str) -> TreeMeasurement:
        """Count regular files and bytes below ``rel``."""

    def exists(self, rel: str) -> bool:
        """Whether ``rel`` exists in the workspace."""

    def remove_tree(self, rel: str) -> None:
        """Remove one file or directory tree if present."""

    def data_mounts(self, cache_volume: str | None) -> tuple[tuple[object, str, bool], ...]:
        """Mounts that place this workspace's durable tree at ``/data``."""

    def host_path(self, rel: str) -> Path:
        """Controller path for a read-only sidecar mount."""


@runtime_checkable
class ClientRuntime(Protocol):
    """One client process/container for one case."""

    name: str

    def start(self, ctx) -> None:
        """Launch the player client (the game directory is already staged)."""

    def start_prepare(self, ctx) -> None:
        """Launch a profile-preparation run and return without waiting."""

    def wait_prepare_exited(self, timeout: float) -> None:
        """Block until the preparation run exits."""

    def prepare_state(self) -> RuntimeState:
        """Current state of the preparation run."""

    def prepare_logs(self, tail: int | None = None) -> str:
        """Logs of the preparation run."""

    def stop_prepare(self) -> None:
        """Remove/kill the preparation run if it exists."""

    def profile_prepared(self, ctx) -> bool:
        """True when the loader profile receipt matches this case."""

    def record_profile(self, ctx) -> None:
        """Persist the prepared-profile receipt after a successful launch."""

    def stop(self) -> None:
        """Remove/kill the player client."""

    def running(self) -> None:
        """Raise when the client is not running."""

    def logs(self, tail: int | None = None) -> str:
        """Recent client output."""

    def wait_exited(self, timeout: float) -> None:
        """Block until the client exits."""

    def exit_code(self) -> int | None:
        """Exit code of a stopped client."""

    def state(self) -> RuntimeState:
        """Running flag plus exit code for the player client."""

    def apply_netem(self, ctx) -> None:
        """Shape the client's egress with the case's ``--netem`` qdisc."""


@runtime_checkable
class ServerRuntime(Protocol):
    """One server for one case."""

    def start(self, ctx, spec: ServerSpec) -> None:
        """Create storage and launch the server."""

    def stop(self) -> None:
        """Remove/kill the server."""

    def logs(self, tail: int | None = None) -> str:
        """Recent server output."""

    def exec(self, argv: list[str], *, user: str | None = None) -> ExecResult:
        """Run one command in the server environment."""

    def restart(self, timeout: float = 60) -> None:
        """Restart the server in place, keeping durable state."""

    def wait_for_log(self, needle: str, timeout: float) -> None:
        """Block until ``needle`` appears in the server output."""

    def apply_netem(self, ctx, qdisc: list[str], label: str) -> None:
        """Shape the server's egress with one root qdisc."""


@runtime_checkable
class HostingRuntime(Protocol):
    """Short-lived side services: static HTTPS host, MinIO, upload jobs."""

    def run(
        self,
        name: str,
        *,
        image: str,
        network: str,
        env: dict[str, str],
        mounts: list[tuple[object, str, bool]] | tuple[tuple[object, str, bool], ...] = (),
        command: list[str] | None = None,
        entrypoint: list[str] | None = None,
        aliases: list[str] | None = None,
        labels: dict[str, str] | None = None,
    ) -> None:
        """Start one service container/process."""

    def logs(self, name: str, tail: int | None = None) -> str: ...

    def wait_for_log(self, name: str, needle: str, timeout: float) -> None: ...

    def wait_exited(self, name: str, timeout: float) -> None: ...

    def exit_code(self, name: str) -> int | None: ...

    def remove(self, name: str) -> None: ...

    def ensure_network(self, name: str, labels: dict[str, str] | None = None) -> None: ...

    def remove_network(self, name: str) -> None: ...


@runtime_checkable
class Platform(Protocol):
    """A selectable run platform."""

    name: str
    capabilities: Capabilities

    def client(self, ctx) -> ClientRuntime: ...

    def server(self, ctx) -> ServerRuntime: ...

    def hosting(self, ctx) -> HostingRuntime: ...

    def workspace(self, ctx) -> ServerWorkspace: ...

    def prerequisites(self, settings: dict) -> list[tuple[str, bool, str]]:
        """``(name, ok, detail)`` checks ``doctor`` reports for this platform, beyond the shared ones."""

    def abort_case(self, ctx) -> None:
        """Best-effort teardown used by the case deadline thread."""


__all__ = [
    "CAPABILITY_REQUIREMENTS",
    "Capabilities",
    "ClientRuntime",
    "ExecResult",
    "HostingRuntime",
    "KNOWN_PLATFORMS",
    "Platform",
    "RuntimeState",
    "ServerRuntime",
    "ServerSpec",
    "ServerWorkspace",
    "StageTree",
    "StagedFile",
    "TreeMeasurement",
]
