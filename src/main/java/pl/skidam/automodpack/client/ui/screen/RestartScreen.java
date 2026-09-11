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
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public class RestartScreen extends VersionedScreen {
	private static final int BODY = 420;

	private final UpdateType updateType;
	private final Changelogs changelogs;
	private int titleTop;

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
		int preservedFiles = hasChangelogs ? changelogs.changeSet().summary().preservedFiles() : 0;
		List<ActionRow> rows = new ArrayList<>();
		if (hasChangelogs) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.changelog.view"), button -> ScreenManager.changelog(this, changelogs))));
		if (preservedFiles > 0) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.management.preservedFilesCount", preservedFiles), button -> openVault())));
		List<InstalledModpackController.StalePack> stalePacks = new InstalledModpackController().stalePacks();
		if (!stalePacks.isEmpty()) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(stalePackLabel(stalePacks), button -> forgetStalePacks(stalePacks))));
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.restart.cancel"), button -> ScreenImpl.setScreen(null)),
				primaryAction(VersionedText.translatable("automodpack.restart.confirm").withStyle(ChatFormatting.BOLD), button -> minecraft.stop())));
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
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.restart.description").getString(), wrapWidth));
		lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.restart.secDescription").getString(), wrapWidth));
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
				lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.restart.reasonsTitle").getString(), wrapWidth, ChatFormatting.YELLOW));
				for (String reason : reasons)
					lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.restart.reason." + reason).getString(), wrapWidth, ChatFormatting.GRAY));
			}
			if (summary.preservedFiles() > 0) {
				lines.add(blankLine());
				lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.restart.preservedFiles", summary.preservedFiles()).getString(), wrapWidth, ChatFormatting.GRAY));
			}
			String notes = changelogs.latestPatchNotes();
			if (!notes.isBlank()) {
				lines.add(blankLine());
				lines.addAll(wrapParagraph(this.font, VersionedText.translatable("automodpack.patchNotes.latest").getString(), wrapWidth, ChatFormatting.YELLOW));
				lines.addAll(wrapParagraph(this.font, notes, wrapWidth));
			}
		}
		return lines;
	}

	/** The vault is one click away from the screen that just preserved the files. */
	private void openVault() {
		new InstalledModpackController().openPreservedFiles(this, this::rebuild);
	}

	/** One stale pack shows by name; several show as a count. */
	private MutableComponent stalePackLabel(List<InstalledModpackController.StalePack> stalePacks) {
		return stalePacks.size() == 1
				? VersionedText.translatable("automodpack.restart.removeStale.one", stalePacks.get(0).name())
				: VersionedText.translatable("automodpack.restart.removeStale.other", stalePacks.size());
	}

	/** The removal only touches retained state of packs the origin no longer serves, so it is safe while the game runs. */
	private void forgetStalePacks(List<InstalledModpackController.StalePack> stalePacks) {
		InstalledModpackController controller = new InstalledModpackController();
		for (InstalledModpackController.StalePack pack : stalePacks) controller.forgetStalePack(pack.modpackId(), this::rebuild);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.translatable("automodpack.restart." + updateType.toString()).withStyle(ChatFormatting.BOLD), this.width / 2, titleTop,
				TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(null));
	}
}
