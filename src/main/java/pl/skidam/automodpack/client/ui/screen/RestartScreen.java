package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.ChangeSummary;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.client.Changelogs;
import pl.skidam.automodpack_core.client.UpdateType;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

public class RestartScreen extends VersionedScreen {
	private static final int BODY = 420;

	private final UpdateType updateType;
	private final Changelogs changelogs;
	private int titleTop;

	public RestartScreen(UpdateType updateType, Changelogs changelogs) {
		super(VersionedText.text("automodpack.restart.title"));
		this.updateType = updateType;
		this.changelogs = changelogs;

		if (AudioManager.isMusicPlaying()) AudioManager.stopMusic();
	}

	@Override
	protected void init() {
		super.init();
		assert this.minecraft != null;
		boolean hasChangelogs = changelogs != null && (!changelogs.changedFiles().isEmpty() || !changelogs.removedFiles().isEmpty() || !changelogs.latestPatchNotes().isBlank());
		int preservedFiles = hasChangelogs ? changelogs.changeSet().summary().preservedFiles() : 0;
		List<ActionRow> rows = new ArrayList<>();
		if (hasChangelogs) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.text("automodpack.changelog.view"), button -> ScreenManager.changelog(changelogs))));
		if (preservedFiles > 0) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.text("automodpack.management.preservedFilesCount", preservedFiles), button -> openVault())));
		if (!new InstalledModpackController().stalePacks().isEmpty())
			rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.text("automodpack.restart.removeStale"),
					button -> new InstalledModpackController().offerStalePackRemoval(this::rebuild))));
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.text("automodpack.restart.cancel"), button -> ScreenImpl.multiplayer()),
				primaryAction(VersionedText.text("automodpack.restart.confirm").withStyle(ChatFormatting.BOLD), button -> minecraft.stop())));
		ActionRow[] rowArray = rows.toArray(ActionRow[]::new);
		List<MutableComponent> lines = buildBodyLines();
		DialogLayout layout = layoutDialogWithActions(28, LINE_HEIGHT, lines.size() * LINE_HEIGHT, 0, rowArray);
		this.titleTop = layout.titleTop();
		this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray);
		this.addCenteredScrollBody(BODY, layout.column().bodyTop(), layout.column().bodyBottom(), lines);
	}

	/** The whole restart dialog as one centered column: what to do, what changed, why a restart is needed. */
	private List<MutableComponent> buildBodyLines() {
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		List<MutableComponent> lines = new ArrayList<>();
		lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.restart.description"), wrapWidth));
		lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.restart.secDescription"), wrapWidth));
		if (changelogs != null) {
			ChangeSet.Summary summary = changelogs.changeSet().summary();
			boolean hasDiff = summary.addedFiles() > 0 || summary.modifiedFiles() > 0 || summary.removedFiles() > 0 || summary.preservedFiles() > 0 || summary.unsafeFiles() > 0;
			if (hasDiff) {
				lines.add(blankLine());
				lines.addAll(ChangeSummary.diffLines(summary.addedFiles(), summary.modifiedFiles(), summary.removedFiles(), summary.preservedFiles(), summary.unsafeFiles()));
			}
			List<String> reasons = changelogs.restartReasons();
			if (!reasons.isEmpty()) {
				lines.add(blankLine());
				lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.restart.reasonsTitle"), wrapWidth, ChatFormatting.YELLOW));
				for (String reason : reasons)
					lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.restart.reason." + reason), wrapWidth, ChatFormatting.GRAY));
			}
			if (summary.preservedFiles() > 0) {
				lines.add(blankLine());
				lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.restart.preservedFiles", summary.preservedFiles()), wrapWidth, ChatFormatting.GRAY));
			}
			String notes = changelogs.latestPatchNotes();
			if (!notes.isBlank()) {
				lines.add(blankLine());
				lines.addAll(wrapParagraph(this.font, VersionedText.str("automodpack.patchNotes.latest"), wrapWidth, ChatFormatting.YELLOW));
				lines.addAll(wrapParagraph(this.font, notes, wrapWidth));
			}
		}
		return lines;
	}

	/** The vault is one click away from the screen that just preserved the files. */
	private void openVault() {
		new InstalledModpackController().openPreservedFiles(this, this::rebuild);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.text("automodpack.restart." + updateType.toString()).withStyle(ChatFormatting.BOLD), this.width / 2, titleTop,
				TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(ScreenImpl::multiplayer);
	}
}
