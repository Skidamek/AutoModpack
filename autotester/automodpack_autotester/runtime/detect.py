"""Skip reasons and which spawn strategy this process uses."""
from __future__ import annotations

import sys

from .base import CAPABILITY_REQUIREMENTS, Capabilities
from ..host.backend import FarmBackend, machine


_REQUIREMENTS = {name: name for name in CAPABILITY_REQUIREMENTS}


class PlatformUnavailable(RuntimeError):
    """The requested farm cannot run on this host."""


def kernel_name() -> str:
    return "windows" if sys.platform.startswith("win") else "linux"


def select_platform(settings: dict | None = None, requested: str | None = None, *, require_host: bool = True) -> FarmBackend:
    """Resolve ``--provide`` / settings ``farm.provide``.

    ``requested`` of ``linux``/``windows`` is the old ``--platform`` spelling: linux means docker on a Linux kernel, windows means native on Windows.
    """
    settings = settings or {}
    provide = None
    if requested in {"native", "docker"}:
        provide = requested
    elif requested in {"linux", "docker-linux"}:
        provide = "docker"
    elif requested == "windows":
        provide = "native"
        if require_host and not sys.platform.startswith("win"):
            raise PlatformUnavailable("the Windows native farm must run on Windows (it needs local NTFS paths and the HeadlessMC launcher)")
    elif requested:
        raise PlatformUnavailable(f"unknown farm {requested!r} (available: native, docker)")
    try:
        return machine(settings, provide=provide)
    except RuntimeError as error:
        raise PlatformUnavailable(str(error)) from error


def scenario_platforms(scenario: dict) -> list[str]:
    declared = scenario.get("platforms")
    if not declared:
        return ["linux"]
    if isinstance(declared, str):
        declared = [declared]
    return [str(name).lower() for name in declared]


def scenario_requirements(scenario: dict) -> list[str]:
    declared = scenario.get("requires")
    if not declared:
        return []
    if isinstance(declared, str):
        return [declared.lower()]
    return [str(name).lower() for name in declared]


def missing_capabilities(scenario: dict, capabilities: Capabilities) -> list[str]:
    missing = []
    for key in scenario_requirements(scenario):
        attribute = _REQUIREMENTS.get(key)
        if attribute is None or not getattr(capabilities, attribute, False):
            missing.append(key)
    return missing


def case_skip_reason(scenario: dict, platform) -> str | None:
    """Why this scenario must not run on this machine, or ``None`` when it can."""
    kernel = kernel_name()
    declared = scenario_platforms(scenario)
    if kernel not in declared:
        return f"scenario declares platforms {declared}, kernel is {kernel}"
    missing = missing_capabilities(scenario, platform.capabilities)
    if missing:
        return f"machine lacks {', '.join(missing)}"
    return None


__all__ = [
    "PlatformUnavailable",
    "case_skip_reason",
    "kernel_name",
    "machine",
    "missing_capabilities",
    "scenario_platforms",
    "scenario_requirements",
    "select_platform",
]
