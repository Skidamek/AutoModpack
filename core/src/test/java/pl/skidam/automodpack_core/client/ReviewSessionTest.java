package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.loader.ModpackLoadRequest;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.modpack.generation.TestPacks;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.screen.ReviewActions;
import pl.skidam.automodpack_core.storage.TestDataRoot;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/**
 * Drives the review state machine through the same payload/actions surface a screen uses, on the engine's own review
 * instance. The decline-and-detach persistence needs a prepared plan and stays with the e2e scenarios.
 */
class ReviewSessionTest {
	@TempDir
	Path temporaryDirectory;

	@Test
	void welcomeOpensTheReviewAndConfirmDrainsToCancelledWhenPreparationCannotRun() throws Exception {
		Harness harness = harness(false);

		harness.review().beginFirstInstallReview();

		assertTrue(harness.actions().reviewActive().getAsBoolean());
		assertFalse(harness.actions().reviewCancelled().getAsBoolean());
		assertTrue(harness.updater().fullDownload);

		harness.actions().startConfirmedUpdate().run();

		// The drain cannot prepare anything without a live connection, so it closes the engine and the review lands on CANCELLED.
		awaitTrue(() -> harness.actions().reviewCancelled().getAsBoolean());
		assertFalse(harness.actions().reviewActive().getAsBoolean());
	}

	@Test
	void cancelConfirmationFromWaitingMarksTheReviewCancelledAndClosesTheEngine() throws Exception {
		Harness harness = harness(false);

		harness.review().beginFirstInstallReview();
		harness.actions().cancelConfirmation().run();

		assertTrue(harness.actions().reviewCancelled().getAsBoolean());
	}

	@Test
	void playerCancelDrainsBackToAnOpenReviewForAFollowUpConfirmation() throws Exception {
		Harness harness = harness(false);

		harness.review().beginFirstInstallReview();
		harness.actions().cancelFromPlayer().run();
		assertTrue(harness.actions().cancelledByPlayer().getAsBoolean());

		// The flag stays raised until drained work observes it; a failed preparation drains through confirmCancellationHandled.
		harness.actions().startConfirmedUpdate().run();
		// The failed preparation drains through confirmCancellationHandled: the review returns to WAITING and the
		// cancel flag clears, so a follow-up confirmation can never race a still-draining run.
		awaitTrue(() -> harness.actions().reviewActive().getAsBoolean());
		assertFalse(harness.actions().cancelledByPlayer().getAsBoolean());
	}

	@Test
	void cleanupConsentFollowsTheReviewState() throws Exception {
		Harness harness = harness(true);

		// The consent checkbox is inert before the review is open.
		harness.actions().setFirstInstallLocalModCleanup().accept(true);
		assertTrue(harness.review().consentedLocalModFiles().isEmpty());

		harness.review().beginFirstInstallReview();
		harness.actions().setFirstInstallLocalModCleanup().accept(true);
		assertEquals(1, harness.review().consentedLocalModFiles().size());

		harness.actions().setFirstInstallLocalModCleanup().accept(false);
		assertTrue(harness.review().consentedLocalModFiles().isEmpty());

		harness.actions().cancelConfirmation().run();
		harness.actions().setFirstInstallLocalModCleanup().accept(true);
		assertTrue(harness.review().consentedLocalModFiles().isEmpty());
	}

	private record Harness(ModpackUpdater updater, ReviewSession review, ReviewActions actions) {}

	/** One engine on empty storage, with an optional leftover file in the loader-visible mods directory for the consent scan. */
	private Harness harness(boolean leftoverMod) throws Exception {
		// The plan builder rejects a missing loader; tests have no Preload, so a no-op loader stands in for the real one.
		if (Constants.MODPACK_LOADER == null) {
			Constants.MODPACK_LOADER = new ModpackLoaderService() {
				@Override
				public void loadModpack(ModpackLoadRequest request) {}

				@Override
				public List<FileInspection.Mod> getModpackNestedConflicts(Path activeProjectionDirectory, FileCache cache) {
					return List.of();
				}
			};
			Constants.LOADER = "fabric";
		}
		ClientStorage storage = TestDataRoot.open(temporaryDirectory.resolve("game" + System.nanoTime()), temporaryDirectory.resolve("data" + System.nanoTime()));
		if (leftoverMod) {
			Files.createDirectories(storage.modsDirectory());
			Files.writeString(storage.modsDirectory().resolve("leftover.jar"), "not a real mod jar");
		}
		GroupManifest manifest = TestPacks.manifest("test pack", "config/a.txt", "hello");
		SelectedModpackTarget target = SelectedModpackTarget.prepareDefault(TestPacks.head(manifest), ClientPlatform.LINUX);
		ModpackUpdater updater = new ModpackUpdater(target, null, null, storage);
		return new Harness(updater, updater.reviewSession(), updater.reviewActions());
	}

	private static void awaitTrue(BooleanSupplier condition) throws InterruptedException {
		long deadline = System.currentTimeMillis() + 5_000;
		while (System.currentTimeMillis() < deadline) {
			if (condition.getAsBoolean()) return;
			Thread.sleep(20);
		}
		fail("condition did not become true in time");
	}
}
