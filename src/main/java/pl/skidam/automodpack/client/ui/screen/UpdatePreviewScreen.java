package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.ChangeSummary;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** A concise confirmation screen. Detailed file changes open in the shared browser. */
public final class UpdatePreviewScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private final Screen parent;
	private final UpdatePreview preview;
	private final String modpackName;
	private final UpdatePreview.Mode mode;
	private final ModpackUpdater updater;
	private final Runnable continueAction;
	private final Runnable cancelAction;
	private final ChangeSet changes;
	private boolean finished;

	public UpdatePreviewScreen(Screen parent, UpdatePreview preview, String modpackName, ModpackUpdater updater, Runnable continueAction,
			Runnable cancelAction) {
		super(VersionedText.translatable(titleKey(preview.mode())));
		this.parent = parent;
		this.preview = preview;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.mode = preview.mode();
		this.updater = updater;
		this.continueAction = continueAction;
		this.cancelAction = cancelAction;
		this.changes = preview.changeSet();
	}

	@Override
	protected void init() {
		super.init();
		List<ActionRow> rows = buildRows();
		List<Button> buttons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rows.toArray(ActionRow[]::new));
		setTooltip(buttons.get(buttons.size() - 2), ChangeSummary.diffLegend());
		int topY = 42;
		int bottomY = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rows.toArray(ActionRow[]::new)) - 4;
		this.addScrollBody(PANEL_WIDTH, topY, bottomY, buildBodyLines());
	}

	private List<ActionRow> buildRows() {
		List<ActionRow> rows = new ArrayList<>();
		if (!preview.patchNotesHistory().isEmpty())
			rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.patchNotes.all"), button -> openPatchNotes())));
		if (canCustomize())
			rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.firstConnect.customize"), button -> customize())));
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.cancel"), button -> cancel()),
				optionalAction(VersionedText.translatable("automodpack.browser.reviewFiles"), button -> openFiles()),
				primaryAction(VersionedText.translatable(actionKey(mode)), button -> continueUpdate())));
		return rows;
	}

	private boolean canCustomize() {
		if (mode != UpdatePreview.Mode.UPDATE || updater == null) return false;
		try {
			return PackConfirmCopy.hasOptionalGroups(updater.getSelectedTarget().manifest());
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	private List<String> buildBodyLines() {
		int wrapWidth = Math.max(1, panelWidth(PANEL_WIDTH) - 20);
		List<String> lines = new ArrayList<>();
		lines.add(VersionedText.translatable("automodpack.patchNotes.latest").getString());
		String patchNotes = preview.latestPatchNotes();
		if (patchNotes.isBlank()) lines.add(VersionedText.translatable("automodpack.patchNotes.none").getString());
		else lines.addAll(wrapToWidth(this.font, patchNotes, wrapWidth));
		lines.add("");
		UpdatePreview.GroupConsequences groups = preview.groupConsequences();
		boolean staleSelection = !groups.staleGroups().isEmpty();
		lines.add(staleSelection
				? VersionedText.translatable("automodpack.update.staleSelection").getString()
				: UiFormat.plural(groups.resolvedGroups().size(), "automodpack.update.groupsSelected").getString());
		if (!preview.conflicts().isEmpty())
			lines.add(UiFormat.plural(preview.conflicts().size(), "automodpack.browser.conflicts").getString());
		ChangeSet.Summary summary = changes.summary();
		lines.add(ChangeSummary.diffLine(summary.addedFiles(), summary.modifiedFiles(), summary.removedFiles(), summary.preservedFiles(), summary.unsafeFiles()));
		if (mode == UpdatePreview.Mode.UPDATE)
			lines.add(VersionedText.translatable("automodpack.browser.downloadSummary", UiFormat.formatSize(preview.uncachedAcquisitionBytes()), UiFormat.formatSize(preview.addedBytes() + preview.changedBytes()))
					.getString());
		long otherEffects = changes.effects().stream().filter(effect -> !"restart".equals(effect.category())).count();
		if (otherEffects > 0) lines.add(VersionedText.translatable("automodpack.summary.otherEffects", otherEffects).getString());
		lines.add(preview.restartReasons().isEmpty()
				? VersionedText.translatable("automodpack.summary.noRestart").getString()
				: VersionedText.translatable("automodpack.summary.restartRequired").getString());
		return lines;
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

	private void customize() {
		if (finished || updater == null) return;
		Consumer<SelectionIntent> action = intent -> {
			try {
				updater.reselectAndPreview(intent);
			} catch (RuntimeException e) {
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.MULTIPLAYER, null));
			}
		};
		ScreenImpl.setScreen(new ModpackSelectionScreen(this, updater, action));
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
