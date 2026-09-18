package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/** Provides an explicit, user-confirmed cleanup pass for client local storage. */
public final class ClientStorageMaintenanceScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 310;

	private final Screen parent;
	private final InstalledModpackController controller;
	private boolean busy;
	private boolean closed;
	private boolean compactArmed;
	private boolean presentingFailure;
	private Operation operation;
	private ClientObjectStore.CollectionResult collectionResult;
	private ClientObjectStore.StorageReport verificationReport;
	private Future<?> work;
	private int preservedCount;
	private int statusY;
	private int titleTop;

	public ClientStorageMaintenanceScreen(Screen parent, InstalledModpackController controller) {
		super(VersionedText.text("automodpack.storage.title"));
		this.parent = parent;
		this.controller = controller;
	}

	@Override
	protected void init() {
		super.init();
		preservedCount = controller.preservedClaimCount();
		ActionRow maintenanceRow = actionRow(ActionAreaLayout.RowKind.AUXILIARY,
				optionalAction(VersionedText.text("automodpack.storage.verify"), button -> verify()),
				primaryAction(VersionedText.text(compactArmed ? "automodpack.storage.confirmArmed" : "automodpack.storage.confirm"), button -> compactPressed()));
		ActionRow footerRow = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.back"), button -> closeToParent()));

		// One pinned status line rides with the column, so the busy/complete feedback never moves.
		int wrapWidth = Math.max(1, panelWidth(PANEL_WIDTH) - 8);
		List<MutableComponent> lines = new ArrayList<>();
		lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.storage.description"), wrapWidth, ChatFormatting.GRAY));
		lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.storage.removes"), wrapWidth, ChatFormatting.YELLOW));
		lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.storage.keeps"), wrapWidth, ChatFormatting.GREEN));
		lines.addAll(
				wrapParagraph(this.font, VersionedText.str(preservedCount > 0 ? "automodpack.storage.preservedKept" : "automodpack.vault.empty", preservedCount), wrapWidth, ChatFormatting.GREEN));
		for (ClientGenerationStore.CompactionReceipt receipt : controller.compactionReceipts())
			lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.storage.compacted", UiFormat.formatInstant(receipt.compactedAt()), receipt.boundarySeq()), wrapWidth,
					ChatFormatting.GREEN));
		if (collectionResult != null) {
			lines.add(blankLine());
			lines.addAll(wrapParagraph(this.font, statLine("automodpack.storage.objects", collectionResult.before().objectCount(), collectionResult.after().objectCount(),
					UiFormat.formatSize(collectionResult.before().objectBytes()), UiFormat.formatSize(collectionResult.after().objectBytes())), wrapWidth));
		} else if (verificationReport != null) {
			lines.add(blankLine());
			lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.storage.verificationReceipt", verificationReport.validReferencedObjectCount(), verificationReport.referencedObjectCount(),
					UiFormat.formatSize(verificationReport.validReferencedObjectBytes())), wrapWidth));
		}
		DialogLayout layout = layoutDialogWithActions(28, LINE_HEIGHT, lines.size() * LINE_HEIGHT, LINE_HEIGHT, maintenanceRow, footerRow);
		this.titleTop = layout.titleTop();
		List<AbstractWidget> buttons = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, maintenanceRow, footerRow);
		buttons.get(0).active = !busy && !closed;
		buttons.get(1).active = !busy && !closed;
		DialogColumn column = layout.column();
		statusY = column.stackTop();
		this.addCenteredScrollBody(PANEL_WIDTH, column.bodyTop(), column.bodyBottom(), lines);
	}

	private void verify() {
		if (busy || closed) return;
		begin(Operation.VERIFY);
		work = ScreenManager.background(() -> {
			try {
				ClientObjectStore.StorageReport report = controller.validateStorage();
				this.minecraft.execute(() -> finishVerification(report));
			} catch (Exception exception) {
				this.minecraft.execute(() -> fail(exception));
			}
		});
	}

	/** The destructive command fires on the second press only; any state change disarms it again. */
	private void compactPressed() {
		if (busy || closed) return;
		if (!compactArmed) {
			compactArmed = true;
			rebuild();
			return;
		}
		compact();
	}

	private void compact() {
		if (busy || closed) return;
		begin(Operation.COMPACT);
		work = ScreenManager.background(() -> {
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
		compactArmed = false;
		rebuild();
	}

	private void finish(ClientObjectStore.CollectionResult collected) {
		if (closed) return;
		collectionResult = collected;
		operation = null;
		busy = false;
		compactArmed = false;
		rebuild();
	}

	private void finishVerification(ClientObjectStore.StorageReport report) {
		if (closed) return;
		verificationReport = report;
		operation = null;
		busy = false;
		compactArmed = false;
		rebuild();
	}

	private void fail(Exception exception) {
		if (closed) return;
		busy = false;
		operation = null;
		compactArmed = false;
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
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.storage.title").withStyle(ChatFormatting.BOLD), this.width / 2, titleTop, TextColors.WHITE);
		if (busy) {
			String message = operation == Operation.VERIFY ? "automodpack.storage.verifying" : "automodpack.storage.running";
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.text(message).withStyle(ChatFormatting.YELLOW), this.width / 2, statusY, TextColors.WHITE);
		} else if (collectionResult != null) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.storage.complete").withStyle(ChatFormatting.GREEN), this.width / 2, statusY, TextColors.WHITE);
		} else if (verificationReport != null) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.storage.verified").withStyle(ChatFormatting.GREEN), this.width / 2, statusY, TextColors.WHITE);
		}
	}

	private String statLine(String labelKey, long countBefore, long countAfter, String sizeBefore, String sizeAfter) {
		return VersionedText.str(labelKey) + ": " + countBefore + " -> " + countAfter + " (" + sizeBefore + " -> " + sizeAfter + ")";
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::closeToParent);
	}

	private enum Operation {
		VERIFY, COMPACT
	}
}
