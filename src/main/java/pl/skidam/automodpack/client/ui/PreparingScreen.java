package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

public class PreparingScreen extends VersionedScreen {

	public PreparingScreen() {
		super(VersionedText.literal("PreparingScreen"));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Preparing update").withStyle(ChatFormatting.BOLD), this.width / 2, this.height / 2 - 12,
				TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.wait").withStyle(ChatFormatting.GRAY), this.width / 2, this.height / 2 + 6,
				TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
