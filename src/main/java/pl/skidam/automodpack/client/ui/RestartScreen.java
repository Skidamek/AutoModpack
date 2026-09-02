package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public class RestartScreen extends VersionedScreen {

	private final UpdateType updateType;
	private final Changelogs changelogs;
	private Button cancelButton;
	private Button restartButton;
	private Button changelogsButton;

	public RestartScreen(UpdateType updateType, Changelogs changelogs) {
		super(VersionedText.translatable("automodpack.restart.title"));
		this.updateType = updateType;
		this.changelogs = changelogs;

		if (AudioManager.isMusicPlaying()) AudioManager.stopMusic();
	}

	@Override
	protected void init() {
		super.init();

		initWidgets();

		this.addRenderableWidget(cancelButton);
		this.addRenderableWidget(restartButton);
		this.addRenderableWidget(changelogsButton);

		if (changelogs == null || (changelogs.changedFiles().isEmpty() && changelogs.removedFiles().isEmpty() && changelogs.latestPatchNotes().isBlank())) changelogsButton.active = false;
	}

	public void initWidgets() {
		assert this.minecraft != null;

		int buttonWidth = actionButtonWidth(310, 2);
		cancelButton = buttonWidget(actionButtonX(310, 2, 0), this.height - 74, buttonWidth, 20, VersionedText.translatable("automodpack.restart.cancel"), button -> {
			ScreenImpl.setScreen(null);
		});

		restartButton = buttonWidget(actionButtonX(310, 2, 1), this.height - 74, buttonWidth, 20,
				VersionedText.translatable("automodpack.restart.confirm").withStyle(ChatFormatting.BOLD), button -> {
					minecraft.stop();
				});

		changelogsButton = buttonWidget(this.width / 2 - 75, this.height - 48, 150, 20, VersionedText.translatable("automodpack.changelog.view"),
				button -> {
					new ScreenManager().changelog(this, changelogs);
				});
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		int lineHeight = 12; // Consistent line spacing

		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.translatable("automodpack.restart." + updateType.toString()).withStyle(ChatFormatting.BOLD), this.width / 2, this.height / 2 - 88,
				TextColors.WHITE);

		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.restart.description"), this.width / 2,
				this.height / 2 - 88 + lineHeight * 3, TextColors.WHITE);

		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.restart.secDescription"), this.width / 2,
				this.height / 2 - 88 + lineHeight * 4, TextColors.WHITE);

		int changed = changelogs == null ? 0 : changelogs.changedFiles().size();
		int removed = changelogs == null ? 0 : changelogs.removedFiles().size();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.summary.filesChanged", changed).withStyle(ChatFormatting.GRAY), this.width / 2,
				this.height / 2 - 28, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.summary.filesRemoved", removed).withStyle(ChatFormatting.GRAY), this.width / 2,
				this.height / 2 - 16, TextColors.WHITE);
		String reason = changelogs == null || changelogs.restartReasons().isEmpty()
				? VersionedText.translatable("automodpack.summary.restartRequired").getString()
				: VersionedText.translatable("automodpack.summary.restartReason", String.join(", ", changelogs.restartReasons())).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, reason, this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2,
				this.height / 2 - 4, TextColors.WHITE);
		String notes = changelogs == null ? "" : changelogs.latestPatchNotes();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.patchNotes.latest").withStyle(ChatFormatting.YELLOW), this.width / 2,
				this.height / 2 + 10, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font,
				notes.isBlank() ? VersionedText.translatable("automodpack.patchNotes.none").getString() : notes, this.width - 20)).withStyle(ChatFormatting.WHITE), this.width / 2,
				this.height / 2 + 22, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
