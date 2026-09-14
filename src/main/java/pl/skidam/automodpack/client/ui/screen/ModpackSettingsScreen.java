package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/** A single, calm entry point for all actions on one installed modpack. */
public final class ModpackSettingsScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = ActionAreaLayout.FOOTER_RAIL;
	// Busy stays invisible for this long first, so a fast check never flashes the disabled state.
	private static final long BUSY_VISIBLE_MILLIS = 500L;

	private final Screen parent;
	private final InstalledModpackController controller;
	private final InstalledModpackController.Pack pack;
	private final List<AbstractWidget> actionButtons = new ArrayList<>();
	private boolean busy;
	private boolean busyVisible;
	private long busyAt;
	private boolean upToDate;

	public ModpackSettingsScreen(Screen parent, InstalledModpackController controller, InstalledModpackController.Pack pack) {
		super(VersionedText.text("automodpack.packDetails.title"));
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
		// One action whose label is the sync state itself: stop syncing declares local sovereignty, resume syncing is the explicit attach.
		if (pack.active())
			actions.add(pack.detached()
					? new Action("automodpack.management.resumeSyncing", this::resumeSyncing, VersionedText.text("automodpack.management.resumeSyncingTooltip"))
					: new Action("automodpack.management.stopSyncing", this::stopSyncing, VersionedText.text("automodpack.management.stopSyncingTooltip")));
		actions.add(new Action("automodpack.management.groups", this::openGroups));
		actions.add(new Action("automodpack.management.packFiles", this::openFiles));
		actions.add(new Action("automodpack.management.history", this::openHistory));
		if (pack.active()) actions.add(new Action("automodpack.management.deactivate", this::deactivate, VersionedText.text("automodpack.management.deactivateTooltip")));
		actions.add(new Action("automodpack.management.remove", this::remove, VersionedText.text("automodpack.management.removeTooltip")));
		int columns = actionColumns(actions.size());
		List<ActionRow> rows = new ArrayList<>();
		for (int index = 0; index < actions.size(); index += columns) {
			int end = Math.min(actions.size(), index + columns);
			List<ActionDefinition> rowActions = new ArrayList<>(end - index);
			for (int rowIndex = index; rowIndex < end; rowIndex++) {
				Action action = actions.get(rowIndex);
				Component message = action.label() != null ? action.label() : VersionedText.text(action.labelKey());
				rowActions.add(rowIndex == 0 ? primaryAction(message, button -> action.action().run()) : optionalAction(message, button -> action.action().run()));
			}
			rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, rowActions.toArray(ActionDefinition[]::new)));
		}
		for (AbstractWidget button : addActionAreaAt(PANEL_WIDTH, actionGridTop(), rows.toArray(ActionRow[]::new))) actionButtons.add(button);
		// Back sits on the shared bottom rail like on every other screen, so it never reads as one more grid action.
		addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.back"), button -> ScreenImpl.setScreen(parent))));
		// Destructive verbs say what they do before the player commits: Deactivate keeps files, Remove deletes them.
		for (int index = 0; index < actions.size() && index < actionButtons.size(); index++) {
			Component tooltip = actions.get(index).tooltip();
			if (tooltip != null) setTooltip(actionButtons.get(index), tooltip);
		}
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

	private void openGroups() {
		if (busy) return;
		ScreenImpl.setScreen(GroupSelectionScreen.forInstalledRecord(this, pack.record(), false));
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

	/** Stop syncing is a pure local declaration: nothing is touched, the state just flips. */
	private void stopSyncing() {
		if (busy) return;
		markBusy();
		controller.stopSyncing(pack, this::reopenOrList);
	}

	/** Resume syncing is an explicit attach: the normal update flow runs and ends attached; only an inline completion navigates, other outcomes release the buttons. */
	private void resumeSyncing() {
		if (busy) return;
		markBusy();
		controller.update(pack, completed -> {
			released();
			if (completed) reopenOrList();
		});
	}

	private void returnToList() {
		ScreenImpl.setScreen(parent instanceof InstalledModpacksScreen list ? list : parent);
	}

	private void reopenOrList() {
		InstalledModpackController.Pack next = controller.installedPack(pack.modpackId());
		ScreenImpl.setScreen(next == null ? parent : new ModpackSettingsScreen(parent, controller, next));
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
		actionButtons.get(0).setMessage(VersionedText.text(upToDate ? "automodpack.management.upToDate" : "automodpack.management.update"));
	}

	// The header stack: description, state, identity, id, contents, optional connection line, generation.
	private static final int HEADER_TOP = 28;
	private static final int STATE_LINE_GAP = 16;
	private static final int IDENTITY_LINE_GAP = 14;
	private static final int LINE_GAP = 12;

	/** The generation line's y - the end of the header stack the render walks - so the action grid derives from one layout. */
	private int generationY() {
		int y = HEADER_TOP + STATE_LINE_GAP + IDENTITY_LINE_GAP + LINE_GAP + LINE_GAP;
		if (pack.connectionDetail() != null) y += LINE_GAP;
		return y;
	}

	// The grid reads as one block of controls, not another text line: it clears the prose by the line gap plus two widget gaps.
	private int actionGridTop() {
		return generationY() + LINE_GAP + 2 * ActionAreaLayout.GAP;
	}

	/** Picks the widest column count whose grid stays clear of the bottom rail; 3 columns still keeps every button at or above the 88px minimum width. */
	private int actionColumns(int actionCount) {
		int gridTop = actionGridTop();
		int bottomLimit = this.height - 28 - ActionAreaLayout.BUTTON_HEIGHT - ActionAreaLayout.GAP;
		for (int columns = 2; columns <= 3; columns++) {
			int rows = (actionCount + columns - 1) / columns;
			int bottom = gridTop + rows * ActionAreaLayout.BUTTON_HEIGHT + (rows - 1) * ActionAreaLayout.GAP;
			if (bottom <= bottomLimit - ActionAreaLayout.GAP) return columns;
		}
		return 3;
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		int width = panelWidth(PANEL_WIDTH);
		MutableComponent name = VersionedText.literal(pack.name()).withStyle(ChatFormatting.BOLD);
		drawCenteredTextWithShadow(matrices, this.font, name, this.width / 2, 12, TextColors.WHITE);
		if (pack.connectionAvailable())
			showHoverTooltip(VersionedText.text("automodpack.packDetails.server", pack.connectionOrigin()), this.width / 2 - this.font.width(name) / 2, 12, this.font.width(name), mouseX, mouseY);
		int y = HEADER_TOP;
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.packDetails.description").withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
		y += STATE_LINE_GAP;
		String state = pack.active() ? VersionedText.text("automodpack.packManager.active", pack.name()).getString() : VersionedText.text("automodpack.packManager.noActive").getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, state, width)).withStyle(pack.active() ? ChatFormatting.GREEN : ChatFormatting.GRAY), this.width / 2, y,
				TextColors.WHITE);
		y += IDENTITY_LINE_GAP;
		String version = VersionedText.text("automodpack.packDetails.identity", pack.record().manifest().loader(), pack.record().manifest().loaderVersion(), pack.record().manifest().mcVersion()).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, version, width)).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
		y += LINE_GAP;
		String modpackId = VersionedText.text("automodpack.packDetails.id", pack.modpackId()).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, modpackId, width)).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
		showHoverTooltip(VersionedText.literal(pack.modpackId()), this.width / 2 - this.font.width(modpackId) / 2, y, this.font.width(modpackId), mouseX, mouseY);
		y += LINE_GAP;
		String contents = VersionedText.text("automodpack.packDetails.contents", UiFormat.plural(pack.groupCount(), "automodpack.confirm.groupCount").getString(),
				UiFormat.plural(pack.fileCount(), "automodpack.confirm.fileCount").getString(), UiFormat.formatSize(pack.fileBytes())).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, contents, width)).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
		y += LINE_GAP;
		if (pack.connectionDetail() != null) {
			String connection = VersionedText.text("automodpack.packDetails.connection", pack.connectionOrigin(), pack.connectionDetail()).getString();
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, connection, width)).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
			y += LINE_GAP;
		}
		String contentToken = pack.record().contentToken();
		String generation = VersionedText.text("automodpack.packDetails.generation", contentToken.substring(0, Math.min(contentToken.length(), 7)), UiFormat.formatInstant(pack.record().createdAt())).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, generation, width)).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
		showHoverTooltip(VersionedText.literal(contentToken), this.width / 2 - this.font.width(generation) / 2, y, this.font.width(generation), mouseX, mouseY);
		if (busyVisible)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.packDetails.working").withStyle(ChatFormatting.YELLOW), this.width / 2, this.height - 44, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
