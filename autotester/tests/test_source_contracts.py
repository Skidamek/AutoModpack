"""Contract for the stonecutter-generated 26.2 screenshot branch.

The source-shape checks against hand-written Java were removed as change-detectors;
this one stays because it reads real stonecutter output and only runs when the
generated sources exist.
"""
from __future__ import annotations

from pathlib import Path

import pytest


def test_autotest_screenshot_generated_26_2_branch_uses_gui_overlay_when_available():
    project_root = Path(__file__).parents[2]
    candidates = (
        project_root
        / "versions/26.2-fabric/build/generated/stonecutter/main/java/pl/skidam/automodpack/client/autotest/AutoTestBridge.java",
        project_root
        / "versions/26.2-fabric/build/stonecutter-cache/sources/main/java/pl/skidam/automodpack/client/autotest/AutoTestBridge.java",
        project_root
        / "versions/26.2-neoforge/build/generated/stonecutter/main/java/pl/skidam/automodpack/client/autotest/AutoTestBridge.java",
        project_root
        / "versions/26.2-neoforge/build/stonecutter-cache/sources/main/java/pl/skidam/automodpack/client/autotest/AutoTestBridge.java",
    )
    generated = next((path for path in candidates if path.is_file()), None)
    if generated is None:
        pytest.skip(
            "run ./gradlew -Pautomodpack.autotest :26.2-fabric:compileJava or :26.2-neoforge:compileJava to materialize the generated branch"
        )

    generated_source = generated.read_text(encoding="utf-8")
    capture = generated_source[
        generated_source.index(
            "private record RenderedFrameState"
        ) : generated_source.index("private static void completeScreenshot")
    ]
    assert "minecraft.gui.overlay() != null" in capture
    assert "return new RenderedFrameState(currentScreen(), false);" not in capture
