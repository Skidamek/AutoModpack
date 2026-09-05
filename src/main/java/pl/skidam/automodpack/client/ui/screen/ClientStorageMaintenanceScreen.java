package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Provides an explicit, user-confirmed cleanup pass for client local storage. */
public final class ClientStorageMaintenanceScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 310;

	private final Screen parent;
	private final InstalledModpackController controller;
	private boolean busy;
	private boolean closed;
	private boolean presentingFailure;
	private Operation operation;
	private ClientObjectStore.CollectionResult collectionResult;
	private ClientObjectStore.StorageReport verificationReport;
	private Future<?> work;
	private int preservedCount;
	private int statusY;

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
		ActionRow maintenanceRow = actionRow(ActionAreaLayout.RowKind.AUXILIARY,
				optionalAction(VersionedText.translatable("automodpack.storage.verify"), button -> verify()),
				primaryAction(VersionedText.translatable("automodpack.storage.confirm"), button -> compact()));
		ActionRow footerRow = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), button -> closeToParent()));
		List<Button> buttons = addActionArea(PANEL_WIDTH, actionY, maintenanceRow, footerRow);
		buttons.get(0).active = !busy && !closed;
		buttons.get(1).active = !busy && !closed;

		// One pinned status line rides with the column, so the busy/complete feedback never moves.
		int wrapWidth = Math.max(1, panelWidth(PANEL_WIDTH) - 8);
		List<MutableComponent> lines = new ArrayList<>();
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.storage.description").getString(), wrapWidth, ChatFormatting.GRAY));
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.storage.removes").getString(), wrapWidth, ChatFormatting.YELLOW));
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.storage.keeps").getString(), wrapWidth, ChatFormatting.GREEN));
		lines.addAll(
				wrapParagraph(this.font, VersionedText.translatable(preservedCount > 0 ? "automodpack.storage.preservedKept" : "automodpack.vault.empty", preservedCount).getString(), wrapWidth, ChatFormatting.GREEN));
		for (ClientGenerationStore.CompactionReceipt receipt : controller.compactionReceipts())
			lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.storage.compacted", receipt.boundarySeq()).getString(), wrapWidth, ChatFormatting.GREEN));
		if (collectionResult != null) {
			lines.add(blankLine());
			lines.addAll(wrapParagraph(this.font, statLine("automodpack.storage.objects", collectionResult.before().objectCount(), collectionResult.after().objectCount(),
					UiFormat.formatSize(collectionResult.before().objectBytes()), UiFormat.formatSize(collectionResult.after().objectBytes())), wrapWidth));
		} else if (verificationReport != null) {
			lines.add(blankLine());
			lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.storage.verificationReceipt", verificationReport.validReferencedObjectCount(), verificationReport.referencedObjectCount(),
					UiFormat.formatSize(verificationReport.validReferencedObjectBytes())).getString(), wrapWidth));
		}
		DialogColumn column = layoutDialogColumn(32, actionAreaTop(PANEL_WIDTH, actionY, maintenanceRow, footerRow), lines.size() * LINE_HEIGHT, LINE_HEIGHT);
		statusY = column.stackTop();
		this.addCenteredScrollBody(PANEL_WIDTH, column.bodyTop(), column.bodyBottom(), lines);
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
				ClientGenerationStore.CompactionResult result = controller.compactStorage();
				this.minecraft.execute(() -> finish(result.collection()));
			} catch (Exception exception) {
				this.minecraft.execute(() -> fail(exception));
			}
		});
	}

	private void begin(Operation nextOperation) {
		busy = true;
		operation = nextOperation;
		collectionResult = null;
		verificationReport = null;
		rebuild();
	}

	private void finish(ClientObjectStore.CollectionResult collected) {
		if (closed) return;
		collectionResult = collected;
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
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.storage.title").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
		if (busy) {
			String message = operation == Operation.VERIFY ? "automodpack.storage.verifying" : "automodpack.storage.running";
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable(message).withStyle(ChatFormatting.YELLOW), this.width / 2, statusY, TextColors.WHITE);
		} else if (collectionResult != null) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.storage.complete").withStyle(ChatFormatting.GREEN), this.width / 2, statusY, TextColors.WHITE);
		} else if (verificationReport != null) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.storage.verified").withStyle(ChatFormatting.GREEN), this.width / 2, statusY, TextColors.WHITE);
		}
	}

	private String statLine(String labelKey, long countBefore, long countAfter, String sizeBefore, String sizeAfter) {
		return VersionedText.translatable(labelKey).getString() + ": " + countBefore + " -> " + countAfter + " (" + sizeBefore + " -> " + sizeAfter + ")";
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::closeToParent);
	}

	private enum Operation {
		VERIFY, COMPACT
	}
}
