"""Declarative step engine for autotester scenarios.

Importing this package registers the built-in UI and filesystem verbs. The runner
registers the remaining lifecycle verbs (launch/connect/quit/...) when it loads.
"""
from . import steps_io, steps_ui  # noqa: F401  -- import for verb registration
from .context import ClientExited, Context
from .executor import run_flow
from .registry import get, verb

__all__ = ["Context", "ClientExited", "run_flow", "verb", "get"]
