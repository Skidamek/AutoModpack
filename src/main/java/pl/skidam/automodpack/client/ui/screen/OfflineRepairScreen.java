package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
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
	// Checkbox convention: [x] checked keeps the files in place; unchecked is the removal consent
	// (copies are preserved in the vault, then removed). Same meaning on every screen.
	private boolean keepUnownedMods;
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
			Button archive = buttonWidget(x, listTop, width, 20, VersionedText.translatable(keepUnownedMods ? "automodpack.repair.keepUnownedChecked" : "automodpack.repair.keepUnowned",
					prepared.unownedModPaths().size()), press -> toggleUnowned());
			archive.active = !busy;
			setTooltip(archive, unownedTooltip());
			this.addRenderableWidget(archive);
			listTop += 28;
		}

		List<OfflineRepair.EditableResetCandidate> candidates = prepared.editableResetCandidates();
		boolean needsUpdate = prepared.requiresUpdate();
		boolean canUpdate = needsUpdate && updateAction != null;
		List<ActionRow> actions = new ArrayList<>();
		boolean showKeepAll = !candidates.isEmpty() && !selectedEditablePaths.isEmpty();
		if (showKeepAll) actions.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY,
				optionalAction(VersionedText.translatable("automodpack.repair.keepAllEditable"), press -> keepAllEditable())));
		List<ActionDefinition> primaryActions = new ArrayList<>();
		primaryActions.add(primaryAction(VersionedText.translatable("automodpack.repair.apply"), press -> apply()));
		if (canUpdate) primaryActions.add(optionalAction(VersionedText.translatable("automodpack.repair.updateAndFinish"), press -> updateAndFinish()));
		actions.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, primaryActions.toArray(ActionDefinition[]::new)));
		actions.add(actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.cancel"), press -> back())));
		int pageSize = rowsPerPage(listTop, actionAreaTop(PANEL_WIDTH, this.height - 28, actions.toArray(ActionRow[]::new)));
		int pageCount = pageCount(candidates.size(), pageSize);
		if (pageCount > 1) {
			int navigationIndex = showKeepAll ? 1 : 0;
			actions.add(navigationIndex, navigationRow(pageCount));
			pageSize = rowsPerPage(listTop, actionAreaTop(PANEL_WIDTH, this.height - 28, actions.toArray(ActionRow[]::new)));
			pageCount = pageCount(candidates.size(), pageSize);
		}
		page = Math.max(0, Math.min(pageCount - 1, page));
		if (pageCount > 1) {
			int navigationIndex = showKeepAll ? 1 : 0;
			actions.set(navigationIndex, navigationRow(pageCount));
		}
		int start = page * pageSize;
		for (int index = start; index < Math.min(candidates.size(), start + pageSize); index++) {
			OfflineRepair.EditableResetCandidate candidate = candidates.get(index);
			// Membership in selectedEditablePaths is the reset consent, so the row renders unchecked
			// while it consents and checked once the player's changes are kept.
			boolean resetConsent = selectedEditablePaths.contains(candidate.logicalPath());
			Button choice = buttonWidget(x, listTop + (index - start) * ROW_HEIGHT, width, 20,
					VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable(resetConsent ? "automodpack.repair.editableKeep" : "automodpack.repair.editableKeepChecked", candidate.logicalPath()).getString(), width - 12)), press -> toggleEditable(candidate.logicalPath()));
			choice.active = !busy;
			setTooltip(choice, editableTooltip(resetConsent, candidate.logicalPath()));
			this.addRenderableWidget(choice);
		}
		List<Button> actionButtons = addActionArea(PANEL_WIDTH, this.height - 28, actions.toArray(ActionRow[]::new));
		int actionIndex = 0;
		if (showKeepAll) actionButtons.get(actionIndex++).active = !busy;
		if (pageCount > 1) {
			actionButtons.get(actionIndex++).active = !busy && page > 0;
			actionIndex++;
			actionButtons.get(actionIndex++).active = !busy && page + 1 < pageCount;
		}
		actionButtons.get(actionIndex++).active = !busy && hasRepairWork();
		if (canUpdate) actionButtons.get(actionIndex).active = !busy;
	}

	private boolean hasRepairWork() {
		return prepared.findings().stream().anyMatch(OfflineRepair.Finding::locallyRepairable) || !selectedEditablePaths.isEmpty() || !keepUnownedMods && !prepared.unownedModPaths().isEmpty();
	}

	private int rowsPerPage(int listTop, int actionTop) {
		return Math.max(1, (actionTop - listTop - actionRowGap()) / ROW_HEIGHT);
	}

	private ActionRow navigationRow(int pageCount) {
		return actionRow(ActionAreaLayout.RowKind.NAVIGATION,
				navigationAction(VersionedText.translatable("automodpack.ui.previous"), press -> changePage(-1)),
				disabledNavigationAction(VersionedText.translatable("automodpack.ui.page", page + 1, pageCount)),
				navigationAction(VersionedText.translatable("automodpack.ui.next"), press -> changePage(1)));
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
		keepUnownedMods = !keepUnownedMods;
		rebuild();
	}

	/** Names the exact files the unowned-mods choice applies to, plus what each state does with them. */
	private Component unownedTooltip() {
		String files = String.join("\n", wrapToWidth(this.font, String.join(", ", prepared.unownedModPaths()), 240, 8));
		return VersionedText.translatable(keepUnownedMods ? "automodpack.repair.unownedTooltipKeep" : "automodpack.repair.unownedTooltipRemove", files);
	}

	/** Same help for an editable row: unchecked consents to reset, checked keeps the player's changes. */
	private Component editableTooltip(boolean resetConsent, String path) {
		return VersionedText.translatable(resetConsent ? "automodpack.repair.editableTooltipReset" : "automodpack.repair.editableTooltipKeep", path);
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
		Set<String> unowned = keepUnownedMods ? Set.of() : Set.copyOf(prepared.unownedModPaths());
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
		keepUnownedMods = false;
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
		ScreenManager.failure(FailureRequest.of(exception, "automodpack.error.repair", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
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
		if (!prepared.unownedModPaths().isEmpty()) {
			String unownedState = VersionedText.translatable(keepUnownedMods ? "automodpack.repair.unownedKept" : "automodpack.repair.unownedArchived", prepared.unownedModPaths().size()).getString();
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, unownedState, this.width - 20)).withStyle(keepUnownedMods ? ChatFormatting.YELLOW : ChatFormatting.GRAY), this.width / 2, 54, TextColors.WHITE);
		} else {
			String choices = VersionedText.translatable("automodpack.repair.choices", selectedEditablePaths.size(), prepared.editableResetCandidates().size()).getString();
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, choices, this.width - 20)).withStyle(ChatFormatting.AQUA), this.width / 2, 54, TextColors.WHITE);
		}
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
