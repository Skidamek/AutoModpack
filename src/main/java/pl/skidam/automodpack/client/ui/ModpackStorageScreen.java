package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
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
		int x = panelLeft(PANEL_WIDTH);
		int width = panelWidth(PANEL_WIDTH);
		int y = 76;
		if (controller.hasPreservedFiles(pack)) {
			this.addRenderableWidget(buttonWidget(x, y, width, 22, VersionedText.translatable("automodpack.management.preservedFiles"), button -> openPreservedFiles()));
			y += 26;
		}
		this.addRenderableWidget(buttonWidget(x, y, width, 22, VersionedText.translatable("automodpack.packDetails.localStorage"), button -> openLocalStorage()));
		this.addRenderableWidget(buttonWidget(centeredActionButtonX(PANEL_WIDTH, 1, 1, 0), this.height - 28, actionButtonWidth(PANEL_WIDTH, 1), 20,
				VersionedText.translatable("automodpack.back"), button -> ScreenImpl.setScreen(parent)));
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
		ScreenImpl.setScreen(parent);
		return false;
	}
}
