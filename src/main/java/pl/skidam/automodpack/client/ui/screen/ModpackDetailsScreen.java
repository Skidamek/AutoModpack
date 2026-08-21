package pl.skidam.automodpack.client.ui.screen;

import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.ActionAreaLayout;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/** A single, calm entry point for all actions on one installed modpack. */
public final class ModpackDetailsScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 320;

	private final Screen parent;
	private final InstalledModpackController controller;
	private final InstalledModpackController.Pack pack;
	private final List<Button> actionButtons = new ArrayList<>();
	private boolean busy;
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
		actions.add(new Action(pack.active() && upToDate ? "automodpack.management.upToDate" : pack.active() ? "automodpack.management.update" : "automodpack.management.activate", this::primaryAction));
		if (pack.active()) actions.add(new Action("automodpack.management.repair", this::repair));
		actions.add(new Action("automodpack.selection.button", this::openFeatures));
		if (controller.hasHistory(pack)) actions.add(new Action("automodpack.management.history", this::openHistory));
		actions.add(new Action("automodpack.management.files", this::openFiles));
		actions.add(new Action("automodpack.packDetails.storage", this::openStorage));
		if (pack.active()) actions.add(new Action("automodpack.management.deactivate", this::deactivate));
		actions.add(new Action("automodpack.management.remove", this::remove));
		int columns = actions.size() > 3 ? 2 : 1;
		List<ActionRow> rows = new ArrayList<>();
		for (int index = 0; index < actions.size(); index += columns) {
			int end = Math.min(actions.size(), index + columns);
			List<ActionDefinition> rowActions = new ArrayList<>(end - index);
			for (int rowIndex = index; rowIndex < end; rowIndex++) {
				Action action = actions.get(rowIndex);
				rowActions.add(rowIndex == 0
						? primaryAction(VersionedText.translatable(action.labelKey()), button -> action.action().run())
						: optionalAction(VersionedText.translatable(action.labelKey()), button -> action.action().run()));
			}
			rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, rowActions.toArray(ActionDefinition[]::new)));
		}
		for (Button button : addActionAreaAt(PANEL_WIDTH, 100, rows.toArray(ActionRow[]::new))) actionButtons.add(button);
		this.addActionArea(PANEL_WIDTH, this.height - 28, actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), button -> ScreenImpl.setScreen(parent))));
		updateActions();
	}

	private record Action(String labelKey, Runnable action) {}

	private void primaryAction() {
		if (busy) return;
		busy = true;
		updateActions();
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
		busy = true;
		updateActions();
		controller.repair(this, pack, this::updateCompleted);
	}

	private void openFeatures() {
		if (busy) return;
		ScreenImpl.setScreen(ModpackSelectionScreen.forInstalledRecord(this, pack.record(), false, false));
	}

	private void openHistory() {
		if (busy) return;
		busy = true;
		updateActions();
		controller.openHistory(pack, this::released);
	}

	private void openFiles() {
		if (busy) return;
		controller.openFiles(this, pack);
	}

	private void openStorage() {
		if (busy) return;
		ScreenImpl.setScreen(new ModpackStorageScreen(this, controller, pack));
	}

	private void remove() {
		if (busy) return;
		busy = true;
		updateActions();
		controller.remove(pack, this::released, () -> ScreenImpl.setScreen(new InstalledModpacksScreen(parent)));
	}

	private void deactivate() {
		if (busy) return;
		busy = true;
		updateActions();
		controller.deactivate(pack, this::released);
	}

	private void released() {
		busy = false;
		updateActions();
	}

	private void updateActions() {
		for (int index = 0; index < actionButtons.size(); index++) actionButtons.get(index).active = !busy && (index != 0 || !pack.active() || pack.connectionAvailable() && !upToDate);
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
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.packDetails.description").withStyle(ChatFormatting.GRAY), this.width / 2, 28, TextColors.WHITE);
		String state = pack.active() ? VersionedText.translatable("automodpack.packManager.active", pack.name()).getString() : VersionedText.translatable("automodpack.packManager.noActive").getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, state, width)).withStyle(pack.active() ? ChatFormatting.GREEN : ChatFormatting.GRAY), this.width / 2, 44,
				TextColors.WHITE);
		String connection = VersionedText.translatable(pack.connectionAvailable() ? "automodpack.packDetails.connected" : "automodpack.packDetails.disconnected").getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, connection, width)).withStyle(pack.connectionAvailable() ? ChatFormatting.AQUA : ChatFormatting.YELLOW),
				this.width / 2, 58,
				TextColors.WHITE);
		String version = VersionedText.translatable("automodpack.packDetails.identity", pack.record().manifest().loader(), pack.record().manifest().loaderVersion(), pack.record().manifest().mcVersion()).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, version, width)).withStyle(ChatFormatting.GRAY), this.width / 2, 70, TextColors.WHITE);
		String contents = VersionedText.translatable("automodpack.packDetails.contents", pack.groupCount(), pack.fileCount(), UiFormat.formatSize(pack.fileBytes())).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, contents, width)).withStyle(ChatFormatting.GRAY), this.width / 2, 82, TextColors.WHITE);
		if (busy) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.packDetails.working").withStyle(ChatFormatting.YELLOW), this.width / 2, this.height - 44, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
