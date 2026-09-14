package pl.skidam.automodpack_core.screen;

import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Supplier;

import pl.skidam.automodpack_core.client.SourceAvailability;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;

/**
 * The actions and live polls a review-backed screen may drive, bundled so the screen seam never names the update
 * engine. Every member is backed by the review flow that built the payload; polls answer the screen's questions in
 * screen vocabulary (is the review still open, was it cancelled, is a preview being reviewed right now) rather than
 * in engine states.
 */
public record ReviewActions(Consumer<Boolean> setFirstInstallLocalModCleanup, Runnable startConfirmedUpdate, Consumer<SelectionIntent> reselectAndPreview,
		Runnable cancelConfirmation, Runnable cancelFromPlayer, BooleanSupplier reviewActive, BooleanSupplier reviewCancelled, BooleanSupplier cancelledByPlayer,
		BooleanSupplier reviewPreviewing, Supplier<SourceAvailability> sourceAvailability) {}
