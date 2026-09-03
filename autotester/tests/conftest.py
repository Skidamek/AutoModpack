"""Test wiring only. Fixtures live in focused modules beside this file:

- ``fixtures_ctx.make_ctx``: the Docker-free Context factory fixture.
- ``fake_bridge.FakeBridge``: the scriptable fake bridge tests attach to a Context.
"""
from __future__ import annotations

pytest_plugins = ["tests.fixtures_ctx"]
