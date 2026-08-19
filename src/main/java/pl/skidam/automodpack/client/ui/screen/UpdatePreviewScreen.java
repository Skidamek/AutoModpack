package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.ActionAreaLayout;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.update.UpdatePreview;

/** A concise confirmation screen. Detailed file changes open in the shared browser. */
public final class UpdatePreviewScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private final Screen parent;
	private final UpdatePreview preview;
	private final String modpackName;
	private final UpdatePreview.Mode mode;
	private final boolean returnToSelection;
	private final Runnable continueAction;
	private final Runnable cancelAction;
	private final ChangeSet changes;
	private boolean finished;

	public UpdatePreviewScreen(Screen parent, UpdatePreview preview, String modpackName, boolean returnToSelection, Runnable continueAction,
			Runnable cancelAction) {
		super(VersionedText.translatable(titleKey(preview.mode())));
		this.parent = parent;
		this.preview = preview;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.mode = preview.mode();
		this.returnToSelection = returnToSelection;
		this.continueAction = continueAction;
		this.cancelAction = cancelAction;
		this.changes = preview.changeSet();
	}

	@Override
	protected void init() {
		super.init();
		List<ActionRow> rows = new ArrayList<>();
		if (!preview.patchNotesHistory().isEmpty())
			rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.patchNotes.all"), button -> openPatchNotes())));
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(returnToSelection ? VersionedText.translatable("automodpack.back") : VersionedText.translatable("automodpack.cancel"), button -> cancel()),
				optionalAction(VersionedText.translatable("automodpack.browser.reviewFiles"), button -> openFiles()),
				primaryAction(VersionedText.translatable(actionKey(mode)), button -> continueUpdate())));
		this.addActionArea(310, this.height - 28, rows.toArray(ActionRow[]::new));
	}

	private void continueUpdate() {
		if (finished) return;
		finished = true;
		ScreenImpl.setScreen(new PreparingScreen());
		continueAction.run();
	}

	private void cancel() {
		if (finished) return;
		finished = true;
		ScreenImpl.setScreen(parent);
		cancelAction.run();
	}

	private void openFiles() {
		ScreenImpl.setScreen(new ChangeBrowserScreen(this,
				VersionedText.translatable("automodpack.browser.previewTitle"), VersionedText.translatable(reviewKey(mode)), changes, preview.featureNames()));
	}

	private void openPatchNotes() {
		ScreenImpl.setScreen(new PatchNotesHistoryScreen(this, preview.patchNotesHistory(), modpackName));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String title = VersionedText.translatable(modpackName.isBlank() ? titleKey(mode) : namedTitleKey(mode), modpackName).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.BOLD), this.width / 2, 14,
				TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable(reviewKey(mode)).withStyle(ChatFormatting.GRAY), this.width / 2, 29, TextColors.WHITE);

		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.patchNotes.latest").withStyle(ChatFormatting.YELLOW), this.width / 2, 47, TextColors.WHITE);
		String patchNotes = preview.latestPatchNotes();
		List<String> noteLines = patchNotes.isBlank()
				? List.of(VersionedText.translatable("automodpack.patchNotes.none").getString())
				: wrapToWidth(this.font, patchNotes, Math.max(1, panelWidth(PANEL_WIDTH) - 20), this.height < 260 ? 1 : 2);
		for (int index = 0; index < noteLines.size(); index++)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(noteLines.get(index)).withStyle(ChatFormatting.WHITE), this.width / 2, 59 + index * 12, TextColors.WHITE);

		int y = 88;
		UpdatePreview.GroupConsequences groups = preview.groupConsequences();
		boolean staleSelection = !groups.staleGroups().isEmpty();
		String selection = staleSelection
				? VersionedText.translatable("automodpack.update.staleSelection").getString()
				: VersionedText.translatable("automodpack.update.groupsSelected", groups.resolvedGroups().size()).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, selection, panelWidth(PANEL_WIDTH))).withStyle(staleSelection ? ChatFormatting.RED : ChatFormatting.GRAY),
				this.width / 2, y, TextColors.WHITE);
		y += 15;
		if (!preview.conflicts().isEmpty()) {
			String conflict = VersionedText.translatable("automodpack.browser.conflicts", preview.conflicts().size()).getString();
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(conflict).withStyle(ChatFormatting.YELLOW), this.width / 2, y, TextColors.WHITE);
			y += 15;
		}

		ChangeSet.Summary summary = changes.summary();
		String fileSummary = VersionedText.translatable("automodpack.browser.diffSummary", summary.addedFiles(), summary.modifiedFiles(), summary.removedFiles(), summary.preservedFiles(), summary.unsafeFiles())
				.getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(fileSummary).withStyle(ChatFormatting.WHITE), this.width / 2, y, TextColors.WHITE);
		y += 15;
		String bytes = VersionedText.translatable("automodpack.browser.downloadSummary", UiFormat.formatSize(preview.uncachedAcquisitionBytes()), UiFormat.formatSize(preview.addedBytes() + preview.changedBytes()))
				.getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(bytes).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
		y += 15;
		long otherEffects = changes.effects().stream().filter(effect -> !"restart".equals(effect.category())).count();
		if (otherEffects > 0) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.summary.otherEffects", otherEffects).withStyle(ChatFormatting.YELLOW), this.width / 2, y, TextColors.WHITE);
			y += 15;
		}
		String restart = preview.restartReasons().isEmpty()
				? VersionedText.translatable("automodpack.summary.noRestart").getString()
				: VersionedText.translatable("automodpack.summary.restartRequired").getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(restart).withStyle(ChatFormatting.GRAY), this.width / 2, y, TextColors.WHITE);
	}

	private static String titleKey(UpdatePreview.Mode mode) {
		return switch (mode) {
			case UPDATE -> "automodpack.update.title";
			case DEACTIVATION -> "automodpack.update.deactivationTitle";
			case REMOVAL -> "automodpack.update.removalTitle";
		};
	}

	private static String namedTitleKey(UpdatePreview.Mode mode) {
		return switch (mode) {
			case UPDATE -> "automodpack.update.updateNamed";
			case DEACTIVATION -> "automodpack.update.deactivateNamed";
			case REMOVAL -> "automodpack.update.removeNamed";
		};
	}

	private static String reviewKey(UpdatePreview.Mode mode) {
		return switch (mode) {
			case UPDATE -> "automodpack.update.reviewUpdate";
			case DEACTIVATION -> "automodpack.update.reviewDeactivation";
			case REMOVAL -> "automodpack.update.reviewRemoval";
		};
	}

	private static String actionKey(UpdatePreview.Mode mode) {
		return switch (mode) {
			case UPDATE -> "automodpack.update.apply";
			case DEACTIVATION -> "automodpack.update.deactivate";
			case REMOVAL -> "automodpack.update.remove";
		};
	}

	@Override
	public boolean shouldCloseOnEsc() {
		cancel();
		return false;
	}
}
