"""Runtime contracts for the autotester.

A scenario talks to client/server/hosting objects. Spawn strategy lives in
``host.backend`` (native Java children or Docker). The engine and its tests
never need a Docker daemon at import time.
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
