package pl.skidam.automodpack.client.ui.screen;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.util.Util;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/** A single, calm entry point for all actions on one installed modpack. */
public final class ModpackDetailsScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = ActionAreaLayout.FOOTER_RAIL;
	// Busy stays invisible for this long first, so a fast check never flashes the disabled state.
	private static final long BUSY_VISIBLE_MILLIS = 500L;

	private final Screen parent;
	private final InstalledModpackController controller;
	private final InstalledModpackController.Pack pack;
	private final List<Button> actionButtons = new ArrayList<>();
	private boolean busy;
	private boolean busyVisible;
	private long busyAt;
	private boolean upToDate;

	public ModpackDetailsScreen(Screen parent, InstalledModpackController controller, InstalledModpackController.Pack pack) {
		super(VersionedText.translatable("automodpack.packDetails.title"));
		this.parent = parent;
		this.controller = controller;
		this.pack = pack;
	}

	@Override
	protected void init() {
		super.init();
		actionButtons.clear();
		List<Action> actions = new ArrayList<>();
		actions.add(new Action(pack.active() ? "automodpack.management.update" : "automodpack.management.activate", this::primaryAction));
		if (pack.active()) actions.add(new Action("automodpack.management.repair", this::repair));
		actions.add(new Action("automodpack.selection.button", this::openFeatures));
		if (controller.hasHistory(pack)) actions.add(new Action("automodpack.management.history", this::openHistory));
		actions.add(new Action("automodpack.management.packFiles", this::openFiles));
		if (pack.active()) actions.add(new Action("automodpack.management.deactivate", this::deactivate, VersionedText.translatable("automodpack.management.deactivateTooltip")));
		actions.add(new Action("automodpack.management.remove", this::remove, VersionedText.translatable("automodpack.management.removeTooltip")));
		int columns = actionColumns(actions.size());
		List<ActionRow> rows = new ArrayList<>();
		for (int index = 0; index < actions.size(); index += columns) {
			int end = Math.min(actions.size(), index + columns);
			List<ActionDefinition> rowActions = new ArrayList<>(end - index);
			for (int rowIndex = index; rowIndex < end; rowIndex++) {
				Action action = actions.get(rowIndex);
				Component message = action.label() != null ? action.label() : VersionedText.translatable(action.labelKey());
				rowActions.add(rowIndex == 0 ? primaryAction(message, button -> action.action().run()) : optionalAction(message, button -> action.action().run()));
			}
			rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, rowActions.toArray(ActionDefinition[]::new)));
		}
		for (Button button : addActionAreaAt(PANEL_WIDTH, actionGridTop(), rows.toArray(ActionRow[]::new))) actionButtons.add(button);
		// Destructive verbs say what they do before the player commits: Deactivate keeps files, Remove deletes them.
		for (int index = 0; index < actions.size() && index < actionButtons.size(); index++) {
			Component tooltip = actions.get(index).tooltip();
			if (tooltip != null) setTooltip(actionButtons.get(index), tooltip);
		}
		this.addActionArea(PANEL_WIDTH, this.height - 28, actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), button -> ScreenImpl.setScreen(parent))));
		updateActions();
	}

	@Override
	public void tick() {
		super.tick();
		if (!busy || busyVisible || Util.getMillis() - busyAt < BUSY_VISIBLE_MILLIS) return;
		busyVisible = true;
		updateActions();
	}

	private record Action(String labelKey, Runnable action, Component tooltip, Component label) {
		Action(String labelKey, Runnable action) {
			this(labelKey, action, null, null);
		}

		Action(String labelKey, Runnable action, Component tooltip) {
			this(labelKey, action, tooltip, null);
		}
	}

	private void markBusy() {
		busy = true;
		busyAt = Util.getMillis();
	}

	private void primaryAction() {
		if (busy) return;
		markBusy();
		if (pack.active()) controller.update(pack, this::updateCompleted);
		else controller.activate(pack, this::released);
	}

	private void updateCompleted(boolean current) {
		upToDate = current;
		released();
		if (current) rebuild();
	}

	private void repair() {
		if (busy || !pack.active()) return;
		markBusy();
		controller.repair(this, pack, this::updateCompleted);
	}

	private void openFeatures() {
		if (busy) return;
		ScreenImpl.setScreen(ModpackSelectionScreen.forInstalledRecord(this, pack.record(), false, false));
	}

	private void openHistory() {
		if (busy) return;
		markBusy();
		controller.openHistory(pack, this::released);
	}

	private void openFiles() {
		if (busy) return;
		controller.openFiles(this, pack);
	}

	private void remove() {
		if (busy) return;
		markBusy();
		controller.remove(pack, this::released, this::returnToList);
	}

	private void deactivate() {
		if (busy) return;
		markBusy();
		controller.deactivate(pack, this::released, this::reopenOrList);
	}

	private void returnToList() {
		ScreenImpl.setScreen(parent instanceof InstalledModpacksScreen list ? list : parent);
	}

	private void reopenOrList() {
		InstalledModpackController.Pack next = controller.installedPack(pack.modpackId());
		ScreenImpl.setScreen(next == null ? parent : new ModpackDetailsScreen(parent, controller, next));
	}

	private void released() {
		busy = false;
		busyVisible = false;
		updateActions();
	}

	private void updateActions() {
		for (int index = 0; index < actionButtons.size(); index++) {
			boolean primary = index == 0;
			actionButtons.get(index).active = !busyVisible && (!primary || !pack.active() || pack.connectionAvailable() && !upToDate);
		}
		if (actionButtons.isEmpty() || !pack.active()) return;
		actionButtons.get(0).setMessage(VersionedText.translatable(upToDate ? "automodpack.management.upToDate" : "automodpack.management.update"));
	}

	private int actionGridTop() {
		int y = 28;
		y += 16;
		y += 14;
		y += 14;
		y += 12;
		y += 12;
		return y + ActionAreaLayout.GAP;
	}

	/** Picks the widest column count whose grid stays clear of the footer rail; 3 columns still keeps every button at or above the 88px minimum width. */
	private int actionColumns(int actionCount) {
		int footerTop = actionAreaTop(PANEL_WIDTH, this.height - 28, actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), button -> {})));
		int gridTop = actionGridTop();
		for (int columns = 2; columns <= 3; columns++) {
			int rows = (actionCount + columns - 1) / columns;
			int bottom = gridTop + rows * ActionAreaLayout.BUTTON_HEIGHT + (rows - 1) * ActionAreaLayout.GAP;
			if (bottom <= footerTop - ActionAreaLayout.GAP) return columns;
		}
		return 3;
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
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		int width = panelWidth(PANEL_WIDTH);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(pack.name()).withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
		int y = 28;
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.packDetails.description").withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
		y += 16;
		String state = pack.active() ? VersionedText.translatable("automodpack.packManager.active", pack.name()).getString() : VersionedText.translatable("automodpack.packManager.noActive").getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, state, width)).withStyle(pack.active() ? ChatFormatting.GREEN : ChatFormatting.GRAY), this.width / 2, y,
				TextColors.WHITE);
		y += 14;
		String connection = VersionedText.translatable(pack.connectionAvailable() ? "automodpack.packDetails.connected" : "automodpack.packDetails.disconnected").getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, connection, width)).withStyle(pack.connectionAvailable() ? ChatFormatting.AQUA : ChatFormatting.GRAY),
				this.width / 2, y,
				TextColors.WHITE);
		y += 14;
		String version = VersionedText.translatable("automodpack.packDetails.identity", pack.record().manifest().loader(), pack.record().manifest().loaderVersion(), pack.record().manifest().mcVersion()).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, version, width)).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
		y += 12;
		String contents = VersionedText.translatable("automodpack.packDetails.contents", UiFormat.plural(pack.groupCount(), "automodpack.confirm.groupCount").getString(), UiFormat.plural(pack.fileCount(), "automodpack.confirm.fileCount").getString(), UiFormat.formatSize(pack.fileBytes())).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, contents, width)).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
		if (busyVisible) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.packDetails.working").withStyle(ChatFormatting.YELLOW), this.width / 2, this.height - 44, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
