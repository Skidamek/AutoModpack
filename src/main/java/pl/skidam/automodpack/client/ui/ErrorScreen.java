package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

public class ErrorScreen extends VersionedScreen {

	private final Screen parent;
	private final String[] errorMessages;
	private Button backButton;

	public ErrorScreen(Screen parent, String... errorMessages) {
		super(VersionedText.literal("ErrorScreen"));
		this.parent = parent;
		this.errorMessages = errorMessages;

		if (AudioManager.isMusicPlaying()) AudioManager.stopMusic();
	}

	@Override
	protected void init() {
		super.init();

		initWidgets();

		this.addRenderableWidget(backButton);
	}

	private void initWidgets() {
		backButton = buttonWidget(actionButtonX(310, 2, 0), this.height - 28, actionButtonWidth(310, 2), 20, VersionedText.translatable("automodpack.back"), button -> back());
	}

	private void back() {
		ScreenImpl.setScreen(parent);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.literal("[AutoModpack] Error! ").append(VersionedText.translatable("automodpack.error").withStyle(ChatFormatting.RED)),
				this.width / 2, 36, TextColors.WHITE);

		int y = 62;
		int contentBottom = this.height - 58;
		for (String message : this.errorMessages) {
			for (String line : wrapToWidth(this.font, VersionedText.translatable(message).getString(), Math.max(1, this.width - 30), Math.max(1, (contentBottom - y) / 12))) {
				if (y >= contentBottom) return;
				drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line), this.width / 2, y, TextColors.LIGHT_GRAY);
				y += 12;
			}
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		back();
		return false;
	}
}
