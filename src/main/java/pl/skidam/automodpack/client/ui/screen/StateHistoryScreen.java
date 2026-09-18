package pl.skidam.automodpack.client.ui.screen;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.RowListWidget;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.ClientStateJournal.Snapshot;
import pl.skidam.automodpack_core.update.InstanceTree;
import pl.skidam.automodpack_core.update.InstanceTree.TrackedFile;
import pl.skidam.automodpack_core.update.StateHistory;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/**
 * Instance timeline: snapshots this computer lived. Restore checkouts the whole tree. Files shows the parent diff
 * first. Forget drops older snapshots.
 */
public final class StateHistoryScreen extends VersionedScreen {
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm").withZone(ZoneId.systemDefault());

	private enum Mode {
		TIMELINE, FILES
	}

	private final Screen parent;
	private final InstalledModpackController controller;
	private final Runnable closedCallback;
	private List<Snapshot> entries;
	private Long selectedSeq;
	private Mode mode = Mode.TIMELINE;
	private boolean showFullTree;
	private final Map<Long, StateHistory.Restorability> restorabilityBySeq = new HashMap<>();
	private final Map<Long, List<StateHistory.FileDiff>> diffsBySeq = new HashMap<>();
	private final Map<Long, InstanceTree> treesBySeq = new HashMap<>();
	private final Map<String, StateHistory.FileGate> fileGates = new HashMap<>();
	private RowListWidget timeline;
	private RowListWidget files;
	private boolean loading = true;
	private boolean busy;
	private boolean closed;
	private Path lastResult;
	private boolean lastResultRestore;
	private Future<?> load;

	public StateHistoryScreen(Screen parent, InstalledModpackController controller, Runnable closedCallback) {
		super(VersionedText.translatable("automodpack.stateHistory.title"));
		this.parent = parent;
		this.controller = controller;
		this.closedCallback = closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		if (mode == Mode.TIMELINE) initTimeline();
		else initFiles();
		if (loading && load == null) load = ScreenManager.background(this::loadEntries);
	}

	private void initTimeline() {
		ActionDefinition restore = primaryAction(VersionedText.translatable("automodpack.stateHistory.restore"), press -> restoreState());
		ActionDefinition filesAction = optionalAction(VersionedText.translatable("automodpack.stateHistory.files"), press -> openFiles());
		ActionDefinition forget = optionalAction(VersionedText.translatable("automodpack.stateHistory.forgetOlder"), press -> forgetOlder());
		ActionRow[] rows = {actionRow(ActionAreaLayout.RowKind.FOOTER, restore, filesAction, forget, secondaryAction(VersionedText.translatable("automodpack.back"), press -> back()))};
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rows) - 6;
		List<RowListWidget.Row> listRows = new ArrayList<>();
		List<Snapshot> newestFirst = reversed();
		Snapshot selected = selectedEntry();
		for (Snapshot entry : newestFirst) listRows.add(entryRow(entry));
		this.timeline = this.addRenderableWidget(new RowListWidget(this.minecraft, this.width, this.height, this.width - 20, 0, 64, listBottom, 36, listRows, this::select));
		selectRow(this.timeline, newestFirst, selected);
		List<AbstractWidget> buttons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rows);
		buttons.get(0).active = selected != null && restorabilityBySeq.get(selected.seq()) == StateHistory.Restorability.READY && !busy;
		buttons.get(1).active = selected != null && !busy;
		buttons.get(2).active = selected != null && !busy && entries != null && !entries.isEmpty() && selected.seq() != entries.get(0).seq();
		setTooltip(buttons.get(0), restoreTooltip(selected, selected == null ? null : restorabilityBySeq.get(selected.seq())));
	}

	private void initFiles() {
		Snapshot selected = selectedEntry();
		ActionDefinition restore = primaryAction(VersionedText.translatable("automodpack.stateHistory.restoreFile"), press -> restoreFile());
		ActionDefinition save = optionalAction(VersionedText.translatable("automodpack.stateHistory.saveCopy"), press -> saveFileCopy());
		ActionDefinition all = optionalAction(VersionedText.translatable(showFullTree ? "automodpack.stateHistory.showDiff" : "automodpack.stateHistory.showAll"), press -> {
			showFullTree = !showFullTree;
			super.rebuild();
		});
		ActionRow[] rows = {actionRow(ActionAreaLayout.RowKind.FOOTER, restore, save, all, secondaryAction(VersionedText.translatable("automodpack.stateHistory.timeline"), press -> showTimeline()))};
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rows) - 6;
		List<RowListWidget.Row> listRows = new ArrayList<>();
		List<TrackedFile> shown = selectedFiles();
		for (TrackedFile file : shown) listRows.add(fileRow(file));
		this.files = this.addRenderableWidget(new RowListWidget(this.minecraft, this.width, this.height, this.width - 20, 0, 64, listBottom, 24, listRows, index -> super.rebuild()));
		TrackedFile selectedFile = selectedFile();
		StateHistory.FileGate gate = gate(selectedFile);
		List<AbstractWidget> buttons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rows);
		buttons.get(0).active = !busy && gate == StateHistory.FileGate.AVAILABLE;
		buttons.get(1).active = selectedFile != null && !busy;
		setTooltip(buttons.get(0), fileRestoreTooltip(gate));
	}

	private RowListWidget.Row entryRow(Snapshot entry) {
		ChatFormatting color = switch (entry.kind()) {
			case INSTALL, UPDATE, ROLLBACK, RESTORE -> ChatFormatting.GREEN;
			case LIVE, FILE_RESTORE, REPAIR, DRIFT_RESET -> ChatFormatting.YELLOW;
			case DEACTIVATION, REMOVAL -> ChatFormatting.RED;
		};
		MutableComponent title = VersionedText.translatable("automodpack.stateHistory.kind." + entry.kind().name()).withStyle(color);
		List<StateHistory.FileDiff> diffs = diffsBySeq.get(entry.seq());
		int added = 0, changed = 0, removed = 0;
		if (diffs != null) for (StateHistory.FileDiff diff : diffs) {
			switch (diff.kind()) {
				case ADDED -> added++;
				case CHANGED -> changed++;
				case REMOVED -> removed++;
			}
		}
		int files = treesBySeq.get(entry.seq()) == null ? 0 : treesBySeq.get(entry.seq()).files().size();
		String pack = entry.modpackId().isEmpty() ? "" : packName(entry.modpackId());
		String summary = VersionedText.translatable("automodpack.stateHistory.entrySummary", DATE_FORMAT.format(entry.createdAt()), pack, files, added, changed, removed).getString();
		return new RowListWidget.Row(List.of(title, VersionedText.literal(summary).withStyle(ChatFormatting.GRAY)));
	}

	private RowListWidget.Row fileRow(TrackedFile file) {
		return new RowListWidget.Row(List.of(VersionedText.literal(rootLabel(file.root()) + " · " + file.path()).withStyle(ChatFormatting.WHITE)));
	}

	private MutableComponent restoreTooltip(Snapshot selected, StateHistory.Restorability option) {
		if (selected == null) return VersionedText.translatable("automodpack.stateHistory.restorePickFirst");
		if (option == null) return VersionedText.translatable("automodpack.stateHistory.checking");
		return switch (option) {
			case READY -> VersionedText.translatable("automodpack.stateHistory.restoreReady");
			case CURRENT -> VersionedText.translatable("automodpack.stateHistory.restoreCurrent");
			case NOT_KEPT -> VersionedText.translatable("automodpack.stateHistory.restoreNotKept");
		};
	}

	private StateHistory.FileGate gate(TrackedFile file) {
		Snapshot selected = selectedEntry();
		if (selected == null || file == null) return null;
		return fileGates.get(gateKey(selected, file));
	}

	private MutableComponent fileRestoreTooltip(StateHistory.FileGate gate) {
		if (gate == null) return VersionedText.translatable("automodpack.stateHistory.restorePickFirst");
		return switch (gate) {
			case AVAILABLE -> VersionedText.translatable("automodpack.stateHistory.restoreFileReady");
			case NOT_GAME_DIR -> VersionedText.translatable("automodpack.stateHistory.restoreFileManaged");
			case OWNED -> VersionedText.translatable("automodpack.stateHistory.restoreFileOwned");
		};
	}

	private void loadEntries() {
		try {
			List<Snapshot> loaded = controller.stateEntries();
			this.minecraft.execute(() -> loaded(loaded));
		} catch (Exception e) {
			this.minecraft.execute(() -> fail(e));
		}
	}

	private void loaded(List<Snapshot> loaded) {
		if (closed) return;
		entries = loaded;
		loading = false;
		load = null;
		if (selectedSeq == null && !loaded.isEmpty()) selectedSeq = loaded.get(loaded.size() - 1).seq();
		super.rebuild();
		for (Snapshot entry : loaded) {
			resolveRestorability(entry);
			resolveDiff(entry);
		}
	}

	private void resolveRestorability(Snapshot entry) {
		if (restorabilityBySeq.containsKey(entry.seq())) return;
		ScreenManager.background(() -> {
			try {
				StateHistory.Restorability option = controller.stateRestorability(entry);
				this.minecraft.execute(() -> {
					if (closed) return;
					restorabilityBySeq.put(entry.seq(), option);
					super.rebuild();
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private void resolveDiff(Snapshot entry) {
		if (diffsBySeq.containsKey(entry.seq())) return;
		ScreenManager.background(() -> {
			try {
				List<StateHistory.FileDiff> diffs = controller.stateDiff(entry);
				InstanceTree tree = controller.stateTree(entry);
				this.minecraft.execute(() -> {
					if (closed) return;
					diffsBySeq.put(entry.seq(), diffs);
					treesBySeq.put(entry.seq(), tree);
					super.rebuild();
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private void resolveFileGate(TrackedFile file) {
		Snapshot selected = selectedEntry();
		if (selected == null || file == null || fileGates.containsKey(gateKey(selected, file))) return;
		ScreenManager.background(() -> {
			try {
				StateHistory.FileGate gate = controller.stateFileGate(file.root(), file.path());
				this.minecraft.execute(() -> {
					if (closed) return;
					fileGates.put(gateKey(selected, file), gate);
					super.rebuild();
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private static String gateKey(Snapshot entry, TrackedFile file) {
		return entry.seq() + ":" + file.root() + ":" + file.overlayPackId() + ":" + file.path();
	}

	private void restoreState() {
		Snapshot entry = selectedEntry();
		if (entry == null || restorabilityBySeq.get(entry.seq()) != StateHistory.Restorability.READY || busy) return;
		busy = true;
		super.rebuild();
		controller.restoreState(entry, this::refreshAfterMutation);
	}

	private void forgetOlder() {
		Snapshot entry = selectedEntry();
		if (entry == null || busy || entries == null || entries.isEmpty() || entry.seq() == entries.get(0).seq()) return;
		busy = true;
		super.rebuild();
		controller.forgetOlderThan(entry.seq(), this::refreshAfterMutation);
	}

	private void restoreFile() {
		TrackedFile file = selectedFile();
		if (file == null || busy) return;
		busy = true;
		super.rebuild();
		runFileOp(() -> controller.restoreStateFile(selectedEntry().seq(), file.root(), file.path()), true);
	}

	private void saveFileCopy() {
		TrackedFile file = selectedFile();
		if (file == null || busy) return;
		busy = true;
		super.rebuild();
		runFileOp(() -> controller.saveStateFileCopy(selectedEntry().seq(), file.root(), file.path()), false);
	}

	private void runFileOp(StateOperation operation, boolean restore) {
		ScreenManager.background(() -> {
			try {
				Path result = operation.run();
				this.minecraft.execute(() -> {
					if (closed) return;
					lastResult = result;
					lastResultRestore = restore;
					busy = false;
					refreshAfterMutation();
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private void refreshAfterMutation() {
		if (closed) return;
		busy = false;
		restorabilityBySeq.clear();
		diffsBySeq.clear();
		treesBySeq.clear();
		fileGates.clear();
		loading = true;
		load = ScreenManager.background(this::loadEntries);
		super.rebuild();
	}

	private void fail(Exception exception) {
		if (closed) return;
		closed = true;
		closedCallback.run();
		ScreenManager.failure(FailureRequest.of(exception, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
	}

	private void openFiles() {
		if (selectedEntry() == null) return;
		mode = Mode.FILES;
		showFullTree = false;
		super.rebuild();
	}

	private void showTimeline() {
		mode = Mode.TIMELINE;
		super.rebuild();
	}

	private void select(int index) {
		List<Snapshot> newestFirst = reversed();
		if (index < 0 || index >= newestFirst.size()) return;
		selectedSeq = newestFirst.get(index).seq();
		super.rebuild();
	}

	private Snapshot selectedEntry() {
		if (selectedSeq == null || entries == null) return null;
		return entries.stream().filter(entry -> entry.seq() == selectedSeq).findFirst().orElse(null);
	}

	private void selectRow(RowListWidget list, List<Snapshot> newestFirst, Snapshot selected) {
		if (selected == null || newestFirst.isEmpty()) return;
		int index = newestFirst.indexOf(selected);
		if (index < 0) return;
		list.setSelected(list.children().get(index));
	}

	private List<Snapshot> reversed() {
		List<Snapshot> newestFirst = new ArrayList<>(entries == null ? List.of() : entries);
		Collections.reverse(newestFirst);
		return newestFirst;
	}

	private List<TrackedFile> selectedFiles() {
		Snapshot entry = selectedEntry();
		if (entry == null) return List.of();
		if (showFullTree) {
			InstanceTree tree = treesBySeq.get(entry.seq());
			return tree == null ? List.of() : tree.files();
		}
		List<StateHistory.FileDiff> diffs = diffsBySeq.get(entry.seq());
		if (diffs == null) return List.of();
		List<TrackedFile> files = new ArrayList<>();
		for (StateHistory.FileDiff diff : diffs) files.add(diff.after() == null ? diff.before() : diff.after());
		return files;
	}

	private TrackedFile selectedFile() {
		List<TrackedFile> shown = selectedFiles();
		if (files == null || shown.isEmpty()) return null;
		int index = files.getSelected() == null ? -1 : files.children().indexOf(files.getSelected());
		if (index < 0 || index >= shown.size()) return null;
		return shown.get(index);
	}

	private String packName(String modpackId) {
		InstalledModpackController.Pack pack = controller.installedPack(modpackId);
		return pack == null ? modpackId : pack.name();
	}

	private String rootLabel(Root root) {
		return VersionedText.translatable("automodpack.stateHistory.root." + root.name()).getString();
	}

	private void back() {
		if (closed) return;
		closed = true;
		closedCallback.run();
		ScreenImpl.setScreen(parent);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.stateHistory.title").withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
		String description;
		if (loading) description = VersionedText.translatable("automodpack.stateHistory.loading").getString();
		else if (mode == Mode.TIMELINE) description = VersionedText.translatable("automodpack.stateHistory.description", entries == null ? 0 : entries.size()).getString();
		else {
			Snapshot selected = selectedEntry();
			description = VersionedText.translatable("automodpack.stateHistory.filesDescription", selectedFiles().size(), selected == null ? "" : packName(selected.modpackId())).getString();
		}
		List<String> descriptionLines = wrapToWidth(this.font, description, this.width - 28, 2);
		for (int index = 0; index < descriptionLines.size(); index++)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(descriptionLines.get(index)).withStyle(ChatFormatting.GRAY), this.width / 2, 28 + index * 12, TextColors.WHITE);
		if (busy) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.stateHistory.working").withStyle(ChatFormatting.YELLOW), this.width / 2, 52, TextColors.WHITE);
		if (!loading && entries != null && entries.isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.stateHistory.empty").withStyle(ChatFormatting.GRAY), this.width / 2, 88, TextColors.WHITE);
		if (lastResult != null) {
			String key = lastResultRestore ? "automodpack.stateHistory.restoredTo" : "automodpack.stateHistory.savedTo";
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable(key, lastResult.toString()).withStyle(ChatFormatting.GREEN), this.width / 2, 52, TextColors.WHITE);
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		if (mode == Mode.FILES) {
			showTimeline();
			return false;
		}
		back();
		return false;
	}

	@FunctionalInterface
	private interface StateOperation {
		Path run() throws Exception;
	}
}
