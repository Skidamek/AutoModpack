"""In-memory runtimes for engine/step tests: no Docker, no processes."""
from __future__ import annotations

from automodpack_autotester.runtime.base import Capabilities, ExecResult, RuntimeState


class FakeClientRuntime:
    capabilities = Capabilities()

    def __init__(self, name: str = "cli-container", prep_name: str = "prep-container"):
        self.name = name
        self.prep_name = prep_name
        self.calls: list[tuple] = []
        self.log_text = ""
        self.prep_log_text = ""
        self.running_error: Exception | None = None
        self.exit_code_value: int | None = 0
        self.prep_state = RuntimeState(False, 0)
        self.profile_ready = True
        self.wait_prepare_error: Exception | None = None
        self.wait_exited_error: Exception | None = None

    def start(self, ctx) -> None:
        self.calls.append(("start", self.name))

    def start_prepare(self, ctx) -> None:
        self.calls.append(("start_prepare", self.prep_name))

    def wait_prepare_exited(self, timeout: float) -> None:
        self.calls.append(("wait_prepare_exited", self.prep_name, timeout))
        if self.wait_prepare_error is not None:
            raise self.wait_prepare_error

    def prepare_state(self) -> RuntimeState:
        return self.prep_state

    def prepare_logs(self, tail: int | None = None) -> str:
        return self.prep_log_text

    def stop_prepare(self) -> None:
        self.calls.append(("stop_prepare", self.prep_name))

    def profile_prepared(self, ctx) -> bool:
        return self.profile_ready

    def record_profile(self, ctx) -> None:
        self.calls.append(("record_profile",))

    def stop(self) -> None:
        self.calls.append(("stop", self.name))

    def running(self) -> None:
        if self.running_error is not None:
            raise self.running_error

    def logs(self, tail: int | None = None) -> str:
        return self.log_text

    def wait_exited(self, timeout: float) -> None:
        self.calls.append(("wait_exited", self.name, timeout))
        if self.wait_exited_error is not None:
            raise self.wait_exited_error

    def exit_code(self) -> int | None:
        return self.exit_code_value

    def state(self) -> RuntimeState:
        return RuntimeState(self.running_error is None, self.exit_code_value)

    def apply_netem(self, ctx) -> None:
        self.calls.append(("apply_netem", tuple(ctx.netem)))


class FakeServerRuntime:
    capabilities = Capabilities()

    def __init__(self, name: str = "srv-container"):
        self.name = name
        self.calls: list[tuple] = []
        self.log_text = ""
        self.exec_result = ExecResult(0, "")
        self.wait_for_log_error: Exception | None = None
        self.restart_error: Exception | None = None

    def start(self, ctx, spec) -> None:
        self.calls.append(("start", spec))

    def stop(self) -> None:
        self.calls.append(("stop", self.name))

    def logs(self, tail: int | None = None) -> str:
        return self.log_text

    def exec(self, argv: list[str], *, user: str | None = None) -> ExecResult:
        self.calls.append(("exec", tuple(argv), user))
        return self.exec_result

    def restart(self, timeout: float = 60) -> None:
        self.calls.append(("restart", timeout))
        if self.restart_error is not None:
            raise self.restart_error

    def wait_for_log(self, needle: str, timeout: float) -> None:
        self.calls.append(("wait_for_log", needle, timeout))
        if self.wait_for_log_error is not None:
            raise self.wait_for_log_error

    def apply_netem(self, ctx, qdisc: list[str], label: str) -> None:
        self.calls.append(("apply_netem", tuple(qdisc), label))


class FakeHostingRuntime:
    def __init__(self):
        self.calls: list[tuple] = []
        self.log_text: dict[str, str] = {}
        self.exit_codes: dict[str, int] = {}
        self.wait_for_log_error: Exception | None = None

    def run(self, name: str, **kwargs) -> None:
        self.calls.append(("run", name, kwargs))

    def logs(self, name: str, tail: int | None = None) -> str:
        return self.log_text.get(name, "")

    def wait_for_log(self, name: str, needle: str, timeout: float) -> None:
        self.calls.append(("wait_for_log", name, needle, timeout))
        if self.wait_for_log_error is not None:
            raise self.wait_for_log_error

    def wait_exited(self, name: str, timeout: float) -> None:
        self.calls.append(("wait_exited", name, timeout))

    def exit_code(self, name: str) -> int | None:
        return self.exit_codes.get(name)

    def remove(self, name: str) -> None:
        self.calls.append(("remove", name))

    def ensure_network(self, name: str, labels: dict[str, str] | None = None) -> None:
        self.calls.append(("ensure_network", name, labels))

    def remove_network(self, name: str) -> None:
        self.calls.append(("remove_network", name))
