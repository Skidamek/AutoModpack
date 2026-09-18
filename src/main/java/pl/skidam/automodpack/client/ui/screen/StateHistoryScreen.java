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
	private List<StateHistory.SnapshotView> views;
	private Long selectedSeq;
	private String selectedFileKey;
	private Mode mode = Mode.TIMELINE;
	private boolean showFullTree;
	private final Map<String, StateHistory.FileGate> fileGates = new HashMap<>();
	private RowListWidget timeline;
	private RowListWidget files;
	private List<AbstractWidget> timelineButtons;
	private boolean loading = true;
	private boolean busy;
	private boolean closed;
	private boolean lastResultPresent;
	private boolean lastResultRestore;
	private Future<?> load;

	public StateHistoryScreen(Screen parent, InstalledModpackController controller, Runnable closedCallback) {
		super(VersionedText.text("automodpack.stateHistory.title"));
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
		ActionDefinition restore = primaryAction(VersionedText.text("automodpack.stateHistory.restore"), press -> restoreState());
		ActionDefinition filesAction = optionalAction(VersionedText.text("automodpack.stateHistory.files"), press -> openFiles());
		ActionDefinition forget = optionalAction(VersionedText.text("automodpack.stateHistory.forgetOlder"), press -> forgetOlder());
		ActionRow[] rows = {actionRow(ActionAreaLayout.RowKind.AUXILIARY, restore, filesAction, forget),
				actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.back"), press -> back()))};
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rows) - 6;
		List<RowListWidget.Row> listRows = new ArrayList<>();
		List<Snapshot> newestFirst = reversed();
		Snapshot selected = selectedEntry();
		for (Snapshot entry : newestFirst) listRows.add(entryRow(entry));
		this.timeline = this.addRenderableWidget(new RowListWidget(this.minecraft, this.width, this.height, this.width - 20, 0, 64, listBottom, 36, listRows, this::select, index -> openFiles()));
		selectRow(this.timeline, newestFirst, selected);
		this.timelineButtons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rows);
		updateTimelineButtons();
	}

	/** Selection changes update the actions in place: a rebuild would drop the list's scroll and break double-click detection. */
	private void updateTimelineButtons() {
		if (timelineButtons == null) return;
		Snapshot selected = selectedEntry();
		StateHistory.Restorability restorability = selected == null ? null : restorability(selected.seq());
		timelineButtons.get(0).active = selected != null && restorability == StateHistory.Restorability.READY && !busy;
		timelineButtons.get(1).active = selected != null && !busy;
		timelineButtons.get(2).active = selected != null && !busy && views != null && !views.isEmpty() && selected.seq() != views.get(0).snapshot().seq();
		setTooltip(timelineButtons.get(0), restoreTooltip(selected, restorability));
	}

	private void initFiles() {
		ActionDefinition restore = primaryAction(VersionedText.text("automodpack.stateHistory.restoreFile"), press -> restoreFile());
		ActionDefinition save = optionalAction(VersionedText.text("automodpack.stateHistory.saveCopy"), press -> saveFileCopy());
		ActionDefinition openFolder = optionalAction(VersionedText.text("automodpack.stateHistory.openFolder"), press -> controller.openRecoveredFolder());
		ActionDefinition all = optionalAction(VersionedText.text(showFullTree ? "automodpack.stateHistory.showDiff" : "automodpack.stateHistory.showAll"), press -> {
			showFullTree = !showFullTree;
			super.rebuild();
		});
		ActionRow[] rows = {actionRow(ActionAreaLayout.RowKind.AUXILIARY, restore, save, openFolder, all),
				actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.text("automodpack.back"), press -> showTimeline()))};
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rows) - 6;
		List<RowListWidget.Row> listRows = new ArrayList<>();
		List<TrackedFile> shown = selectedFiles();
		for (TrackedFile file : shown) listRows.add(fileRow(file));
		this.files = this.addRenderableWidget(new RowListWidget(this.minecraft, this.width, this.height, this.width - 20, 0, 64, listBottom, 24, listRows, this::pickFile));
		selectFileRow();
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
			case LIVE, FILE_RESTORE, REPAIR -> ChatFormatting.YELLOW;
			case DEACTIVATION, REMOVAL -> ChatFormatting.RED;
		};
		MutableComponent title = VersionedText.text("automodpack.stateHistory.kind." + entry.kind().name()).withStyle(color);
		StateHistory.SnapshotView view = view(entry.seq());
		List<StateHistory.FileDiff> diffs = view == null ? List.of() : view.diffs();
		int added = 0, changed = 0, removed = 0;
		for (StateHistory.FileDiff diff : diffs) {
			switch (diff.kind()) {
				case ADDED -> added++;
				case CHANGED -> changed++;
				case REMOVED -> removed++;
			}
		}
		int files = view == null ? 0 : view.tree().files().size();
		String pack = entry.modpackId().isEmpty() ? "" : packName(entry.modpackId());
		String summary = VersionedText.str("automodpack.stateHistory.entrySummary", DATE_FORMAT.format(entry.createdAt()), pack, files, added, changed, removed);
		return new RowListWidget.Row(List.of(title, VersionedText.literal(summary).withStyle(ChatFormatting.GRAY)));
	}

	private RowListWidget.Row fileRow(TrackedFile file) {
		return new RowListWidget.Row(List.of(VersionedText.literal(rootLabel(file.root()) + " · " + file.path()).withStyle(ChatFormatting.WHITE)));
	}

	private MutableComponent restoreTooltip(Snapshot selected, StateHistory.Restorability option) {
		if (selected == null) return VersionedText.text("automodpack.stateHistory.restorePickFirst");
		if (option == null) return VersionedText.text("automodpack.stateHistory.checking");
		return switch (option) {
			case READY -> VersionedText.text("automodpack.stateHistory.restoreReady");
			case CURRENT -> VersionedText.text("automodpack.stateHistory.restoreCurrent");
			case NOT_KEPT -> VersionedText.text("automodpack.stateHistory.restoreNotKept");
		};
	}

	private StateHistory.FileGate gate(TrackedFile file) {
		Snapshot selected = selectedEntry();
		if (selected == null || file == null) return null;
		return fileGates.get(gateKey(selected, file));
	}

	private MutableComponent fileRestoreTooltip(StateHistory.FileGate gate) {
		if (gate == null) return VersionedText.text("automodpack.stateHistory.restorePickFirst");
		return switch (gate) {
			case AVAILABLE -> VersionedText.text("automodpack.stateHistory.restoreFileReady");
			case NOT_GAME_DIR -> VersionedText.text("automodpack.stateHistory.restoreFileManaged");
			case OWNED -> VersionedText.text("automodpack.stateHistory.restoreFileOwned");
			case PROTECTED -> VersionedText.text("automodpack.stateHistory.restoreFileProtected");
		};
	}

	private void loadEntries() {
		try {
			List<StateHistory.SnapshotView> loaded = controller.stateViews();
			this.minecraft.execute(() -> loaded(loaded));
		} catch (Exception e) {
			this.minecraft.execute(() -> fail(e));
		}
	}

	private void loaded(List<StateHistory.SnapshotView> loaded) {
		if (closed) return;
		views = loaded;
		loading = false;
		load = null;
		if (selectedSeq == null && !loaded.isEmpty()) selectedSeq = loaded.get(loaded.size() - 1).snapshot().seq();
		super.rebuild();
		if (mode == Mode.FILES) resolveFileGate(selectedFile());
	}

	private void pickFile(int index) {
		List<TrackedFile> shown = selectedFiles();
		if (index < 0 || index >= shown.size() || selectedEntry() == null) return;
		selectedFileKey = gateKey(selectedEntry(), shown.get(index));
		resolveFileGate(shown.get(index));
		super.rebuild();
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
		if (entry == null || restorability(entry.seq()) != StateHistory.Restorability.READY || busy) return;
		busy = true;
		super.rebuild();
		controller.restoreState(entry, this::refreshAfterMutation);
	}

	private void forgetOlder() {
		Snapshot entry = selectedEntry();
		if (entry == null || busy || views == null || views.isEmpty() || entry.seq() == views.get(0).snapshot().seq()) return;
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
				operation.run();
				this.minecraft.execute(() -> {
					if (closed) return;
					lastResultPresent = true;
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
		fileGates.clear();
		selectedFileKey = null;
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
		selectedFileKey = null;
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
		updateTimelineButtons();
	}

	private Snapshot selectedEntry() {
		StateHistory.SnapshotView view = selectedSeq == null ? null : view(selectedSeq);
		return view == null ? null : view.snapshot();
	}

	private StateHistory.SnapshotView view(long seq) {
		if (views == null) return null;
		return views.stream().filter(entry -> entry.snapshot().seq() == seq).findFirst().orElse(null);
	}

	private StateHistory.Restorability restorability(long seq) {
		StateHistory.SnapshotView view = view(seq);
		return view == null ? null : view.restorability();
	}

	private void selectRow(RowListWidget list, List<Snapshot> newestFirst, Snapshot selected) {
		if (selected == null || newestFirst.isEmpty()) return;
		int index = newestFirst.indexOf(selected);
		if (index < 0) return;
		list.setSelected(list.children().get(index));
	}

	private void selectFileRow() {
		if (files == null || selectedFileKey == null || selectedEntry() == null) return;
		List<TrackedFile> shown = selectedFiles();
		for (int index = 0; index < shown.size(); index++) {
			if (selectedFileKey.equals(gateKey(selectedEntry(), shown.get(index)))) {
				files.setSelected(files.children().get(index));
				return;
			}
		}
	}

	private List<Snapshot> reversed() {
		List<Snapshot> newestFirst = new ArrayList<>();
		if (views != null) for (StateHistory.SnapshotView view : views) newestFirst.add(view.snapshot());
		Collections.reverse(newestFirst);
		return newestFirst;
	}

	private List<TrackedFile> selectedFiles() {
		StateHistory.SnapshotView view = selectedSeq == null ? null : view(selectedSeq);
		if (view == null) return List.of();
		if (showFullTree) return view.tree().files();
		List<TrackedFile> files = new ArrayList<>();
		for (StateHistory.FileDiff diff : view.diffs()) files.add(diff.after() == null ? diff.before() : diff.after());
		return files;
	}

	private TrackedFile selectedFile() {
		List<TrackedFile> shown = selectedFiles();
		if (shown.isEmpty() || selectedEntry() == null) return null;
		if (selectedFileKey != null) {
			for (TrackedFile file : shown) if (selectedFileKey.equals(gateKey(selectedEntry(), file))) return file;
		}
		return null;
	}

	private String packName(String modpackId) {
		InstalledModpackController.Pack pack = controller.installedPack(modpackId);
		return pack == null ? modpackId : pack.name();
	}

	private String rootLabel(Root root) {
		return VersionedText.str("automodpack.stateHistory.root." + root.name());
	}

	private void back() {
		if (closed) return;
		closed = true;
		closedCallback.run();
		ScreenImpl.setScreen(parent);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.stateHistory.title").withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
		String description;
		if (loading) description = VersionedText.str("automodpack.stateHistory.loading");
		else if (mode == Mode.TIMELINE) description = VersionedText.str("automodpack.stateHistory.description", views == null ? 0 : views.size());
		else description = VersionedText.str("automodpack.stateHistory.filesDescription", selectedFiles().size());
		List<String> descriptionLines = wrapToWidth(this.font, description, this.width - 28, 2);
		for (int index = 0; index < descriptionLines.size(); index++)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(descriptionLines.get(index)).withStyle(ChatFormatting.GRAY), this.width / 2, 28 + index * 12, TextColors.WHITE);
		if (busy) drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.stateHistory.working").withStyle(ChatFormatting.YELLOW), this.width / 2, 52, TextColors.WHITE);
		if (!loading && views != null && views.isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.stateHistory.empty").withStyle(ChatFormatting.GRAY), this.width / 2, 88, TextColors.WHITE);
		if (lastResultPresent) {
			String key = lastResultRestore ? "automodpack.stateHistory.restoredTo" : "automodpack.stateHistory.savedTo";
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.text(key).withStyle(ChatFormatting.GREEN), this.width / 2, 52, TextColors.WHITE);
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
