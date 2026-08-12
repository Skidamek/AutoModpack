package pl.skidam.automodpack.client.ui;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.OfflineRepair;
import pl.skidam.automodpack_loader_core.client.ClientOfflineRepair;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Reviews one cache-bypassing offline integrity inspection before any files are changed. */
public final class OfflineRepairScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 420;
	private static final int ROW_HEIGHT = 22;

	private final Screen parent;
	private final String modpackName;
	private final ClientOfflineRepair repair;
	private final Runnable updateAction;
	private final Runnable closedCallback;
	private final Set<String> selectedEditablePaths = new TreeSet<>();
	private OfflineRepair.Prepared prepared;
	private OfflineRepair.Receipt receipt;
	private boolean archiveUnownedMods;
	private boolean busy;
	private boolean presentingFailure;
	private boolean closed;
	private int page;
	private Future<?> work;

	public OfflineRepairScreen(Screen parent, String modpackName, ClientOfflineRepair repair, OfflineRepair.Prepared prepared, Runnable updateAction, Runnable closedCallback) {
		super(VersionedText.translatable("automodpack.repair.title"));
		this.parent = parent;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.repair = repair;
		this.prepared = prepared;
		this.updateAction = updateAction;
		this.closedCallback = closedCallback;
		prepared.editableResetCandidates().forEach(candidate -> selectedEditablePaths.add(candidate.logicalPath()));
	}

	@Override
	protected void init() {
		super.init();
		int width = panelWidth(PANEL_WIDTH);
		int x = panelLeft(PANEL_WIDTH);
		int listTop = prepared.requiresUpdate() ? 94 : 82;
		if (!prepared.unownedModPaths().isEmpty()) {
			Button archive = buttonWidget(x, listTop, width, 20, VersionedText.translatable(archiveUnownedMods ? "automodpack.repair.archiveUnownedOn" : "automodpack.repair.archiveUnownedOff",
					prepared.unownedModPaths().size()), press -> toggleUnowned());
			archive.active = !busy;
			this.addRenderableWidget(archive);
			listTop += 28;
		}

		List<OfflineRepair.EditableResetCandidate> candidates = prepared.editableResetCandidates();
		int pageSize = rowsPerPage(listTop);
		int pageCount = pageCount(candidates.size(), pageSize);
		page = Math.max(0, Math.min(pageCount - 1, page));
		int start = page * pageSize;
		for (int index = start; index < Math.min(candidates.size(), start + pageSize); index++) {
			OfflineRepair.EditableResetCandidate candidate = candidates.get(index);
			boolean selected = selectedEditablePaths.contains(candidate.logicalPath());
			String label = VersionedText.translatable(selected ? "automodpack.repair.editableReset" : "automodpack.repair.editableKeep", candidate.logicalPath()).getString();
			Button choice = buttonWidget(x, listTop + (index - start) * ROW_HEIGHT, width, 20,
					VersionedText.literal(truncateToWidth(this.font, label, width - 12)), press -> toggleEditable(candidate.logicalPath()));
			choice.active = !busy;
			this.addRenderableWidget(choice);
		}

		int actionY = this.height - 28;
		boolean needsUpdate = prepared.requiresUpdate();
		boolean canUpdate = needsUpdate && updateAction != null;
		int primaryCount = canUpdate ? 2 : 1;
		int primaryY = actionY - 24;
		int primaryWidth = actionButtonWidth(PANEL_WIDTH, primaryCount);
		Button repairButton = buttonWidget(actionButtonX(PANEL_WIDTH, primaryCount, 0), primaryY, primaryWidth, 20,
				VersionedText.translatable(needsUpdate ? "automodpack.repair.available" : "automodpack.repair.apply"), press -> apply());
		repairButton.active = !busy && hasRepairWork();
		this.addRenderableWidget(repairButton);
		if (canUpdate) {
			Button update = buttonWidget(actionButtonX(PANEL_WIDTH, primaryCount, 1), primaryY, primaryWidth, 20,
					VersionedText.translatable("automodpack.repair.updateAndFinish"), press -> updateAndFinish());
			update.active = !busy;
			this.addRenderableWidget(update);
		}
		this.addRenderableWidget(buttonWidget(x, actionY, width, 20,
				VersionedText.translatable("automodpack.cancel"), press -> back()));

		if (pageCount > 1) {
			int navigationY = primaryY - 24;
			int navigationWidth = actionButtonWidth(PANEL_WIDTH, 3);
			Button previous = buttonWidget(actionButtonX(PANEL_WIDTH, 3, 0), navigationY, navigationWidth, 20, VersionedText.translatable("automodpack.ui.previous"), press -> changePage(-1));
			previous.active = !busy && page > 0;
			this.addRenderableWidget(previous);
			Button pageLabel = buttonWidget(actionButtonX(PANEL_WIDTH, 3, 1), navigationY, navigationWidth, 20, VersionedText.translatable("automodpack.ui.page", page + 1, pageCount), press -> {});
			pageLabel.active = false;
			this.addRenderableWidget(pageLabel);
			Button next = buttonWidget(actionButtonX(PANEL_WIDTH, 3, 2), navigationY, navigationWidth, 20, VersionedText.translatable("automodpack.ui.next"), press -> changePage(1));
			next.active = !busy && page + 1 < pageCount;
			this.addRenderableWidget(next);
		}

		if (!candidates.isEmpty() && !selectedEditablePaths.isEmpty()) {
			Button keepAll = buttonWidget(x, primaryY - (pageCount > 1 ? 48 : 24), width, 20, VersionedText.translatable("automodpack.repair.keepAllEditable"), press -> keepAllEditable());
			keepAll.active = !busy;
			this.addRenderableWidget(keepAll);
		}
	}

	private boolean hasRepairWork() {
		return prepared.findings().stream().anyMatch(OfflineRepair.Finding::locallyRepairable) || !selectedEditablePaths.isEmpty() || archiveUnownedMods && !prepared.unownedModPaths().isEmpty();
	}

	private int rowsPerPage(int listTop) {
		return Math.max(1, (this.height - listTop - 108) / ROW_HEIGHT);
	}

	private static int pageCount(int size, int pageSize) {
		return Math.max(1, (size + pageSize - 1) / pageSize);
	}

	private void toggleEditable(String path) {
		if (!selectedEditablePaths.remove(path)) selectedEditablePaths.add(path);
		rebuild();
	}

	private void keepAllEditable() {
		selectedEditablePaths.clear();
		rebuild();
	}

	private void toggleUnowned() {
		archiveUnownedMods = !archiveUnownedMods;
		rebuild();
	}

	private void changePage(int amount) {
		page = Math.max(0, page + amount);
		rebuild();
	}

	private void apply() {
		apply(false);
	}

	private void apply(boolean updateAfterRepair) {
		if (busy || closed || !hasRepairWork()) return;
		busy = true;
		rebuild();
		Set<String> editable = Set.copyOf(selectedEditablePaths);
		Set<String> unowned = archiveUnownedMods ? Set.copyOf(prepared.unownedModPaths()) : Set.of();
		work = DownloadClient.NET_EXECUTOR.submit(() -> {
			try {
				OfflineRepair.Receipt result = repair.apply(prepared, editable, unowned);
				this.minecraft.execute(() -> applied(result, updateAfterRepair));
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private void applied(OfflineRepair.Receipt result, boolean updateAfterRepair) {
		if (closed) return;
		if (updateAfterRepair) {
			closed = true;
			ScreenImpl.setScreen(parent);
			updateAction.run();
			return;
		}
		receipt = result;
		prepared = result.after();
		Set<String> remaining = new HashSet<>();
		prepared.editableResetCandidates().forEach(candidate -> remaining.add(candidate.logicalPath()));
		selectedEditablePaths.retainAll(remaining);
		archiveUnownedMods = false;
		busy = false;
		page = 0;
		rebuild();
	}

	private void updateAndFinish() {
		if (busy || closed || updateAction == null) return;
		if (hasRepairWork()) apply(true);
		else {
			closed = true;
			ScreenImpl.setScreen(parent);
			updateAction.run();
		}
	}

	private void back() {
		if (closed) return;
		closed = true;
		cancelWork();
		closedCallback.run();
		ScreenImpl.setScreen(parent);
	}

	private void fail(Exception exception) {
		if (closed) return;
		busy = false;
		rebuild();
		presentingFailure = true;
		new ScreenManager().failure(FailureRequest.of(exception, "automodpack.error.repair", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
	}

	private void cancelWork() {
		Future<?> current = work;
		if (current != null && !current.isDone()) current.cancel(true);
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
			closedCallback.run();
		}
		super.removed();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String title = VersionedText.translatable("automodpack.repair.titleNamed", modpackName).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
		long missing = prepared.findings().stream().filter(finding -> finding.condition() == OfflineRepair.Condition.MISSING).count();
		long damaged = prepared.findings().stream().filter(finding -> finding.condition() != OfflineRepair.Condition.MISSING).count();
		long repairable = prepared.findings().stream().filter(OfflineRepair.Finding::locallyRepairable).count();
		String state = prepared.findings().isEmpty() ? VersionedText.translatable("automodpack.repair.healthy").getString()
				: VersionedText.translatable("automodpack.repair.findings", missing, damaged, repairable).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, state, this.width - 20)).withStyle(prepared.findings().isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), this.width / 2, 30, TextColors.WHITE);
		String hashed = VersionedText.translatable("automodpack.repair.hashed", prepared.directlyHashedFileCount(), UiFormat.formatSize(prepared.directlyHashedBytes())).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, hashed, this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, 42, TextColors.WHITE);
		String choices = VersionedText.translatable("automodpack.repair.choices", selectedEditablePaths.size(), prepared.editableResetCandidates().size(), prepared.unownedModPaths().size()).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, choices, this.width - 20)).withStyle(ChatFormatting.AQUA), this.width / 2, 54, TextColors.WHITE);
		if (busy) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.repair.working").withStyle(ChatFormatting.YELLOW), this.width / 2, 66, TextColors.WHITE);
		else if (receipt != null) {
			String result = VersionedText.translatable("automodpack.repair.receipt", receipt.repairedCasObjects(), receipt.repairedMaterializedFiles(), receipt.resetEditableFiles(), receipt.archivedUnownedMods()).getString();
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, result, this.width - 20)).withStyle(receipt.complete() ? ChatFormatting.GREEN : ChatFormatting.YELLOW), this.width / 2, 66, TextColors.WHITE);
		} else if (prepared.requiresUpdate()) {
			String updateMessage = VersionedText.translatable(updateAction == null ? "automodpack.repair.updateNeededOffline" : "automodpack.repair.updateNeeded").getString();
			List<String> lines = wrapToWidth(this.font, updateMessage, this.width - 28, 2);
			for (int index = 0; index < lines.size(); index++)
				drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(lines.get(index)).withStyle(ChatFormatting.YELLOW), this.width / 2, 66 + index * 12, TextColors.WHITE);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		back();
		return false;
	}
}
