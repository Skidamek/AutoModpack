package pl.skidam.automodpack.client.ui.screen;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.LoadingDots;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

public class PreparingScreen extends VersionedScreen {
	private final long startedAtNanos = System.nanoTime();

	public PreparingScreen() {
		super(VersionedText.translatable("automodpack.preparing.title"));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.preparing.title").withStyle(ChatFormatting.BOLD), this.width / 2, this.height / 2 - 12,
				TextColors.WHITE);
		MutableComponent waiting = VersionedText.translatable("automodpack.wait").withStyle(ChatFormatting.GRAY);
		MutableComponent dots = VersionedText.literal(LoadingDots.frame(System.nanoTime() - startedAtNanos)).withStyle(ChatFormatting.GRAY);
		int totalWidth = this.font.width(waiting) + 4 + this.font.width(dots);
		int left = (this.width - totalWidth) / 2;
		drawTextWithShadow(matrices, this.font, waiting, left, this.height / 2 + 6, TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, dots, left + this.font.width(waiting) + 4, this.height / 2 + 6, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
