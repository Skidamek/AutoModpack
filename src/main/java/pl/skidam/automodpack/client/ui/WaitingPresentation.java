package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.Font;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

/** Shared waiting body used by the preparing screen and the post-download apply wait. */
public final class WaitingPresentation {
	private WaitingPresentation() {}

	public static void render(VersionedMatrices matrices, Font font, int width, int height, long elapsedNanos) {
		VersionedScreen.drawCenteredTextWithShadow(matrices, font, VersionedText.translatable("automodpack.preparing.title").withStyle(ChatFormatting.BOLD), width / 2, height / 2 - 12, TextColors.WHITE);
		MutableComponent waiting = VersionedText.translatable("automodpack.wait").withStyle(ChatFormatting.GRAY);
		MutableComponent dots = VersionedText.literal(LoadingDots.frame(elapsedNanos)).withStyle(ChatFormatting.GRAY);
		int totalWidth = font.width(waiting) + 4 + font.width(dots);
		int left = (width - totalWidth) / 2;
		VersionedScreen.drawTextWithShadow(matrices, font, waiting, left, height / 2 + 6, TextColors.WHITE);
		VersionedScreen.drawTextWithShadow(matrices, font, dots, left + font.width(waiting) + 4, height / 2 + 6, TextColors.WHITE);
	}
}
