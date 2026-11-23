"""Platform selection and scenario capability gating."""
from __future__ import annotations

import sys

from .base import CAPABILITY_REQUIREMENTS, KNOWN_PLATFORMS, Capabilities, Platform
from .docker_linux import DockerLinuxPlatform


class PlatformUnavailable(RuntimeError):
    """The requested platform has no runtime on this host, or no implementation yet."""


_REQUIREMENTS = {name: name for name in CAPABILITY_REQUIREMENTS}


def host_default() -> str:
    if sys.platform.startswith("linux"):
        return "linux"
    if sys.platform.startswith("win"):
        return "windows"
    return "linux"


def select_platform(settings: dict | None = None, requested: str | None = None) -> Platform:
    """Resolve ``--platform`` / settings ``platform:`` / the host default.

    ``linux`` is Docker everywhere Docker runs. A declared ``windows`` kernel is refused by
    name until a Windows runtime exists, rather than silently running the Linux containers.
    """
    settings = settings or {}
    name = str(requested or settings.get("platform") or host_default()).lower()
    if name == "linux":
        return DockerLinuxPlatform()
    if name == "windows":
        raise PlatformUnavailable("the Windows runtime is not implemented; the Linux provider is the only one")
    raise PlatformUnavailable(f"unknown platform {name!r} (available: {', '.join(sorted(KNOWN_PLATFORMS))})")


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


def case_skip_reason(scenario: dict, platform: Platform) -> str | None:
    """Why this scenario must not run on ``platform``, or ``None`` when it can."""
    if platform.name not in scenario_platforms(scenario):
        return f"scenario declares platforms {scenario_platforms(scenario)}, selected {platform.name}"
    missing = missing_capabilities(scenario, platform.capabilities)
    if missing:
        return f"selected platform {platform.name} lacks {', '.join(missing)}"
    return None


__all__ = [
    "PlatformUnavailable",
    "case_skip_reason",
    "host_default",
    "missing_capabilities",
    "scenario_platforms",
    "scenario_requirements",
    "select_platform",
]
