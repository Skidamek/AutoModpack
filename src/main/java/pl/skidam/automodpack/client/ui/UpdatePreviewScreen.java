package pl.skidam.automodpack.client.ui;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;

public final class UpdatePreviewScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private static final int CONTENT_PADDING = 16;
	private final Screen parent;
	private final UpdatePreview preview;
	private final String modpackName;
	private final boolean removal;
	private final boolean returnToSelection;
	private final boolean finalVerification;
	private final Runnable continueAction;
	private final Runnable cancelAction;
	private Button previousButton;
	private Button nextButton;
	private int page;
	private boolean finished;

	public UpdatePreviewScreen(Screen parent, UpdatePreview preview, String modpackName, boolean removal, boolean returnToSelection, boolean finalVerification, Runnable continueAction,
			Runnable cancelAction) {
		super(VersionedText.literal(removal ? "ModpackRemovalScreen" : "UpdatePreviewScreen"));
		this.parent = parent;
		this.preview = preview;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.removal = removal;
		this.returnToSelection = returnToSelection;
		this.finalVerification = finalVerification;
		this.continueAction = continueAction;
		this.cancelAction = cancelAction;
	}

	@Override
	protected void init() {
		super.init();
		int pageCount = pageCount();
		int navigationY = this.height - 54;
		int left = panelLeft(310);
		int rowWidth = panelWidth(310);
		int gap = 10;
		int actionWidth = (rowWidth - gap) / 2;
		this.previousButton = buttonWidget(left, navigationY, 70, 20, VersionedText.literal("< Prev"), button -> changePage(-1));
		this.nextButton = buttonWidget(left + rowWidth - 70, navigationY, 70, 20, VersionedText.literal("Next >"), button -> changePage(1));
		this.previousButton.active = page > 0;
		this.nextButton.active = page + 1 < pageCount;
		if (pageCount > 1) {
			this.addRenderableWidget(this.previousButton);
			this.addRenderableWidget(this.nextButton);
		}
		this.addRenderableWidget(buttonWidget(left, this.height - 28, actionWidth, 20,
				returnToSelection ? VersionedText.translatable("automodpack.back") : VersionedText.translatable("automodpack.cancel"), button -> cancel()));
		this.addRenderableWidget(buttonWidget(left + actionWidth + gap, this.height - 28, actionWidth, 20,
				VersionedText.literal(removal ? "Remove" : finalVerification ? "Apply" : "Continue").withStyle(ChatFormatting.BOLD), button -> continueUpdate()));
		if (GenerationPatchNoteHistory.containsNotes(preview.patchNotesHistory()))
			this.addRenderableWidget(buttonWidget(this.width / 2 - 75, navigationY, 150, 20, VersionedText.literal("All patch notes"), button -> openPatchNotes()));
	}

	private int pageCount() {
		int pageSize = pageSize();
		return Math.max(1, (preview.entries().size() + pageSize - 1) / pageSize);
	}

	private int pageSize() {
		return Math.max(1, (summaryY() - entryTop() - 6) / 16);
	}

	private int entryTop() {
		return headerBottom() + 10;
	}

	private int headerBottom() {
		UpdatePreview.GroupConsequences groups = preview.groupConsequences();
		return groups.staleTags().isEmpty() && groups.staleGroups().isEmpty() ? 78 : 90;
	}

	private int summaryY() {
		return this.height - 110;
	}

	private int contentLeft() {
		return panelLeft(PANEL_WIDTH) + CONTENT_PADDING;
	}

	private int contentWidth() {
		return Math.max(1, panelWidth(PANEL_WIDTH) - CONTENT_PADDING * 2);
	}

	private List<String> patchNoteLines() {
		return preview.patchNotes().isBlank()
				? List.of("No patch notes published.")
				: wrapToWidth(this.font, preview.patchNotes(), contentWidth(), 1);
	}

	private void changePage(int amount) {
		page = Math.max(0, Math.min(pageCount() - 1, page + amount));
		previousButton.active = page > 0;
		nextButton.active = page + 1 < pageCount();
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

	private void openPatchNotes() {
		ScreenImpl.setScreen(new PatchNotesHistoryScreen(this, preview.patchNotesHistory(), modpackName));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		int left = contentLeft();
		int contentWidth = contentWidth();
		String title = removal
				? (modpackName.isBlank() ? "Remove modpack" : "Remove " + modpackName)
				: (modpackName.isBlank() ? "Update preview" : modpackName + " update preview");
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, contentWidth)).withStyle(ChatFormatting.BOLD), left, 16, TextColors.WHITE);
		String subtitle = removal ? "Review before removal" : finalVerification ? "Files are ready. Review before applying" : "Review before preparing files";
		drawTextWithShadow(matrices, this.font, VersionedText.literal(subtitle).withStyle(ChatFormatting.AQUA), left, 31,
				TextColors.CYAN);
		int headerY = 50;
		List<String> notes = patchNoteLines();
		drawTextWithShadow(matrices, this.font, VersionedText.literal("Patch notes:").withStyle(ChatFormatting.YELLOW), left, headerY, TextColors.WHITE);
		for (String note : notes) {
			headerY += 12;
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, note, contentWidth)).withStyle(ChatFormatting.WHITE), left, headerY, TextColors.WHITE);
		}

		UpdatePreview.GroupConsequences groups = preview.groupConsequences();
		headerY += 14;
		drawTextWithShadow(matrices, this.font,
				VersionedText.literal(truncateToWidth(this.font, "Selected content: " + groups.resolvedGroups().size() + " groups", contentWidth)).withStyle(ChatFormatting.GRAY), left, headerY,
				TextColors.WHITE);
		if (!groups.staleTags().isEmpty() || !groups.staleGroups().isEmpty()) {
			headerY += 12;
			String stale = "Unavailable old choices: tags=" + join(groups.staleTags()) + " groups=" + join(groups.staleGroups());
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, stale, contentWidth)).withStyle(ChatFormatting.RED), left, headerY,
					TextColors.WHITE);
		}
		List<UpdatePreview.Entry> entries = preview.entries();
		int pageSize = pageSize();
		int start = page * pageSize;
		int end = Math.min(entries.size(), start + pageSize);
		for (int index = start; index < end; index++) {
			UpdatePreview.Entry entry = entries.get(index);
			int y = entryTop() + (index - start) * 16;
			KindPresentation kind = kindPresentation(entry.kind());
			String line = kind.label() + "  " + rootText(entry.root()) + ":/" + entry.relativePath() + "  (" + UiFormat.formatSize(entry.size()) + ")";
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, line, contentWidth)).withStyle(kind.color()), left, y, TextColors.WHITE);
		}

		if (entries.isEmpty()) {
			drawTextWithShadow(matrices, this.font, VersionedText.literal("No file changes are required.").withStyle(ChatFormatting.GREEN), left, entryTop(),
					TextColors.WHITE);
		}
		long updatedFiles = entries.stream().filter(entry -> entry.kind() == UpdatePreview.Kind.ADDED || entry.kind() == UpdatePreview.Kind.CHANGED
				|| entry.kind() == UpdatePreview.Kind.RESTORED_BASELINE).count();
		String changeSummary = "Changes: added " + UiFormat.formatSize(preview.addedBytes()) + "  changed " + UiFormat.formatSize(preview.changedBytes()) + "  removed "
				+ UiFormat.formatSize(preview.removedBytes()) + "  preserved " + UiFormat.formatSize(preview.preservedBytes());
		String acquisitionSummary = (finalVerification ? "Ready: " : "File updates: ") + updatedFiles + (finalVerification ? " file updates" : "");
		String restartSummary = "Restart: " + restartReasons();
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, changeSummary, contentWidth)).withStyle(ChatFormatting.YELLOW), left, summaryY(), TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, acquisitionSummary, contentWidth)).withStyle(ChatFormatting.GREEN), left, summaryY() + 12,
				TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, restartSummary, contentWidth)).withStyle(ChatFormatting.GRAY), left, summaryY() + 24,
				TextColors.WHITE);
		if (pageCount() > 1) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal((page + 1) + " / " + pageCount()).withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 66,
					TextColors.WHITE);
		}
	}

	private String join(Iterable<String> values) {
		StringBuilder joined = new StringBuilder();
		for (String value : values) {
			if (joined.length() > 0) joined.append(", ");
			joined.append(value);
		}
		String result = joined.length() == 0 ? "none" : joined.toString();
		return truncateToWidth(this.font, result, contentWidth());
	}

	private static String rootText(UpdatePlan.Root root) {
		return UiFormat.rootLabel(root);
	}

	private String restartReasons() {
		if (preview.plan().restartReasons().isEmpty()) return "no restart expected";
		return String.join(", ", preview.plan().restartReasons().stream().map(UpdatePreviewScreen::restartReason).toList());
	}

	private static String restartReason(UpdatePlan.RestartReason reason) {
		return switch (reason) {
			case REMOVED_NON_MODPACK_FILES -> "removed old files";
			case CORRECTED_FILE_LOCATIONS -> "corrected file locations";
			case FIXED_NESTED_MODS -> "fixed nested mods";
			case REMOVED_DUPLICATE_MODS -> "removed duplicate mods";
			case REMOVED_STANDARD_MODS -> "removed standard mods";
			case APPLIED_SERVER_DELETIONS -> "applied server deletions";
			case CHANGED_LOADER_VERSION -> "changed loader version";
			case CHANGED_GROUP_SELECTION -> "changed group selection";
			case SELECTED_MODPACK -> "selected modpack";
		};
	}

	private static KindPresentation kindPresentation(UpdatePreview.Kind kind) {
		return switch (kind) {
			case ADDED -> new KindPresentation("Added", ChatFormatting.GREEN);
			case CHANGED -> new KindPresentation("Changed", ChatFormatting.GREEN);
			case REMOVED -> new KindPresentation("Removed", ChatFormatting.YELLOW);
			case PRESERVED_CAS -> new KindPresentation("Preserved in CAS", ChatFormatting.YELLOW);
			case PRESERVED_CHANGED -> new KindPresentation("Preserved changed", ChatFormatting.RED);
			case PRESERVED_UNAVAILABLE -> new KindPresentation("Preserved unavailable", ChatFormatting.RED);
			case PRESERVED_OUTSIDE -> new KindPresentation("Preserved outside roots", ChatFormatting.RED);
			case UNSAFE -> new KindPresentation("Unsafe file type", ChatFormatting.RED);
			case RESTORED_BASELINE -> new KindPresentation("Restored baseline", ChatFormatting.GREEN);
		};
	}

	private record KindPresentation(String label, ChatFormatting color) {}

	@Override
	public boolean shouldCloseOnEsc() {
		cancel();
		return false;
	}
}
