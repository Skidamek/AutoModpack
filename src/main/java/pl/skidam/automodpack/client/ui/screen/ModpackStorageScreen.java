package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.ActionAreaLayout;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/** Routes pack-specific preserved files and the explicit global storage cleanup. */
public final class ModpackStorageScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 320;

	private final Screen parent;
	private final InstalledModpackController controller;
	private final InstalledModpackController.Pack pack;

	public ModpackStorageScreen(Screen parent, InstalledModpackController controller, InstalledModpackController.Pack pack) {
		super(VersionedText.translatable("automodpack.packDetails.storage"));
		this.parent = parent;
		this.controller = controller;
		this.pack = pack;
	}

	@Override
	protected void init() {
		super.init();
		List<ActionRow> actions = new ArrayList<>();
		if (controller.hasPreservedFiles(pack)) {
			actions.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY,
					optionalAction(VersionedText.translatable("automodpack.management.preservedFiles"), button -> openPreservedFiles())));
		}
		actions.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, primaryAction(VersionedText.translatable("automodpack.packDetails.localStorage"), button -> openLocalStorage())));
		this.addActionAreaAt(PANEL_WIDTH, 76, actions.toArray(ActionRow[]::new));
		this.addActionArea(PANEL_WIDTH, this.height - 28, actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), button -> ScreenImpl.setScreen(parent))));
	}

	private void openPreservedFiles() {
		controller.openPreservedFiles(this, pack, () -> {});
	}

	private void openLocalStorage() {
		ScreenImpl.setScreen(new ClientStorageMaintenanceScreen(this, controller));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(pack.name()).withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.packDetails.storageDescription").withStyle(ChatFormatting.GRAY), this.width / 2, 30, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
