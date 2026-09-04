package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
/*? if > 1.19.2 {*/
import net.minecraft.client.gui.components.Tooltip;
/*?}*/

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.RowListWidget;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.OfflineRepair;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.client.ClientOfflineRepair;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Reviews one cache-bypassing offline integrity inspection before any files are changed. */
public final class OfflineRepairScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 500;
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
			String files = String.join("\n", wrapToWidth(this.font, String.join(", ", prepared.unownedModPaths()), 240, 8));
			AbstractWidget keep = checkboxWidget(this.font, x, listTop, width, ActionAreaLayout.BUTTON_HEIGHT, VersionedText.translatable("automodpack.confirm.keepExistingMods", prepared.unownedModPaths().size()), keepUnownedMods, value -> {
				keepUnownedMods = value;
				rebuild();
			});
			keep.active = !busy;
			/*? if > 1.19.2 {*/
			keep.setTooltip(Tooltip.create(VersionedText.translatable("automodpack.confirm.leftoverTooltip", files)));
			/*?}*/
			this.addRenderableWidget(keep);
			listTop += 28;
		}

		List<OfflineRepair.EditableResetCandidate> candidates = prepared.editableResetCandidates();
		boolean needsUpdate = prepared.requiresUpdate();
		boolean canUpdate = needsUpdate && updateAction != null;
		List<ActionRow> actions = new ArrayList<>();
		boolean showKeepAll = !candidates.isEmpty() && !selectedEditablePaths.isEmpty();
		if (showKeepAll)
			actions.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY,
					optionalAction(VersionedText.translatable("automodpack.repair.keepAllEditable"), press -> keepAllEditable())));
		List<ActionDefinition> primaryActions = new ArrayList<>();
		primaryActions.add(primaryAction(VersionedText.translatable("automodpack.repair.apply"), press -> apply()));
		if (canUpdate) primaryActions.add(optionalAction(VersionedText.translatable("automodpack.repair.updateAndFinish"), press -> updateAndFinish()));
		actions.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, primaryActions.toArray(ActionDefinition[]::new)));
		actions.add(actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), press -> back())));
		ActionRow[] actionRows = actions.toArray(ActionRow[]::new);
		List<Button> actionButtons = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows);
		int actionIndex = 0;
		if (showKeepAll) actionButtons.get(actionIndex++).active = !busy;
		actionButtons.get(actionIndex++).active = !busy && hasRepairWork();
		if (canUpdate) actionButtons.get(actionIndex).active = !busy;
		if (candidates.isEmpty()) return;
		List<RowListWidget.Row> listRows = new ArrayList<>(candidates.size());
		for (OfflineRepair.EditableResetCandidate candidate : candidates) {
			// Membership in selectedEditablePaths is the reset consent, so the row renders unchecked
			// while it consents and checked once the player's changes are kept.
			boolean resetConsent = selectedEditablePaths.contains(candidate.logicalPath());
			listRows.add(new RowListWidget.Row(List.of(VersionedText.literal(truncateToWidth(this.font,
					VersionedText.translatable(resetConsent ? "automodpack.repair.editableKeep" : "automodpack.repair.editableKeepChecked", candidate.logicalPath()).getString(), width - 12))),
					editableTooltip(resetConsent, candidate.logicalPath())));
		}
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRows) - 8;
		this.addRenderableWidget(new RowListWidget(this.minecraft, this.width, this.height, panelWidth(PANEL_WIDTH), listTop, listBottom, ROW_HEIGHT, listRows,
				index -> {
					if (!busy) toggleEditable(candidates.get(index).logicalPath());
				}, this::showComponentTooltip));
	}

	private void toggleEditable(String path) {
		if (!selectedEditablePaths.remove(path)) selectedEditablePaths.add(path);
		rebuild();
	}

	private void keepAllEditable() {
		selectedEditablePaths.clear();
		rebuild();
	}

	/** Same help for an editable row: unchecked consents to reset, checked keeps the player's changes. */
	private Component editableTooltip(boolean resetConsent, String path) {
		return VersionedText.translatable(resetConsent ? "automodpack.repair.editableTooltipReset" : "automodpack.repair.editableTooltipKeep", path);
	}

	private boolean hasRepairWork() {
		return prepared.findings().stream().anyMatch(OfflineRepair.Finding::locallyRepairable) || !selectedEditablePaths.isEmpty() || !keepUnownedMods && !prepared.unownedModPaths().isEmpty();
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
		String state = prepared.findings().isEmpty()
				? VersionedText.translatable("automodpack.repair.healthy").getString()
				: VersionedText.translatable("automodpack.repair.findings", missing, damaged, repairable).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, state, this.width - 20)).withStyle(prepared.findings().isEmpty() ? ChatFormatting.GREEN : ChatFormatting.YELLOW),
				this.width / 2, 30, TextColors.WHITE);
		String hashed = VersionedText.translatable("automodpack.repair.hashed", prepared.directlyHashedFileCount(), UiFormat.formatSize(prepared.directlyHashedBytes())).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, hashed, this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, 42, TextColors.WHITE);
		if (!prepared.unownedModPaths().isEmpty()) {
			String unownedState = VersionedText.translatable(keepUnownedMods ? "automodpack.repair.unownedKept" : "automodpack.repair.unownedArchived", prepared.unownedModPaths().size()).getString();
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, unownedState, this.width - 20)).withStyle(keepUnownedMods ? ChatFormatting.YELLOW : ChatFormatting.GRAY),
					this.width / 2, 54, TextColors.WHITE);
		} else {
			String choices = VersionedText.translatable("automodpack.repair.choices", selectedEditablePaths.size(), prepared.editableResetCandidates().size()).getString();
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, choices, this.width - 20)).withStyle(ChatFormatting.AQUA), this.width / 2, 54, TextColors.WHITE);
		}
		if (busy) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.repair.working").withStyle(ChatFormatting.YELLOW), this.width / 2, 66, TextColors.WHITE);
		else if (receipt != null) {
			String result = VersionedText.translatable("automodpack.repair.receipt", receipt.repairedCasObjects(), receipt.repairedMaterializedFiles(), receipt.resetEditableFiles(), receipt.archivedUnownedMods())
					.getString();
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, result, this.width - 20)).withStyle(receipt.complete() ? ChatFormatting.GREEN : ChatFormatting.YELLOW),
					this.width / 2, 66, TextColors.WHITE);
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
