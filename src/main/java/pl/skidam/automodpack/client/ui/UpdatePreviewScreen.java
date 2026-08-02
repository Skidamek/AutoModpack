package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;

public final class UpdatePreviewScreen extends VersionedScreen {
	private static final int ROWS_PER_PAGE = 5;

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
		int left = this.width / 2 - 155;
		this.previousButton = buttonWidget(left, navigationY, 70, 20, VersionedText.literal("< Prev"), button -> changePage(-1));
		this.nextButton = buttonWidget(left + 240, navigationY, 70, 20, VersionedText.literal("Next >"), button -> changePage(1));
		this.previousButton.active = page > 0;
		this.nextButton.active = page + 1 < pageCount;
		this.addRenderableWidget(this.previousButton);
		this.addRenderableWidget(this.nextButton);
		this.addRenderableWidget(buttonWidget(left + 80, navigationY, 75, 20, VersionedText.literal("Cancel"), button -> cancel()));
		this.addRenderableWidget(buttonWidget(left + 165, navigationY, 75, 20,
				VersionedText.literal(removal ? "Remove" : "Continue").withStyle(ChatFormatting.BOLD), button -> continueUpdate()));
	}

	private int pageCount() {
		return Math.max(1, (preview.entries().size() + ROWS_PER_PAGE - 1) / ROWS_PER_PAGE);
	}

	private void changePage(int amount) {
		page = Math.max(0, Math.min(pageCount() - 1, page + amount));
		previousButton.active = page > 0;
		nextButton.active = page + 1 < pageCount();
	}

	private void continueUpdate() {
		if (finished) return;
		finished = true;
		continueAction.run();
		this.minecraft.gui.setScreen(new PreparingScreen());
	}

	private void cancel() {
		if (finished) return;
		finished = true;
		this.minecraft.gui.setScreen(parent);
		cancelAction.run();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String title = removal
				? (modpackName.isBlank() ? "Remove modpack" : "Remove " + modpackName)
				: (modpackName.isBlank() ? "Update preview" : modpackName + " update preview");
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(title).withStyle(ChatFormatting.BOLD), this.width / 2, 10, TextColors.WHITE);

		int headerY = 24;
		List<String> notes = preview.patchNotes().isBlank() ? List.of("No patch notes published.") : limitedLines(preview.patchNotes(), 2);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Patch notes:").withStyle(ChatFormatting.YELLOW), this.width / 2, headerY, TextColors.WHITE);
		for (String note : notes) {
			headerY += 12;
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(note).withStyle(ChatFormatting.WHITE), this.width / 2, headerY, TextColors.WHITE);
		}

		UpdatePreview.GroupConsequences groups = preview.groupConsequences();
		headerY += 15;
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Tags: " + join(groups.explicitTags())).withStyle(ChatFormatting.GRAY), this.width / 2, headerY, TextColors.WHITE);
		headerY += 12;
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Groups: " + join(groups.resolvedGroups())).withStyle(ChatFormatting.GRAY), this.width / 2, headerY, TextColors.WHITE);
		headerY += 12;
		String stale = groups.staleTags().isEmpty() && groups.staleGroups().isEmpty()
				? "none"
				: "tags=" + join(groups.staleTags()) + " groups=" + join(groups.staleGroups());
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Stale choices: " + stale).withStyle(groups.staleTags().isEmpty() && groups.staleGroups().isEmpty()
				? ChatFormatting.GRAY
				: ChatFormatting.RED), this.width / 2, headerY, TextColors.WHITE);

		List<UpdatePreview.Entry> entries = preview.entries();
		int start = page * ROWS_PER_PAGE;
		int end = Math.min(entries.size(), start + ROWS_PER_PAGE);
		for (int index = start; index < end; index++) {
			UpdatePreview.Entry entry = entries.get(index);
			int y = 100 + (index - start) * 18;
			String line = kindText(entry.kind()) + "  " + rootText(entry.root()) + ":/" + shortPath(entry.relativePath()) + "  (" + formatSize(entry.size()) + ")";
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(color(entry.kind())), this.width / 2, y, TextColors.WHITE);
		}

		if (entries.isEmpty()) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("No file changes are required.").withStyle(ChatFormatting.GREEN), this.width / 2, 100,
					TextColors.WHITE);
		}
		String summary = "Added " + formatSize(preview.addedBytes()) + "  Changed " + formatSize(preview.changedBytes()) + "  Removed "
				+ formatSize(preview.removedBytes()) + "  Preserved " + formatSize(preview.preservedBytes());
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(summary).withStyle(ChatFormatting.YELLOW), this.width / 2, this.height - 64, TextColors.WHITE);
		if (pageCount() > 1) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal((page + 1) + " / " + pageCount()).withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 27,
					TextColors.WHITE);
		}
	}

	private static List<String> limitedLines(String text, int limit) {
		List<String> lines = new ArrayList<>();
		for (String line : text.split("\\R", -1)) {
			if (lines.size() == limit) break;
			String trimmed = line.strip();
			if (trimmed.length() > 100) trimmed = trimmed.substring(0, 97) + "...";
			lines.add(trimmed);
		}
		return lines;
	}

	private static String join(Iterable<String> values) {
		StringBuilder joined = new StringBuilder();
		for (String value : values) {
			if (joined.length() > 0) joined.append(", ");
			joined.append(value);
		}
		String result = joined.length() == 0 ? "none" : joined.toString();
		return result.length() <= 100 ? result : result.substring(0, 97) + "...";
	}

	private static String rootText(UpdatePlan.Root root) {
		return switch (root) {
			case MODPACK_DIR -> "modpack";
			case GAME_DIR -> "game";
			case MODS_DIR -> "mods";
			case STORE_DIR -> "cas";
			case AUTOMODPACK_DIR -> "automodpack";
		};
	}

	private static String shortPath(String path) {
		return path.length() <= 43 ? path : "..." + path.substring(path.length() - 40);
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
		return false;
	}
}
