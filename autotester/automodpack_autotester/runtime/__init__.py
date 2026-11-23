"""Runtime contracts for the autotester.

A scenario runs against one platform. The contracts are dependency-free; the
providers live in ``runtime.docker_linux`` and are
resolved by ``runtime.detect``, so the engine and its tests never need a Docker
daemon at import time.
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
