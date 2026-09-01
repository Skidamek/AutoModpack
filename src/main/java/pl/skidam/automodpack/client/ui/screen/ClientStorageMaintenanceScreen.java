package pl.skidam.automodpack.client.ui.screen;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;

import java.util.List;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.utils.cache.ClientObjectStore;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Provides an explicit, user-confirmed cleanup pass for client generation storage. */
public final class ClientStorageMaintenanceScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 310;

	private final Screen parent;
	private final InstalledModpackController controller;
	private boolean busy;
	private boolean closed;
	private boolean presentingFailure;
	private Operation operation;
	private ClientGenerationStore.CompactionResult compactionResult;
	private ClientObjectStore.StorageReport verificationReport;
	private Future<?> work;
	private int preservedCount;

	public ClientStorageMaintenanceScreen(Screen parent, InstalledModpackController controller) {
		super(VersionedText.translatable("automodpack.storage.title"));
		this.parent = parent;
		this.controller = controller;
	}

	@Override
	protected void init() {
		super.init();
		preservedCount = controller.preservedClaimCount();
		int actionY = this.height - 28;
		List<Button> buttons = addActionArea(PANEL_WIDTH, actionY,
				actionRow(ActionAreaLayout.RowKind.AUXILIARY,
						optionalAction(VersionedText.translatable("automodpack.storage.verify"), button -> verify()),
						primaryAction(VersionedText.translatable("automodpack.storage.confirm"), button -> compact())),
				actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), button -> closeToParent())));
		buttons.get(0).active = !busy && !closed;
		buttons.get(1).active = !busy && !closed;
	}

	private void verify() {
		if (busy || closed) return;
		begin(Operation.VERIFY);
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				ClientObjectStore.StorageReport report = controller.validateStorage();
				this.minecraft.execute(() -> finishVerification(report));
			} catch (Exception exception) {
				this.minecraft.execute(() -> fail(exception));
			}
		});
	}

	private void compact() {
		if (busy || closed) return;
		begin(Operation.COMPACT);
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				ClientGenerationStore.CompactionResult compacted = controller.compactStorage();
				this.minecraft.execute(() -> finish(compacted));
			} catch (Exception exception) {
				this.minecraft.execute(() -> fail(exception));
			}
		});
	}

	private void begin(Operation nextOperation) {
		busy = true;
		operation = nextOperation;
		compactionResult = null;
		verificationReport = null;
		rebuild();
	}

	private void finish(ClientGenerationStore.CompactionResult compacted) {
		if (closed) return;
		compactionResult = compacted;
		operation = null;
		busy = false;
		rebuild();
	}

	private void finishVerification(ClientObjectStore.StorageReport report) {
		if (closed) return;
		verificationReport = report;
		operation = null;
		busy = false;
		rebuild();
	}

	private void fail(Exception exception) {
		if (closed) return;
		busy = false;
		operation = null;
		presentingFailure = true;
		ScreenManager.failure(FailureRequest.of(exception, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
	}

	private void closeToParent() {
		if (closed) return;
		closed = true;
		cancelWork();
		ScreenImpl.setScreen(parent);
	}

	private void cancelWork() {
		Future<?> currentWork = work;
		// cancel(false) only prevents queued work from starting; an active compaction continues without interruption.
		if (currentWork != null && !currentWork.isDone()) currentWork.cancel(false);
	}

	private void rebuild() {
		/*? if >=1.19.2 {*/
		this.rebuildWidgets();
		/*?} else {*/
		/*
		this.init(this.minecraft, this.width, this.height);
		*//*?}*/
	}

	@Override
	public void removed() {
		if (presentingFailure) {
			presentingFailure = false;
			super.removed();
			return;
		}
		if (!closed) {
			closed = true;
			cancelWork();
		}
		super.removed();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		int textWidth = panelWidth(PANEL_WIDTH);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.storage.title").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
		int y = 32;
		y = drawWrapped(matrices, VersionedText.translatable("automodpack.storage.description").getString(), y, textWidth, TextColors.LIGHT_GRAY);
		y = drawWrapped(matrices, VersionedText.translatable("automodpack.storage.removes").getString(), y + 4, textWidth, TextColors.YELLOW);
		y = drawWrapped(matrices, VersionedText.translatable("automodpack.storage.keeps").getString(), y + 4, textWidth, TextColors.GREEN);
		y = drawWrapped(matrices, VersionedText.translatable(preservedCount > 0 ? "automodpack.storage.preservedKept" : "automodpack.vault.empty", preservedCount).getString(), y + 4, textWidth, TextColors.GREEN);
		if (busy) {
			String message = operation == Operation.VERIFY ? "automodpack.storage.verifying" : "automodpack.storage.running";
			drawWrapped(matrices, VersionedText.translatable(message).getString(), y + 8, textWidth, TextColors.YELLOW);
		} else if (compactionResult != null) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.storage.complete").withStyle(ChatFormatting.GREEN), this.width / 2, y + 8, TextColors.WHITE);
			drawStats(matrices, compactionResult, y + 26, textWidth);
		} else if (verificationReport != null) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.storage.verified").withStyle(ChatFormatting.GREEN), this.width / 2, y + 8, TextColors.WHITE);
			String receipt = VersionedText.translatable("automodpack.storage.verificationReceipt", verificationReport.validReferencedObjectCount(), verificationReport.referencedObjectCount(),
					UiFormat.formatSize(verificationReport.validReferencedObjectBytes())).getString();
			drawWrapped(matrices, receipt, y + 26, textWidth, TextColors.WHITE);
		}
	}

	private int drawWrapped(VersionedMatrices matrices, String text, int y, int width, int color) {
		for (String line : wrapToWidth(this.font, text, width, 5)) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line), this.width / 2, y, color);
			y += 12;
		}
		return y;
	}

	/** One stats line per fact; the before -> after shape lives here so it cannot drift per locale. */
	private void drawStats(VersionedMatrices matrices, ClientGenerationStore.CompactionResult compacted, int y, int textWidth) {
		y = drawWrapped(matrices, statLine("automodpack.storage.records", compacted.generationRecordCountBefore(), compacted.generationRecordCountAfter(), UiFormat.formatSize(compacted.generationRecordBytesBefore()), UiFormat.formatSize(compacted.generationRecordBytesAfter())), y, textWidth, TextColors.WHITE);
		y = drawWrapped(matrices, statLine("automodpack.storage.objects", compacted.objectCollection().before().objectCount(), compacted.objectCollection().after().objectCount(), UiFormat.formatSize(compacted.objectCollection().before().objectBytes()), UiFormat.formatSize(compacted.objectCollection().after().objectBytes())), y, textWidth, TextColors.WHITE);
		drawWrapped(matrices, statLine("automodpack.storage.generatedCopies", compacted.generatedCopyCountBefore(), compacted.generatedCopyCountAfter(), UiFormat.formatSize(compacted.generatedCopyBytesBefore()), UiFormat.formatSize(compacted.generatedCopyBytesAfter())), y, textWidth, TextColors.GRAY);
	}

	private String statLine(String labelKey, long countBefore, long countAfter, String sizeBefore, String sizeAfter) {
		return VersionedText.translatable(labelKey).getString() + ": " + countBefore + " -> " + countAfter + " (" + sizeBefore + " -> " + sizeAfter + ")";
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::closeToParent);
	}

	private enum Operation { VERIFY, COMPACT }
}
