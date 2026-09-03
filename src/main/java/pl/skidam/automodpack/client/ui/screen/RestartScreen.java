package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public class RestartScreen extends VersionedScreen {
	private static final int BODY = 420;

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
		ActionRow[] rowArray = rows.toArray(ActionRow[]::new);
		this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray);
		int bottomLimit = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray) - 4;
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		int changed = changelogs == null ? 0 : changelogs.changedFiles().size();
		int removed = changelogs == null ? 0 : changelogs.removedFiles().size();
		String reason = changelogs == null || changelogs.restartReasons().isEmpty()
				? VersionedText.translatable("automodpack.summary.restartRequired").getString()
				: VersionedText.translatable("automodpack.summary.restartReason", String.join(", ", changelogs.restartReasons())).getString();
		String notes = changelogs == null ? "" : changelogs.latestPatchNotes();
		List<MutableComponent> lines = new ArrayList<>();
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.restart.description").getString(), wrapWidth));
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.restart.secDescription").getString(), wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.summary.filesChanged", changed).getString(), wrapWidth, ChatFormatting.GRAY));
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.summary.filesRemoved", removed).getString(), wrapWidth, ChatFormatting.GRAY));
		lines.addAll(wrapParagraph(this.font, reason, wrapWidth, ChatFormatting.YELLOW));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.patchNotes.latest").getString(), wrapWidth, ChatFormatting.YELLOW));
		lines.addAll(wrapParagraph(this.font, notes.isBlank() ? VersionedText.translatable("automodpack.patchNotes.none").getString() : notes, wrapWidth));
		int preserved = changelogs == null ? 0 : changelogs.changeSet().summary().preservedFiles();
		if (preserved > 0) {
			lines.add(blankLine());
			lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.restart.preservedFiles", preserved).getString(), wrapWidth, ChatFormatting.GRAY));
		}
		this.addCenteredScrollBody(BODY, 42, bottomLimit, lines);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.translatable("automodpack.restart." + updateType.toString()).withStyle(ChatFormatting.BOLD), this.width / 2, 14,
				TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(null));
	}
}
