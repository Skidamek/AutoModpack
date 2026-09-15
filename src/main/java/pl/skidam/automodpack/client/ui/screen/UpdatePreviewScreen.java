package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.ChangeSummary;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.client.Changelogs;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.HistoryViewRequest;
import pl.skidam.automodpack_core.screen.PreviewPayload;
import pl.skidam.automodpack_core.screen.ReviewActions;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/** A concise confirmation screen. Detailed file changes open in the shared browser. */
public final class UpdatePreviewScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private final Screen parent;
	private final UpdatePreview preview;
	private final String modpackName;
	private final String origin;
	private final UpdatePreview.Mode mode;
	private final ReviewActions actions;
	private final SelectedModpackTarget target;
	private final Runnable continueAction;
	private final Runnable cancelAction;
	private final ChangeSet changes;
	private boolean finished;
	private int titleTop;

	public UpdatePreviewScreen(Screen parent, PreviewPayload payload) {
		super(VersionedText.translatable(titleKey(payload.preview().mode())));
		this.parent = parent;
		this.preview = payload.preview();
		this.modpackName = payload.modpackName() == null ? "" : payload.modpackName();
		this.origin = payload.origin() == null ? "" : payload.origin();
		this.mode = preview.mode();
		this.actions = payload.actions();
		this.target = payload.target();
		this.continueAction = Objects.requireNonNull(payload.continueAction(), "continueAction");
		this.cancelAction = Objects.requireNonNull(payload.cancelAction(), "cancelAction");
		this.changes = preview.changeSet();
	}

	@Override
	protected void init() {
		super.init();
		List<ActionRow> rows = buildRows();
		ActionRow[] rowArray = rows.toArray(ActionRow[]::new);
		List<MutableComponent> body = buildBodyLines();
		// The origin takes the third header line when it is known, so the pack name always names its server.
		int headerLines = origin.isBlank() ? 2 : 3;
		DialogLayout layout = layoutDialogWithActions(28, headerLines * LINE_HEIGHT, body.size() * LINE_HEIGHT, 0, rowArray);
		this.titleTop = layout.titleTop();
		List<AbstractWidget> buttons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray);
		setTooltip(buttons.get(buttons.size() - 2), ChangeSummary.diffLegend());
		this.addCenteredScrollBody(PANEL_WIDTH, layout.column().bodyTop(), layout.column().bodyBottom(), body);
	}

	private List<ActionRow> buildRows() {
		List<ActionRow> rows = new ArrayList<>();
		if (Changelogs.hasNotes(preview.journal()))
			rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.management.history"), button -> openHistory())));
		if (canCustomize()) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(PackConfirmCopy.customizeLabel(), button -> customize())));
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.back"), button -> cancel()),
				optionalAction(VersionedText.translatable("automodpack.browser.reviewFiles"), button -> openFiles()),
				primaryAction(VersionedText.translatable(actionKey(mode)), button -> continueUpdate())));
		return rows;
	}

	private boolean canCustomize() {
		// Customize re-enters the review flow, so it only exists when a live review stands behind this preview; a switch
		// or removal preview has an idle confirmation and reselecting there would dead-end on the missing connection.
		if (mode != UpdatePreview.Mode.UPDATE || actions == null || !actions.reviewPreviewing().getAsBoolean()) return false;
		try {
			return PackConfirmCopy.canCustomize(target.manifest());
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	private List<MutableComponent> buildBodyLines() {
		int wrapWidth = Math.max(1, panelWidth(PANEL_WIDTH) - 20);
		List<MutableComponent> lines = new ArrayList<>();
		if (mode == UpdatePreview.Mode.ROLLBACK) {
			lines.add(VersionedText.translatable("automodpack.update.rollbackDetaches").withStyle(ChatFormatting.YELLOW));
			lines.add(blankLine());
		}
		String patchNotes = preview.latestPatchNotes();
		if (!patchNotes.isBlank()) {
			lines.add(VersionedText.translatable("automodpack.patchNotes.latest").withStyle(ChatFormatting.GRAY));
			lines.addAll(wrapParagraph(this.font, patchNotes, wrapWidth));
			lines.add(blankLine());
		}
		UpdatePreview.GroupConsequences groups = preview.groupConsequences();
		if (!groups.staleGroups().isEmpty()) lines.add(VersionedText.translatable("automodpack.update.staleSelection").withStyle(ChatFormatting.RED));
		else lines.add(UiFormat.plural(groups.resolvedGroups().size(), "automodpack.update.groupsSelected").withStyle(ChatFormatting.GREEN));
		if (!preview.conflicts().isEmpty())
			lines.add(UiFormat.plural(preview.conflicts().size(), "automodpack.browser.conflicts").withStyle(ChatFormatting.RED));
		ChangeSet.Summary summary = changes.summary();
		lines.addAll(ChangeSummary.diffLines(summary.addedFiles(), summary.modifiedFiles(), summary.removedFiles(), summary.preservedFiles(), summary.unsafeFiles()));
		if (mode == UpdatePreview.Mode.UPDATE && (summary.addedFiles() > 0 || summary.modifiedFiles() > 0 || preview.uncachedAcquisitionBytes() > 0))
			lines.add(VersionedText.translatable("automodpack.browser.downloadSummary", UiFormat.formatSize(preview.uncachedAcquisitionBytes()), UiFormat.formatSize(preview.addedBytes() + preview.changedBytes()))
					.withStyle(ChatFormatting.GRAY));
		long otherEffects = changes.effects().stream().filter(effect -> !"restart".equals(effect.category())).count();
		if (otherEffects > 0) lines.add(VersionedText.translatable("automodpack.summary.otherEffects", otherEffects).withStyle(ChatFormatting.YELLOW));
		lines.add(blankLine());
		lines.add(preview.requiresRestart()
				? VersionedText.translatable("automodpack.summary.restartRequired").withStyle(ChatFormatting.YELLOW)
				: VersionedText.translatable("automodpack.summary.noRestart").withStyle(ChatFormatting.GREEN));
		return lines;
	}

	private void continueUpdate() {
		if (finished && (actions == null || !actions.reviewActive().getAsBoolean() || actions.cancelledByPlayer().getAsBoolean())) return;
		finished = true;
		ScreenManager.waiting(actions == null ? null : actions.cancelFromPlayer());
		continueAction.run();
	}

	private void cancel() {
		if (!finished) cancelAction.run();
		finished = true;
		ScreenImpl.setScreen(parent);
	}

	private void customize() {
		if (finished || actions == null) return;
		Consumer<SelectionIntent> action = intent -> {
			try {
				actions.reselectAndPreview().accept(intent);
			} catch (RuntimeException e) {
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.MULTIPLAYER, null));
			}
		};
		ScreenImpl.setScreen(new ModpackSelectionScreen(this, target, actions, action));
	}

	private void openFiles() {
		ScreenImpl.setScreen(new ChangeBrowserScreen(this,
				VersionedText.translatable("automodpack.browser.previewTitle"), VersionedText.translatable(reviewKey(mode)), changes, preview.featureNames(), null, List.of(), preview.uncachedAcquisitionBytes(),
				""));
	}

	private void openHistory() {
		// The journal is the server's timeline, so no entry of a pending preview is installed yet.
		ScreenImpl.setScreen(new ContentHistoryScreen(this, new HistoryViewRequest(preview.journal(), -1, modpackName, () -> {})));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String title = VersionedText.translatable(modpackName.isBlank() ? titleKey(mode) : namedTitleKey(mode), modpackName).getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.BOLD), this.width / 2, titleTop,
				TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable(reviewKey(mode)).withStyle(ChatFormatting.GRAY), this.width / 2, titleTop + LINE_HEIGHT, TextColors.WHITE);
		if (!origin.isBlank())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.packDetails.server", origin).withStyle(ChatFormatting.GRAY), this.width / 2, titleTop + 2 * LINE_HEIGHT,
					TextColors.WHITE);
	}

	static String titleKey(UpdatePreview.Mode mode) {
		return switch (mode) {
			case UPDATE -> "automodpack.update.title";
			case DEACTIVATION -> "automodpack.update.deactivationTitle";
			case REMOVAL -> "automodpack.update.removalTitle";
			case ROLLBACK -> "automodpack.update.rollbackTitle";
		};
	}

	private static String namedTitleKey(UpdatePreview.Mode mode) {
		return switch (mode) {
			case UPDATE -> "automodpack.update.updateNamed";
			case DEACTIVATION -> "automodpack.update.deactivateNamed";
			case REMOVAL -> "automodpack.update.removeNamed";
			case ROLLBACK -> "automodpack.update.rollbackNamed";
		};
	}

	static String reviewKey(UpdatePreview.Mode mode) {
		return switch (mode) {
			case UPDATE -> "automodpack.update.reviewUpdate";
			case DEACTIVATION -> "automodpack.update.reviewDeactivation";
			case REMOVAL -> "automodpack.update.reviewRemoval";
			case ROLLBACK -> "automodpack.update.reviewRollback";
		};
	}

	static String actionKey(UpdatePreview.Mode mode) {
		return switch (mode) {
			case UPDATE -> "automodpack.update.apply";
			case DEACTIVATION -> "automodpack.update.deactivate";
			case REMOVAL -> "automodpack.update.remove";
			case ROLLBACK -> "automodpack.update.rollbackApply";
		};
	}

	@Override
	public void tick() {
		super.tick();
		if (actions == null) return;
		if (finished && actions.reviewActive().getAsBoolean() && !actions.cancelledByPlayer().getAsBoolean()) finished = false;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		cancel();
		return false;
	}
}
