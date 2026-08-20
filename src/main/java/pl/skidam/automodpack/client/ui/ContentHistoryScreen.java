package pl.skidam.automodpack.client.ui;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutionException;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.ActionAreaLayout;
import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.modpack.generation.CatalogueSnapshot;
import pl.skidam.automodpack_core.modpack.generation.GenerationDiff;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.HistoricalCatalogueLoader;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/**
 * Shows the authoritative generation timeline. The index is thin and is rendered immediately;
 * historical catalogues are fetched only after the player selects an entry whose local details are
 * not already available.
 */
public final class ContentHistoryScreen extends VersionedScreen {
	private static final int ENTRY_TOP = 52;
	private static final int ENTRY_HEIGHT = 54;
	private static final int PANEL_WIDTH = 600;
	private static final int ROW_HEIGHT = 20;

	private final Screen parent;
	private final GenerationHistoryIndex historyIndex;
	private final List<GenerationRecord> localHistory;
	private final List<HistoryEntry> entries;
	private final String modpackName;
	private final HistoricalCatalogueLoader catalogueLoader;
	private final Runnable closedCallback;
	private final Map<String, GenerationRecord> localByGenerationId;
	private Button previousButton;
	private Button nextButton;
	private int page;
	private boolean busy;
	private boolean closed;

	public ContentHistoryScreen(Screen parent, GenerationHistoryIndex historyIndex, List<GenerationRecord> localHistory, String modpackName,
			HistoricalCatalogueLoader catalogueLoader, Runnable closedCallback) {
		super(VersionedText.translatable("automodpack.history.title"));
		this.parent = parent;
		this.historyIndex = Objects.requireNonNull(historyIndex, "generation history index");
		this.localHistory = List.copyOf(localHistory == null ? List.of() : localHistory);
		this.localByGenerationId = new HashMap<>();
		for (GenerationRecord record : this.localHistory) this.localByGenerationId.put(record.metadata().generationId(), record);
		this.entries = buildEntries();
		this.modpackName = modpackName == null ? "" : modpackName;
		this.catalogueLoader = Objects.requireNonNull(catalogueLoader, "historical catalogue loader");
		this.closedCallback = closedCallback == null ? () -> {} : closedCallback;
	}

	private List<HistoryEntry> buildEntries() {
		List<HistoryEntry> result = new ArrayList<>(historyIndex.entries().size());
		for (int index = historyIndex.entries().size() - 1; index >= 0; index--) {
			GenerationHistoryIndex.Entry entry = historyIndex.entries().get(index);
			result.add(new HistoryEntry(entry.generationId(), entry.parentGenerationId(), entry.createdAt(), entry.patchNotes(), entry.diffSummary(), entry.detailsAvailable(),
					entry.rollbackAvailable(), localByGenerationId.get(entry.generationId()), entry));
		}
		return List.copyOf(result);
	}

	@Override
	protected void init() {
		super.init();
		int bottomY = this.height - 28;
		boolean hasPagination = pageCount() > 1;
		List<ActionRow> actionRows = new ArrayList<>();
		if (hasPagination) {
			actionRows.add(actionRow(ActionAreaLayout.RowKind.NAVIGATION,
					navigationAction(VersionedText.translatable("automodpack.ui.previous"), button -> changePage(-1)),
					disabledNavigationAction(VersionedText.translatable("automodpack.ui.page", page + 1, pageCount())),
					navigationAction(VersionedText.translatable("automodpack.ui.next"), button -> changePage(1))));
		}
		List<ActionDefinition> footerActions = new ArrayList<>();
		footerActions.add(secondaryAction(VersionedText.translatable("automodpack.back"), button -> back()));
		if (hasPatchNotesHistory()) footerActions.add(optionalAction(VersionedText.translatable("automodpack.patchNotes.button"), button -> openPatchNotes()));
		if (hasLocalFiles()) footerActions.add(primaryAction(VersionedText.translatable("automodpack.management.files"), button -> openFiles()));
		actionRows.add(actionRow(ActionAreaLayout.RowKind.FOOTER, footerActions.toArray(ActionDefinition[]::new)));
		List<Button> actionButtons = this.addActionArea(PANEL_WIDTH, bottomY, actionRows.toArray(ActionRow[]::new));
		if (hasPagination) {
			this.previousButton = actionButtons.get(0);
			this.nextButton = actionButtons.get(2);
		} else {
			this.previousButton = null;
			this.nextButton = null;
		}
		updateNavigation();

		int start = page * rowsPerPage();
		int end = Math.min(entries.size(), start + rowsPerPage());
		int rowWidth = panelWidth(PANEL_WIDTH);
		int x = panelLeft(PANEL_WIDTH);
		for (int index = start; index < end; index++) {
			int entryIndex = index;
			HistoryEntry entry = entries.get(index);
			String label = VersionedText.translatable("automodpack.history.updated", UiFormat.formatInstant(entry.createdAt())).getString();
			Button row = buttonWidget(x, ENTRY_TOP + (index - start) * ENTRY_HEIGHT, rowWidth, ENTRY_HEIGHT - 2,
					VersionedText.literal(truncateToWidth(this.font, label, rowWidth - 12)).withStyle(isCurrent(entry) ? ChatFormatting.GREEN : ChatFormatting.WHITE), button -> openEntry(entryIndex));
			row.active = !busy && canOpen(entry);
			if (!canOpen(entry)) setTooltip(row, VersionedText.translatable("automodpack.history.detailsCompacted"));
			this.addRenderableWidget(row);
		}
	}

	private boolean hasPatchNotesHistory() {
		return entries.size() > 1 || entries.stream().anyMatch(entry -> !entry.patchNotes().isBlank());
	}

	private boolean hasLocalFiles() {
		return !localHistory.isEmpty();
	}

	private int pageCount() {
		int pageSize = rowsPerPage();
		return Math.max(1, (entries.size() + pageSize - 1) / pageSize);
	}

	private int rowsPerPage() {
		return Math.max(1, (this.height - 92 - ENTRY_TOP) / ENTRY_HEIGHT);
	}

	private void updateNavigation() {
		page = Math.max(0, Math.min(pageCount() - 1, page));
		if (previousButton != null) previousButton.active = !busy && page > 0;
		if (nextButton != null) nextButton.active = !busy && page + 1 < pageCount();
	}

	private void changePage(int amount) {
		page = Math.max(0, Math.min(pageCount() - 1, page + amount));
		rebuild();
	}

	private void rebuild() {
		/*? if >=1.19.2 {*/
		this.rebuildWidgets();
		/*?} else {*/
		/*
		this.init(this.minecraft, this.width, this.height);
		*//*?}*/
	}

	private boolean isCurrent(HistoryEntry entry) {
		return historyIndex.currentGenerationId().equals(entry.generationId());
	}

	private void openPatchNotes() {
		ScreenImpl.setScreen(PatchNotesHistoryScreen.fromIndex(this, historyIndex, modpackName));
	}

	private void openFiles() {
		if (localHistory.isEmpty()) return;
		GenerationRecord latest = localByGenerationId.get(historyIndex.currentGenerationId());
		if (latest == null) latest = localHistory.get(localHistory.size() - 1);
		Map<String, String> featureNames = featureNames(latest.manifest());
		ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.translatable("automodpack.files.title", modpackName),
				VersionedText.translatable("automodpack.files.description"), ChangeSet.catalogue(latest.manifest()), featureNames));
	}

	private void openEntry(int index) {
		if (busy || index < 0 || index >= entries.size()) return;
		HistoryEntry entry = entries.get(index);
		if (entry.localRecord() != null) {
			openLocal(entry);
			return;
		}
		if (!canOpen(entry) || entry.indexEntry() == null) return;
		busy = true;
		updateNavigation();
		rebuild();
		loadRemote(entry).whenComplete((loaded, failure) -> this.minecraft.execute(() -> {
			if (closed) return;
			busy = false;
			if (failure != null) {
				updateNavigation();
				rebuild();
				failure(unwrap(failure));
				return;
			}
			openBrowser(entry, loaded.catalogue().manifest(), loaded.parentManifest());
		}));
	}

	private void openLocal(HistoryEntry entry) {
		GenerationRecord record = entry.localRecord();
		GenerationRecord parentRecord = localByGenerationId.get(entry.parentGenerationId());
		if (parentRecord != null || entry.parentGenerationId().isEmpty()) {
			openBrowser(entry, record.manifest(), parentRecord == null ? null : parentRecord.manifest());
			return;
		}
		GenerationHistoryIndex.Entry parentEntry = historyIndex.find(entry.parentGenerationId()).orElse(null);
		if (parentEntry == null || !parentEntry.detailsAvailable()) return;
		busy = true;
		rebuild();
		catalogueLoader.load(parentEntry).whenComplete((parent, failure) -> this.minecraft.execute(() -> {
			if (closed) return;
			busy = false;
			if (failure != null) {
				rebuild();
				failure(unwrap(failure));
				return;
			}
			openBrowser(entry, record.manifest(), parent.manifest());
		}));
	}

	private CompletableFuture<LoadedEntry> loadRemote(HistoryEntry entry) {
		CompletableFuture<CatalogueSnapshot> current = catalogueLoader.load(entry.indexEntry());
		return current.thenCompose(catalogue -> loadParent(entry).thenApply(parent -> new LoadedEntry(catalogue, parent)));
	}

	private CompletableFuture<GroupManifest> loadParent(HistoryEntry entry) {
		if (entry.parentGenerationId().isEmpty()) return CompletableFuture.completedFuture(null);
		GenerationRecord localParent = localByGenerationId.get(entry.parentGenerationId());
		if (localParent != null) return CompletableFuture.completedFuture(localParent.manifest());
		GenerationHistoryIndex.Entry parentEntry = historyIndex.find(entry.parentGenerationId()).orElse(null);
		if (parentEntry == null || !parentEntry.detailsAvailable())
			return CompletableFuture.failedFuture(new IllegalStateException("The parent generation catalogue is unavailable"));
		return catalogueLoader.load(parentEntry).thenApply(CatalogueSnapshot::manifest);
	}

	private void openBrowser(HistoryEntry entry, GroupManifest current, GroupManifest parentManifest) {
		if (parentManifest == null && !entry.parentGenerationId().isEmpty()) throw new IllegalStateException("A history diff requires its parent catalogue");
		GenerationDiff diff = GenerationDiff.between(parentManifest, current);
		ScreenImpl.setScreen(new ChangeBrowserScreen(this,
				VersionedText.translatable("automodpack.history.detailsTitle", VersionedText.translatable("automodpack.history.updated", UiFormat.formatInstant(entry.createdAt())).getString()),
				VersionedText.translatable("automodpack.history.detailsDescription"), diff.changeSet(), featureNames(current)));
	}

	private boolean canOpen(HistoryEntry entry) {
		if (entry.localRecord() == null && !entry.detailsAvailable()) return false;
		if (entry.parentGenerationId().isEmpty() || localByGenerationId.containsKey(entry.parentGenerationId())) return true;
		return historyIndex.find(entry.parentGenerationId()).map(GenerationHistoryIndex.Entry::detailsAvailable).orElse(false);
	}

	private static Map<String, String> featureNames(GroupManifest manifest) {
		Map<String, String> names = new TreeMap<>();
		for (Map.Entry<String, GroupManifest.Group> group : manifest.groups().entrySet()) {
			String name = group.getValue().displayName();
			names.put(group.getKey(), name.isBlank() ? VersionedText.translatable("automodpack.browser.unknownFeature").getString() : name);
		}
		return names;
	}

	private void failure(Throwable cause) {
		new ScreenManager().failure(FailureRequest.of(cause, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.CURRENT_SCREEN, null));
	}

	private static Throwable unwrap(Throwable failure) {
		Throwable current = failure;
		while ((current instanceof CompletionException || current instanceof ExecutionException) && current.getCause() != null) current = current.getCause();
		return current;
	}

	private void back() {
		if (closed) return;
		closed = true;
		catalogueLoader.close();
		closedCallback.run();
		ScreenImpl.setScreen(parent);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String title = VersionedText.translatable(modpackName.isBlank() ? "automodpack.history.title" : "automodpack.history.titleNamed", modpackName).getString();
		int left = panelLeft(PANEL_WIDTH);
		drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.BOLD), left, 10, TextColors.WHITE);
		drawTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.history.description").withStyle(ChatFormatting.GRAY), left, 25, TextColors.WHITE);
		int start = page * rowsPerPage();
		int end = Math.min(entries.size(), start + rowsPerPage());
		int rowWidth = panelWidth(PANEL_WIDTH);
		for (int index = start; index < end; index++) {
			HistoryEntry entry = entries.get(index);
			int y = ENTRY_TOP + (index - start) * ENTRY_HEIGHT;
			String status = status(entry);
			String note = entry.patchNotes().isBlank() ? VersionedText.translatable("automodpack.history.noPatchNotes").getString() : firstLine(entry.patchNotes());
			GenerationDiff.Summary diff = entry.diffSummary();
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, status, rowWidth - 12)).withStyle(isCurrent(entry) ? ChatFormatting.GREEN : ChatFormatting.GRAY), left + 6, y + 4, TextColors.WHITE);
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, VersionedText.translatable("automodpack.history.patchNotes", note).getString(), rowWidth - 12)).withStyle(ChatFormatting.YELLOW), left + 6, y + 31,
					TextColors.WHITE);
			String diffText = VersionedText.translatable("automodpack.history.diff", diff.addedFiles(), diff.modifiedFiles(), diff.removedFiles(), diff.metadataOnlyFiles(), diff.metadataChanges()).getString();
			drawTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, diffText, rowWidth - 12)).withStyle(ChatFormatting.GRAY), left + 6, y + 42, TextColors.WHITE);
		}
		if (entries.isEmpty()) drawTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.history.empty").withStyle(ChatFormatting.GRAY), left, ENTRY_TOP, TextColors.WHITE);
		if (busy) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.history.loading").withStyle(ChatFormatting.YELLOW), this.width / 2, this.height - 44, TextColors.WHITE);
	}

	private String status(HistoryEntry entry) {
		List<String> values = new ArrayList<>();
		if (isCurrent(entry)) values.add(VersionedText.translatable("automodpack.history.current").getString());
		if (entry.localRecord() != null) values.add(VersionedText.translatable("automodpack.history.savedLocally").getString());
		if (!isCurrent(entry) && entry.rollbackAvailable()) values.add(VersionedText.translatable("automodpack.history.rollbackAvailable").getString());
		if (entry.localRecord() == null) values.add(VersionedText.translatable(entry.detailsAvailable() ? "automodpack.history.detailsAvailable" : "automodpack.history.detailsCompacted").getString());
		return String.join(" · ", values);
	}

	private String firstLine(String notes) {
		return truncateToWidth(this.font, notes.split("\\R", -1)[0], Math.max(1, panelWidth(PANEL_WIDTH) - 12));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::back);
	}

	private record LoadedEntry(CatalogueSnapshot catalogue, GroupManifest parentManifest) {}

	private record HistoryEntry(String generationId, String parentGenerationId, Instant createdAt, String patchNotes, GenerationDiff.Summary diffSummary,
			boolean detailsAvailable, boolean rollbackAvailable, GenerationRecord localRecord, GenerationHistoryIndex.Entry indexEntry) {}
}
