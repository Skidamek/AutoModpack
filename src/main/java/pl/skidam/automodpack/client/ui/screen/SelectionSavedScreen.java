package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.clientConfig;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/**
 * Shown after a group selection was saved: the new selection only takes effect on the next launch, so this screen
 * is a restart prompt and nothing else. A separate screen instead of a mutated settings screen - a screen that
 * turns into a different one is two screens wearing one name.
 */
public final class SelectionSavedScreen extends VersionedScreen {
	private final Screen parent;
	private final String modpackName;

	public SelectionSavedScreen(Screen parent, String modpackName) {
		super(VersionedText.literal(modpackName));
		this.parent = parent;
		this.modpackName = modpackName;
	}

	@Override
	protected void init() {
		super.init();
		this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.text("automodpack.back"), press -> ScreenImpl.setScreen(parent)),
				primaryAction(VersionedText.text("automodpack.selection.restartNow").withStyle(ChatFormatting.BOLD), press -> this.minecraft.stop())));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, header(), this.width / 2, 11, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.selection.saved").withStyle(ChatFormatting.GREEN), this.width / 2, this.height / 2 - 30,
				TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.selection.restartRequired").withStyle(ChatFormatting.YELLOW), this.width / 2,
				this.height / 2 - 15, TextColors.WHITE);
		if (clientConfig != null && !clientConfig.updateSelectedModpackOnLaunch) {
			drawCenteredTextWithShadow(matrices, this.font,
					VersionedText.text("automodpack.selection.updateOnLaunchDisabled").withStyle(ChatFormatting.RED), this.width / 2,
					this.height / 2 - 45, TextColors.WHITE);
		}
	}

	private MutableComponent header() {
		return VersionedText.literal(truncateToWidth(this.font, modpackName, this.width - 20)).withStyle(ChatFormatting.BOLD);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}
}
