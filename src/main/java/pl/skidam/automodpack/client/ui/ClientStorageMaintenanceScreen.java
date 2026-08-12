package pl.skidam.automodpack.client.ui;

import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.cache.ClientObjectStore;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Provides an explicit, user-confirmed cleanup pass for client generation storage. */
public final class ClientStorageMaintenanceScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 310;

	private final Screen parent;
	private final ClientStorage storage;
	private boolean busy;
	private boolean closed;
	private Operation operation;
	private ClientGenerationStore.CompactionResult compactionResult;
	private ClientObjectStore.StorageReport verificationReport;
	private Future<?> work;

	public ClientStorageMaintenanceScreen(Screen parent, ClientStorage storage) {
		super(VersionedText.translatable("automodpack.storage.title"));
		this.parent = parent;
		this.storage = storage;
	}

	@Override
	protected void init() {
		super.init();
		int actionWidth = actionButtonWidth(PANEL_WIDTH, 2);
		int actionY = this.height - 54;
		String verifyLabel = busy && operation == Operation.VERIFY ? "automodpack.storage.verifyingButton" : "automodpack.storage.verify";
		Button verify = buttonWidget(actionButtonX(PANEL_WIDTH, 2, 0), actionY, actionWidth, 20,
				VersionedText.translatable(verifyLabel), button -> verify());
		verify.active = !busy && !closed;
		this.addRenderableWidget(verify);
		String actionLabel = busy && operation == Operation.COMPACT ? "automodpack.storage.runningButton" : "automodpack.storage.confirm";
		Button maintenance = buttonWidget(actionButtonX(PANEL_WIDTH, 2, 1), actionY, actionWidth, 20,
				VersionedText.translatable(actionLabel), button -> compact());
		maintenance.active = !busy && !closed;
		this.addRenderableWidget(maintenance);
		this.addRenderableWidget(buttonWidget(centeredActionButtonX(PANEL_WIDTH, 1, 1, 0), this.height - 28, actionButtonWidth(PANEL_WIDTH, 1), 20,
				VersionedText.translatable("automodpack.back"), button -> closeToParent()));
	}

	private void verify() {
		if (busy || closed) return;
		begin(Operation.VERIFY);
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				ClientObjectStore.StorageReport report = ClientObjectStore.validate(storage);
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
				ClientGenerationStore.CompactionResult compacted = new ClientGenerationStore(storage).compact();
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
		new ScreenManager().failure(FailureRequest.of(exception, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
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
		if (!closed) {
			closed = true;
			cancelWork();
		}
		super.removed();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		int textWidth = Math.max(1, this.width - 20);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.storage.title").withStyle(ChatFormatting.BOLD), this.width / 2, 14, TextColors.WHITE);
		int y = 32;
		y = drawWrapped(matrices, VersionedText.translatable("automodpack.storage.description").getString(), y, textWidth, TextColors.LIGHT_GRAY);
		y = drawWrapped(matrices, VersionedText.translatable("automodpack.storage.removes").getString(), y + 4, textWidth, TextColors.YELLOW);
		y = drawWrapped(matrices, VersionedText.translatable("automodpack.storage.keeps").getString(), y + 4, textWidth, TextColors.GREEN);
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

	private void drawStats(VersionedMatrices matrices, ClientGenerationStore.CompactionResult compacted, int y, int textWidth) {
		String records = VersionedText.translatable("automodpack.storage.records", compacted.generationRecordCountBefore(), compacted.generationRecordCountAfter(), UiFormat.formatSize(compacted.generationRecordBytesBefore()), UiFormat.formatSize(compacted.generationRecordBytesAfter())).getString();
		String objects = VersionedText.translatable("automodpack.storage.objects", compacted.objectCollection().before().objectCount(), compacted.objectCollection().after().objectCount(), UiFormat.formatSize(compacted.objectCollection().before().objectBytes()), UiFormat.formatSize(compacted.objectCollection().after().objectBytes())).getString();
		String generatedCopies = VersionedText.translatable("automodpack.storage.generatedCopies", compacted.generatedCopyCountBefore(), compacted.generatedCopyCountAfter(), UiFormat.formatSize(compacted.generatedCopyBytesBefore()), UiFormat.formatSize(compacted.generatedCopyBytesAfter())).getString();
		y = drawWrapped(matrices, records, y, textWidth, TextColors.WHITE);
		y = drawWrapped(matrices, objects, y, textWidth, TextColors.WHITE);
		drawWrapped(matrices, generatedCopies, y, textWidth, TextColors.GRAY);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		closeToParent();
		return false;
	}

	private enum Operation { VERIFY, COMPACT }
}
