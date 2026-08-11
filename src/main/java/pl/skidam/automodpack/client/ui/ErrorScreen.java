package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

import java.nio.file.Files;
import java.nio.file.Path;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;

public class ErrorScreen extends VersionedScreen {

	private final Screen parent;
	private final FailureRequest request;
	private Button backButton;
	private Button logsButton;
	private Button copyButton;
	private Button retryButton;
	private boolean copied;

	public ErrorScreen(Screen parent, FailureRequest request) {
		super(VersionedText.translatable("automodpack.error.title"));
		this.parent = parent;
		this.request = request;

		if (AudioManager.isMusicPlaying()) AudioManager.stopMusic();
	}

	@Override
	protected void init() {
		super.init();

		initWidgets();

		this.addRenderableWidget(logsButton);
		this.addRenderableWidget(copyButton);
		if (retryButton != null) this.addRenderableWidget(retryButton);
		this.addRenderableWidget(backButton);
	}

	private void initWidgets() {
		int buttonWidth = actionButtonWidth(310, 2);
		int left = panelLeft(310);
		int right = left + buttonWidth + actionRowGap();
		int firstRowY = this.height - 52;
		int secondRowY = this.height - 28;
		if (request.retryAction() != null) {
			retryButton = buttonWidget(left, firstRowY, buttonWidth, 20,
				VersionedText.translatable("automodpack.error.retry"), button -> retry());
			logsButton = buttonWidget(right, firstRowY, buttonWidth, 20,
				VersionedText.translatable("automodpack.error.openLogs"), button -> openLogs());
			copyButton = buttonWidget(left, secondRowY, buttonWidth, 20,
				VersionedText.translatable(copied ? "automodpack.error.copied" : "automodpack.error.copyDetails"), button -> copyDetails());
			backButton = buttonWidget(right, secondRowY, buttonWidth, 20, VersionedText.translatable("automodpack.back"), button -> back());
			return;
		}
		logsButton = buttonWidget(left, firstRowY, buttonWidth, 20,
				VersionedText.translatable("automodpack.error.openLogs"), button -> openLogs());
		copyButton = buttonWidget(right, firstRowY, buttonWidth, 20,
				VersionedText.translatable(copied ? "automodpack.error.copied" : "automodpack.error.copyDetails"), button -> copyDetails());
		backButton = buttonWidget(centeredActionButtonX(310, 1, 1, 0), secondRowY, actionButtonWidth(310, 1), 20, VersionedText.translatable("automodpack.back"), button -> back());
	}

	private void back() {
		ScreenImpl.setScreen(parent);
	}

	private void retry() {
		Runnable retryAction = request.retryAction();
		if (retryAction == null) return;
		ScreenImpl.setScreen(parent);
		retryAction.run();
	}

	private void openLogs() {
		Path gameDirectory = GameDirectory.current();
		Path logsDirectory = gameDirectory.resolve("logs");
		Util.getPlatform().openFile((Files.isDirectory(logsDirectory) ? logsDirectory : gameDirectory).toFile());
	}

	private void copyDetails() {
		Minecraft.getInstance().keyboardHandler.setClipboard(request.diagnosticText());
		copied = true;
		rebuild();
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
		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.translatable("automodpack.error.titleLine", VersionedText.translatable("automodpack.error").getString()).withStyle(ChatFormatting.RED),
				this.width / 2, 36, TextColors.WHITE);

		int y = 62;
		int contentBottom = this.height - 58;
		String summary = VersionedText.translatable(request.messageKey(), request.translationArguments()).getString();
		for (String line : wrapToWidth(this.font, summary, Math.max(1, this.width - 30), Math.max(1, (contentBottom - y) / 12))) {
			if (y >= contentBottom) return;
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line), this.width / 2, y, TextColors.LIGHT_GRAY);
			y += 12;
		}
		y += 8;
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.error.category", VersionedText.translatable(request.category().translationKey())).withStyle(ChatFormatting.GRAY),
				this.width / 2, y, TextColors.WHITE);
		y += 16;
		for (String line : wrapToWidth(this.font, VersionedText.translatable("automodpack.error.details").getString(), Math.max(1, this.width - 30), Math.max(1, (contentBottom - y) / 12))) {
			if (y >= contentBottom) return;
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line), this.width / 2, y, TextColors.GRAY);
			y += 12;
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		back();
		return false;
	}
}
