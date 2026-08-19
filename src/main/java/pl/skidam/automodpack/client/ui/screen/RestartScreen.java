package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.ActionAreaLayout;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public class RestartScreen extends VersionedScreen {

	private final UpdateType updateType;
	private final Changelogs changelogs;

	public RestartScreen(UpdateType updateType, Changelogs changelogs) {
		super(VersionedText.translatable("automodpack.restart.title"));
		this.updateType = updateType;
		this.changelogs = changelogs;

		if (AudioManager.isMusicPlaying()) AudioManager.stopMusic();
	}

	@Override
	protected void init() {
		super.init();
		assert this.minecraft != null;
		boolean hasChangelogs = changelogs != null && (!changelogs.changedFiles().isEmpty() || !changelogs.removedFiles().isEmpty() || !changelogs.latestPatchNotes().isBlank());
		List<ActionRow> rows = new ArrayList<>();
		if (hasChangelogs) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.changelog.view"), button -> ScreenManager.changelog(this, changelogs))));
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.restart.cancel"), button -> ScreenImpl.setScreen(null)),
				primaryAction(VersionedText.translatable("automodpack.restart.confirm").withStyle(ChatFormatting.BOLD), button -> minecraft.stop())));
		this.addActionArea(310, this.height - 28, rows.toArray(ActionRow[]::new));
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
		return handleBackOnEscape(() -> ScreenImpl.setScreen(null));
	}
}
