package pl.skidam.automodpack.client.ui;

import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;

public final class UpdatePreviewScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private static final int CONTENT_PADDING = 16;
	private final Screen parent;
	private final UpdatePreview preview;
	private final String modpackName;
	private final boolean removal;
	private final Runnable continueAction;
	private final Runnable cancelAction;
	private Button previousButton;
	private Button nextButton;
	private int page;
	private boolean finished;

	public UpdatePreviewScreen(Screen parent, UpdatePreview preview, String modpackName, Runnable continueAction, Runnable cancelAction) {
		this(parent, preview, modpackName, false, continueAction, cancelAction);
	}

	public UpdatePreviewScreen(Screen parent, UpdatePreview preview, String modpackName, boolean removal, Runnable continueAction, Runnable cancelAction) {
		super(VersionedText.literal(removal ? "ModpackRemovalScreen" : "UpdatePreviewScreen"));
		this.parent = parent;
		this.preview = preview;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.removal = removal;
		this.continueAction = continueAction;
		this.cancelAction = cancelAction;
	}

	@Override
	protected void init() {
		super.init();
		int pageCount = pageCount();
		int navigationY = this.height - 48;
		int left = Math.max(5, (this.width - 310) / 2);
		this.previousButton = buttonWidget(left, navigationY, 70, 20, VersionedText.literal("< Prev"), button -> changePage(-1));
		this.nextButton = buttonWidget(left + 240, navigationY, 70, 20, VersionedText.literal("Next >"), button -> changePage(1));
		this.previousButton.active = page > 0;
		this.nextButton.active = page + 1 < pageCount;
		this.addRenderableWidget(this.previousButton);
		this.addRenderableWidget(this.nextButton);
		this.addRenderableWidget(buttonWidget(left + 80, navigationY, 75, 20, VersionedText.translatable("automodpack.cancel"), button -> cancel()));
		this.addRenderableWidget(buttonWidget(left + 165, navigationY, 75, 20,
				VersionedText.literal(removal ? "Remove" : "Continue").withStyle(ChatFormatting.BOLD), button -> continueUpdate()));
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
		return 86 + patchNoteLines().size() * 12;
	}

	private int summaryY() {
		return panelBottom() - 48;
	}

	private int panelBottom() {
		return Math.max(10, this.height - 58);
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
				: wrapToWidth(this.font, preview.patchNotes(), contentWidth(), 2);
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
		ScreenImpl.setScreen(parent instanceof PreparingScreen ? null : parent);
		cancelAction.run();
	}

	@Override
	public void versionedBackground(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawPanel(matrices, PANEL_WIDTH, 8, panelBottom());
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		int left = contentLeft();
		int contentWidth = contentWidth();
		String title = removal
				? (modpackName.isBlank() ? "Remove modpack" : "Remove " + modpackName)
				: (modpackName.isBlank() ? "Update preview" : modpackName + " update preview");
		drawTextWithShadow(matrices, this.font, VersionedText.literal(title).withStyle(ChatFormatting.BOLD), left, 16, TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, VersionedText.literal(removal ? "Review before removal" : "Review before download").withStyle(ChatFormatting.AQUA), left, 31,
				TextColors.PANEL_ACCENT);
		drawDivider(matrices, PANEL_WIDTH, 42);

		int headerY = 50;
		List<String> notes = patchNoteLines();
		drawTextWithShadow(matrices, this.font, VersionedText.literal("Patch notes:").withStyle(ChatFormatting.YELLOW), left, headerY, TextColors.WHITE);
		for (String note : notes) {
			headerY += 12;
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, note, contentWidth)).withStyle(ChatFormatting.WHITE), left, headerY, TextColors.WHITE);
		}

		UpdatePreview.GroupConsequences groups = preview.groupConsequences();
		headerY += 14;
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Tags: " + join(groups.explicitTags()), contentWidth)).withStyle(ChatFormatting.GRAY), left, headerY,
				TextColors.WHITE);
		headerY += 12;
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Groups: " + join(groups.resolvedGroups()), contentWidth)).withStyle(ChatFormatting.GRAY), left,
				headerY, TextColors.WHITE);
		headerY += 12;
		String stale = groups.staleTags().isEmpty() && groups.staleGroups().isEmpty()
				? "none"
				: "tags=" + join(groups.staleTags()) + " groups=" + join(groups.staleGroups());
		drawTextWithShadow(matrices, this.font,
				VersionedText.literal(truncateToWidth(this.font, "Stale choices: " + stale, contentWidth)).withStyle(groups.staleTags().isEmpty() && groups.staleGroups().isEmpty()
						? ChatFormatting.GRAY
						: ChatFormatting.RED),
				left, headerY, TextColors.WHITE);
		drawDivider(matrices, PANEL_WIDTH, entryTop() - 6);

		List<UpdatePreview.Entry> entries = preview.entries();
		int pageSize = pageSize();
		int start = page * pageSize;
		int end = Math.min(entries.size(), start + pageSize);
		for (int index = start; index < end; index++) {
			UpdatePreview.Entry entry = entries.get(index);
			int y = entryTop() + (index - start) * 16;
			String line = kindText(entry.kind()) + "  " + rootText(entry.root()) + ":/" + entry.relativePath() + "  (" + formatSize(entry.size()) + ")";
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, line, contentWidth)).withStyle(color(entry.kind())), left, y, TextColors.WHITE);
		}

		if (entries.isEmpty()) {
			drawTextWithShadow(matrices, this.font, VersionedText.literal("No file changes are required.").withStyle(ChatFormatting.GREEN), left, entryTop(),
					TextColors.WHITE);
		}
		long acquisitionBytes = entries.stream().filter(entry -> entry.kind() == UpdatePreview.Kind.ADDED || entry.kind() == UpdatePreview.Kind.CHANGED
				|| entry.kind() == UpdatePreview.Kind.RESTORED_BASELINE).mapToLong(UpdatePreview.Entry::size).sum();
		long acquisitionFiles = entries.stream().filter(entry -> entry.kind() == UpdatePreview.Kind.ADDED || entry.kind() == UpdatePreview.Kind.CHANGED
				|| entry.kind() == UpdatePreview.Kind.RESTORED_BASELINE).count();
		String changeSummary = "Changes: added " + formatSize(preview.addedBytes()) + "  changed " + formatSize(preview.changedBytes()) + "  removed "
				+ formatSize(preview.removedBytes()) + "  preserved " + formatSize(preview.preservedBytes());
		String acquisitionSummary = "Download: " + acquisitionFiles + " files, " + formatSize(acquisitionBytes);
		String restartSummary = "Restart: " + restartReasons();
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, changeSummary, contentWidth)).withStyle(ChatFormatting.YELLOW), left, summaryY(), TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, acquisitionSummary, contentWidth)).withStyle(ChatFormatting.GREEN), left, summaryY() + 12,
				TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, restartSummary, contentWidth)).withStyle(ChatFormatting.GRAY), left, summaryY() + 24,
				TextColors.WHITE);
		if (pageCount() > 1) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal((page + 1) + " / " + pageCount()).withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 27,
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
		return switch (root) {
			case PROJECTION -> "active";
			case OVERLAY -> "editable overlay";
			case GAME_DIR -> "game";
			case STORE_DIR -> "cas";
			case AUTOMODPACK_DIR -> "automodpack";
		};
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

	private static String kindText(UpdatePreview.Kind kind) {
		return switch (kind) {
			case ADDED -> "Added";
			case CHANGED -> "Changed";
			case REMOVED -> "Removed";
			case PRESERVED_CAS -> "Preserved in CAS";
			case PRESERVED_CHANGED -> "Preserved changed";
			case PRESERVED_UNAVAILABLE -> "Preserved unavailable";
			case PRESERVED_OUTSIDE -> "Preserved outside roots";
			case UNSAFE -> "Unsafe file type";
			case RESTORED_BASELINE -> "Restored baseline";
		};
	}

	private static ChatFormatting color(UpdatePreview.Kind kind) {
		return switch (kind) {
			case ADDED, CHANGED, RESTORED_BASELINE -> ChatFormatting.GREEN;
			case REMOVED, PRESERVED_CAS -> ChatFormatting.YELLOW;
			case PRESERVED_CHANGED, PRESERVED_UNAVAILABLE, PRESERVED_OUTSIDE, UNSAFE -> ChatFormatting.RED;
		};
	}

	private static String formatSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return (bytes / 1024) + " KiB";
		return (bytes / (1024 * 1024)) + " MiB";
	}

	@Override
	public boolean shouldCloseOnEsc() {
		cancel();
		return false;
	}
}
