"""Small source contracts for unavoidable Java and Stonecutter boundaries."""

from __future__ import annotations

from pathlib import Path

import pytest


def test_legacy_forge_keeps_loader_classes_out_of_nested_mod():
    project_root = Path(__file__).parents[2]
    build_script = (project_root / "build.forge.gradle.kts").read_text(encoding="utf-8")
    modpack_utils = (
        project_root
        / "loader/core/src/main/java/pl/skidam/automodpack_loader_core/client/ModpackUtils.java"
    ).read_text(encoding="utf-8")
    early_service_layer = (
        project_root
        / "loader/forge/earlyservices/src/main/java/pl/skidam/automodpack_loader_core_forge/EarlyServiceLayer.java"
    ).read_text(encoding="utf-8")

    assert 'compileOnly(project(":core")) { isTransitive = false }' in build_script
    assert (
        'compileOnly(project(":loader-core")) { isTransitive = false }' in build_script
    )
    assert (
        'implementation(project(":loader-core")) { isTransitive = false }'
        not in build_script
    )
    assert (
        "ManifestFetchState connectionFailedState = ManifestFetchState.CONNECTION_FAILED;"
        in modpack_utils
    )
    assert "return new ManifestFetchResult(connectionFailedState, null, null, cause);" in modpack_utils
    assert (
        'using the built-in fallback ({}: {})", t.getClass().getName(), t.getMessage())'
        in early_service_layer
    )


def test_autotest_bridge_readiness_is_level_triggered():
    source = (
        Path(__file__).parents[2]
        / "src/main/java/pl/skidam/automodpack/client/autotest/AutoTestBridge.java"
    ).read_text(encoding="utf-8")
    start = source[
        source.index("public static void start()") : source.index(
            "public static void onClientReady()"
        )
    ]
    mark_reload = source[
        source.index("public static void markReloadFinished()") : source.index(
            "public static void start()"
        )
    ]
    on_ready = source[
        source.index("public static void onClientReady()") : source.index(
            "private static void run("
        )
    ]
    publish = source[
        source.index("private static void publishReadyState()") : source.index(
            "private static void run("
        )
    ]
    run = source[
        source.index("private static void run(") : source.index(
            "private static String handle("
        )
    ]

    assert "if (currentScreen() instanceof TitleScreen && hasReloadFinished())" in start
    assert "RELOAD_FINISHED.set(true);" in mark_reload
    assert mark_reload.index("RELOAD_FINISHED.set(true);") < mark_reload.index(
        "onClientReady();"
    )
    assert "onClientReady();" in mark_reload
    assert "CLIENT_READY.set(true);" in on_ready
    assert "CLIENT_READY.compareAndSet(false, true)" not in on_ready
    assert "publishReadyState();" in on_ready
    assert "private static void publishReadyState()" in source
    assert "READY_STATE_PUBLISHED" in source
    assert "synchronized (READY_STATE_LOCK)" in publish
    assert "if (READY_STATE_PUBLISHED.get()) return;" in publish
    assert publish.index("writeFile(") < publish.index(
        "READY_STATE_PUBLISHED.set(true);"
    )
    assert "READY_STATE_WRITE_FAILED.compareAndSet(false, true)" in publish
    assert (
        'writeFile(dir.resolve("bridge-state.json"), "{\\"status\\":\\"ready\\"}");'
        in source
    )
    assert run.index("Files.createDirectories(dir)") < run.index("bridgeDir = dir;")
    assert run.count("publishReadyState();") >= 2


def test_autotest_screenshot_requires_a_settled_render_state():
    project_root = Path(__file__).parents[2]
    source = (
        project_root
        / "src/main/java/pl/skidam/automodpack/client/autotest/AutoTestBridge.java"
    ).read_text(encoding="utf-8")
    mixin = (
        project_root
        / "src/main/java/pl/skidam/automodpack/mixin/dev/GameRendererMixin.java"
    ).read_text(encoding="utf-8")
    capture = source[
        source.index("public static void onFrameRendered()") : source.index(
            "private static void completeScreenshot"
        )
    ]
    queue = source[
        source.index("private static void queueScreenshot") : source.index(
            "public static void onFrameRendered()"
        )
    ]
    screenshot = source[
        source.index("private static String screenshot") : source.index(
            "private static void queueScreenshot"
        )
    ]
    overlay_capture = source[
        source.index(
            "/*? if >=26.2 {*/", source.index("private record RenderedFrameState")
        ) : source.index(
            "/*?} else {*/", source.index("private record RenderedFrameState")
        )
    ]
    settled = source[
        source.index("private boolean isSettledAfter") : source.index(
            "private static void completeScreenshot"
        )
    ]

    assert "RenderedFrameState state = RenderedFrameState.capture();" in capture
    assert "state.isSettledAfter(previousState, targetScreen)" in capture
    assert "Screen targetScreen = currentScreen();" in queue
    assert "new PendingScreenshot(captured, path, targetScreen)" in queue
    assert "SCREENSHOT_SETTLE_TIMEOUT_SECONDS, TimeUnit.SECONDS" in screenshot
    assert "catch (TimeoutException e)" in screenshot
    assert "pending.logTimeout()" in screenshot
    assert "did not settle" in source
    assert (
        "return new RenderedFrameState(currentScreen(), minecraft.gui.overlay() != null);"
        in overlay_capture
    )
    assert (
        "return new RenderedFrameState(currentScreen(), false);" not in overlay_capture
    )
    assert "previous != null" in settled
    assert "screen == targetScreen && previous.screen == targetScreen" in settled
    assert "!overlayVisible && !previous.overlayVisible" in settled
    assert "screen == previous.screen" in settled
    assert "frameObserved" not in capture
    assert mixin.index("original.call(") < mixin.index(
        "AutoTestBridge.onFrameRendered();"
    )


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


def test_legacy_bridge_disconnect_uses_full_client_lifecycle():
    source = (
        Path(__file__).parents[2]
        / "src/main/java/pl/skidam/automodpack/client/autotest/AutoTestBridge.java"
    ).read_text(encoding="utf-8")

    assert "/*minecraft.disconnect(new TitleScreen());" in source
    assert "minecraft.clearLevel(new TitleScreen());" in source
    assert "/*minecraft.level.disconnect();" in source
    assert source.index("/*minecraft.level.disconnect();") < source.index(
        "minecraft.clearLevel(new TitleScreen());"
    )


def test_versioned_screen_legacy_tooltip_fallback_preserves_control_message():
    source = (
        Path(__file__).parents[2]
        / "src/main/java/pl/skidam/automodpack/client/ui/versioned/VersionedScreen.java"
    ).read_text(encoding="utf-8")
    modern_start = source.index("/*? > 1.19.2 {*/")
    legacy_start = source.index("/*?} else {*/", modern_start)
    legacy_end = source.index("*//*?}*/", legacy_start)
    modern = source[modern_start:legacy_start]
    legacy = source[legacy_start:legacy_end]

    assert "button.setTooltip(Tooltip.create(tooltip));" in modern
    assert "public static void setTooltip(Button button, Component tooltip)" in legacy
    assert "setMessage(tooltip)" not in legacy
    assert "Keep their existing message unchanged" in legacy


def test_error_screen_dispatch_requires_a_logged_throwable():
    project_root = Path(__file__).parents[2]
    screen_manager = (
        project_root
        / "loader/core/src/main/java/pl/skidam/automodpack_loader_core/screen/ScreenManager.java"
    ).read_text(encoding="utf-8")
    dispatch_sources = "\n".join(
        path.read_text(encoding="utf-8")
        for source_root in (project_root / "loader/core/src/main/java", project_root / "src/main/java")
        for path in source_root.rglob("*.java")
    )

    assert "public static void failure(FailureRequest request)" in screen_manager
    assert 'LOGGER.error("AutoModpack client failure [{}] while displaying {}", request.category().key(), request.messageKey(), request.cause());' in screen_manager
    assert screen_manager.count("LOGGER.error(") == 1
    assert "ScreenManager.error(\"" not in dispatch_sources
    assert "ScreenManager.report(\"" not in dispatch_sources
    assert "public void error(Throwable throwable, String... args)" not in screen_manager
    assert "public void report(Throwable throwable, String context)" not in screen_manager
    assert "ScreenManager.INSTANCE" not in dispatch_sources


def test_storage_cleanup_failure_uses_dedicated_error_screen():
    project_root = Path(__file__).parents[2]
    screen_manager = (
        project_root
        / "loader/core/src/main/java/pl/skidam/automodpack_loader_core/screen/ScreenManager.java"
    ).read_text(encoding="utf-8")
    storage_screen = (
        project_root
        / "src/main/java/pl/skidam/automodpack/client/ui/ClientStorageMaintenanceScreen.java"
    ).read_text(encoding="utf-8")

    assert "public static void failure(FailureRequest request)" in screen_manager
    assert 'ScreenManager.failure(FailureRequest.of(exception, "automodpack.error.storage"' in storage_screen
    assert "ScreenManager.report(" not in storage_screen
