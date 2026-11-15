"""Runtime contracts for the autotester.

A scenario runs against one platform. The contracts are dependency-free; the
Docker Linux provider lives in ``runtime.docker_linux`` and is imported by the
runner so the engine and its tests never need a Docker daemon at import time.
"""
from .base import (
    Capabilities,
    ClientRuntime,
    ExecResult,
    HostingRuntime,
    Platform,
    RuntimeState,
    ServerRuntime,
    ServerSpec,
    ServerWorkspace,
    StageTree,
    StagedFile,
    TreeMeasurement,
)

__all__ = [
    "Capabilities",
    "ClientRuntime",
    "ExecResult",
    "HostingRuntime",
    "Platform",
    "RuntimeState",
    "ServerRuntime",
    "ServerSpec",
    "ServerWorkspace",
    "StageTree",
    "StagedFile",
    "TreeMeasurement",
]
