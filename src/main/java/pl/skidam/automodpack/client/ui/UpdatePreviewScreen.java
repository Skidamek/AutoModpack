package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.ListEntry;
import pl.skidam.automodpack.client.ui.widget.ListEntryWidget;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;

public final class UpdatePreviewScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private final Screen parent;
	private final UpdatePreview preview;
	private final String modpackName;
	private final boolean removal;
	private final boolean returnToSelection;
	private final Runnable continueAction;
	private final Runnable cancelAction;
	private final Map<UpdatePlan.FileKey, List<String>> mainPageUrls;
	private ListEntryWidget changesList;
	private Button openMainPageButton;
	private boolean finished;
	private Layout layout;

	public UpdatePreviewScreen(Screen parent, UpdatePreview preview, String modpackName, boolean removal, boolean returnToSelection, Runnable continueAction,
			Runnable cancelAction, Map<UpdatePlan.FileKey, List<String>> mainPageUrls) {
		super(VersionedText.literal(removal ? "ModpackRemovalScreen" : "UpdatePreviewScreen"));
		this.parent = parent;
		this.preview = preview;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.removal = removal;
		this.returnToSelection = returnToSelection;
		this.continueAction = continueAction;
		this.cancelAction = cancelAction;
		this.mainPageUrls = Map.copyOf(mainPageUrls);
	}

	@Override
	protected void init() {
		super.init();
		this.layout = layout();
		int actionWidth = actionButtonWidth(310, 3);
		this.changesList = new ListEntryWidget(rows(), this.minecraft, this.width, this.height, listTop(), listBottom(), 18);
		this.addRenderableWidget(this.changesList);

		boolean hasPatchNotes = GenerationPatchNoteHistory.containsNotes(preview.patchNotesHistory());
		if (hasPatchNotes) this.addRenderableWidget(buttonWidget(this.width / 2 - 75, this.layout.patchNotesButtonY(), 150, 20,
				VersionedText.translatable("automodpack.patchNotes.all"), button -> openPatchNotes()));
		this.openMainPageButton = buttonWidget(actionButtonX(310, 3, 1), this.height - 28, actionWidth, 20,
				VersionedText.literal("Project page"), button -> openSelectedPage());
		this.openMainPageButton.active = false;
		this.addRenderableWidget(this.openMainPageButton);
		this.addRenderableWidget(buttonWidget(actionButtonX(310, 3, 0), this.height - 28, actionWidth, 20,
				returnToSelection ? VersionedText.translatable("automodpack.back") : VersionedText.translatable("automodpack.cancel"), button -> cancel()));
		this.addRenderableWidget(buttonWidget(actionButtonX(310, 3, 2), this.height - 28, actionWidth, 20,
				VersionedText.literal(removal ? "Remove" : "Update"), button -> continueUpdate()));
	}

	private int listBottom() {
		return this.layout.listBottom();
	}

	private int listTop() {
		return this.layout.listTop();
	}

	private Layout layout() {
		boolean hasPatchNotes = GenerationPatchNoteHistory.containsNotes(preview.patchNotesHistory());
		boolean hasOtherEffects = preview.summary().otherEffects() > 0;
		int actionY = this.height - 28;
		int patchNotesButtonY = hasPatchNotes ? actionY - 24 : -1;
		int restartY = (hasPatchNotes ? patchNotesButtonY : actionY) - 18;
		int otherEffectsY = hasOtherEffects ? restartY - 12 : -1;
		int changedY = (hasOtherEffects ? otherEffectsY : restartY) - 12;
		int removedY = changedY - 12;
		int footerTop = removedY - 6;
		int latestNoteLines = this.height <= 260 ? 1 : 2;
		int latestNoteY = 54;
		int selectionY = latestNoteY + latestNoteLines * 12 + 2;
		int conflictY = preview.conflicts().isEmpty() ? -1 : selectionY + 12;
		int listTop = conflictY < 0 ? selectionY + 12 : conflictY + 12;
		return new Layout(listTop, Math.max(listTop + 18, footerTop), patchNotesButtonY, latestNoteLines, changedY, removedY, otherEffectsY, restartY, selectionY, conflictY);
	}

	private record Layout(int listTop, int listBottom, int patchNotesButtonY, int latestNoteLines, int changedY, int removedY,
			int otherEffectsY, int restartY, int selectionY, int conflictY) {}

	private List<ListEntryWidget.Row> rows() {
		List<ListEntryWidget.Row> rows = new ArrayList<>();
		for (UpdatePlan.Conflict conflict : preview.conflicts()) {
			String ids = String.join(", ", conflict.modIds());
			String action = conflict.action() == UpdatePlan.ConflictAction.QUARANTINE
					? "Local mod " + ids + " will be moved to AutoModpack quarantine (recoverable)."
					: "AutoModpack-owned duplicate " + ids + " will be removed.";
			rows.add(new ListEntryWidget.Row(VersionedText.literal(truncateToWidth(this.font, "! " + action, panelWidth(PANEL_WIDTH) - 8)).withStyle(ChatFormatting.YELLOW), null));
		}
		for (UpdatePreview.Entry entry : preview.displayEntries()) {
			UpdatePlan.FileKey file = new UpdatePlan.FileKey(entry.root(), entry.relativePath());
			String text = entry.kind().displaySymbol() + UiFormat.changePath(file) + "  (" + UiFormat.formatSize(entry.size()) + ")";
			ChatFormatting color = entry.kind().isPreserved() ? ChatFormatting.GRAY : entry.kind() == UpdatePreview.Kind.UNSAFE ? ChatFormatting.RED : ChatFormatting.WHITE;
			rows.add(new ListEntryWidget.Row(VersionedText.literal(text).withStyle(color), firstUrl(mainPageUrls.get(file))));
		}
		if (rows.isEmpty()) rows.add(new ListEntryWidget.Row(VersionedText.literal("No file changes are required.").withStyle(ChatFormatting.GRAY), null));
		return rows;
	}

	private static String firstUrl(List<String> urls) {
		return urls == null || urls.isEmpty() ? null : urls.get(0);
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

	private void openSelectedPage() {
		ListEntry selected = changesList.getSelected();
		if (selected != null && selected.getMainPageUrl() != null) Util.getPlatform().openUri(selected.getMainPageUrl());
	}

	private void openPatchNotes() {
		ScreenImpl.setScreen(new PatchNotesHistoryScreen(this, preview.patchNotesHistory(), modpackName));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		/*? if <26.1 {*/
		/*this.changesList.render(matrices.getContext(), mouseX, mouseY, delta);
		*//*?}*/
		ListEntry selected = changesList.getSelected();
		this.openMainPageButton.active = selected != null && selected.getMainPageUrl() != null;

		String title = removal
				? (modpackName.isBlank() ? "Remove modpack" : "Remove " + modpackName)
				: (modpackName.isBlank() ? "Review update" : "Update " + modpackName);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.BOLD), this.width / 2, 14,
				TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(removal ? "Review the files that will be removed." : "Review the changes before updating.").withStyle(ChatFormatting.GRAY),
				this.width / 2, 29, TextColors.WHITE);
		Layout layout = this.layout;
		String patchNotes = preview.latestPatchNotes();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.patchNotes.latest").withStyle(ChatFormatting.YELLOW), this.width / 2, 42, TextColors.WHITE);
		List<String> patchNoteLines = patchNotes.isBlank() ? List.of(VersionedText.translatable("automodpack.patchNotes.none").getString())
				: wrapToWidth(this.font, patchNotes, Math.max(1, panelWidth(PANEL_WIDTH) - 20), layout.latestNoteLines());
		for (int index = 0; index < patchNoteLines.size(); index++)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(patchNoteLines.get(index)).withStyle(ChatFormatting.WHITE), this.width / 2, 54 + index * 12, TextColors.WHITE);

		UpdatePreview.GroupConsequences groups = preview.groupConsequences();
		boolean staleSelection = !groups.staleGroups().isEmpty();
		String selection = staleSelection ? "Some previously selected content is no longer available." : groups.resolvedGroups().size() + " content groups selected";
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, selection, this.width - 20)).withStyle(staleSelection ? ChatFormatting.RED : ChatFormatting.GRAY), this.width / 2, layout.selectionY(),
				TextColors.WHITE);
		if (!preview.conflicts().isEmpty()) {
			String conflictText = preview.conflicts().stream().anyMatch(conflict -> conflict.action() == UpdatePlan.ConflictAction.QUARANTINE)
					? "A local same-ID mod will be quarantined and can be recovered from AutoModpack's client/quarantine folder."
					: "An AutoModpack-owned duplicate will be removed as part of this update.";
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, conflictText, this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2, layout.conflictY(),
					TextColors.WHITE);
		}

		UpdatePreview.Summary summary = preview.summary();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.summary.filesChanged", summary.changedFiles()).withStyle(ChatFormatting.GRAY), this.width / 2,
				layout.changedY(), TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.summary.filesRemoved", summary.removedFiles()).withStyle(ChatFormatting.GRAY), this.width / 2,
				layout.removedY(), TextColors.WHITE);
		String otherEffects = summary.otherEffects() == 0 ? "" : VersionedText.translatable("automodpack.summary.otherEffects", summary.otherEffects()).getString();
		if (!otherEffects.isBlank()) drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, otherEffects, this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2,
				layout.otherEffectsY(), TextColors.WHITE);
		String restart = preview.restartReasons().isEmpty() ? VersionedText.translatable("automodpack.summary.noRestart").getString() : VersionedText.translatable("automodpack.summary.restartRequired").getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(restart).withStyle(ChatFormatting.GRAY), this.width / 2, layout.restartY(), TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		cancel();
		return false;
	}
}
