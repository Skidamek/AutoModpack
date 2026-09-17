package pl.skidam.automodpack.client.ui.screen;

import java.nio.file.Path;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
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
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStateJournal;
import pl.skidam.automodpack_core.update.PreservationVault;
import pl.skidam.automodpack_core.update.StateHistory;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/**
 * The instance state history: every checkpoint AutoModpack recorded, newest first. A clean generation state of the
 * active pack restores whole through the reviewed rollback; every file of every state can be restored or copied out.
 */
public final class StateHistoryScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 500;
	private static final int LIST_TOP = 66;
	private static final int ROW_HEIGHT = 24;
	private static final DateTimeFormatter DATE_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT).withZone(ZoneId.systemDefault());

	private final Screen parent;
	private final InstalledModpackController controller;
	private final Runnable closedCallback;
	private Mode mode = Mode.TIMELINE;
	private List<ClientStateJournal.StateEntry> entries;
	private Long selectedSeq;
	private int selectedFileIndex = -1;
	private final Map<Long, StateHistory.RestoreOption> restorabilityBySeq = new HashMap<>();
	private final Map<String, PreservationVault.OriginalRestore> fileGates = new HashMap<>();
	private final Map<String, String> packNames = new HashMap<>();
	private boolean loading;
	private boolean busy;
	private boolean presentingFailure;
	private boolean closed;
	private boolean lastResultRestore;
	private Path lastResult;
	private Future<?> work;

	private enum Mode {
		TIMELINE, FILES
	}

	public StateHistoryScreen(Screen parent, InstalledModpackController controller, Runnable closedCallback) {
		super(VersionedText.translatable("automodpack.stateHistory.title"));
		this.parent = parent;
		this.controller = controller;
		this.closedCallback = closedCallback;
	}

	@Override
	protected void init() {
		super.init();
		if (!loading && entries == null) load();
		if (mode == Mode.TIMELINE) initTimeline();
		else initFiles();
	}

	private void initTimeline() {
		ClientStateJournal.StateEntry selected = selectedEntry();
		List<ActionRow> actions = new ArrayList<>();
		actions.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY,
				primaryAction(VersionedText.translatable("automodpack.stateHistory.restore"), press -> restoreState()),
				optionalAction(VersionedText.translatable("automodpack.stateHistory.files"), press -> openFiles())));
		if (selected != null && isUndoableArrival(selected))
			actions.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY,
					optionalAction(VersionedText.translatable("automodpack.stateHistory.undoArrival"), press -> undoArrival())));
		if (lastResult != null) actions.add(confirmationRow());
		actions.add(actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), press -> back())));
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actions.toArray(ActionRow[]::new)) - 8;
		List<ClientStateJournal.StateEntry> newestFirst = reversed();
		List<RowListWidget.Row> rows = new ArrayList<>();
		for (ClientStateJournal.StateEntry entry : newestFirst) rows.add(entryRow(entry));
		RowListWidget list = new RowListWidget(this.minecraft, this.width, this.height, panelWidth(PANEL_WIDTH), 0, LIST_TOP, listBottom, ROW_HEIGHT, rows,
				index -> select(newestFirst.get(index).seq()));
		selectRow(list, newestFirst, selected);
		this.addRenderableWidget(list);
		List<AbstractWidget> buttons = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actions.toArray(ActionRow[]::new));
		AbstractWidget restore = buttons.get(0);
		StateHistory.RestoreOption option = selected == null ? null : restorabilityBySeq.get(selected.seq());
		restore.active = !busy && option != null && option.restorability() == StateHistory.Restorability.READY;
		setTooltip(restore, restoreTooltip(selected, option));
		buttons.get(1).active = !busy && selected != null;
	}

	private void initFiles() {
		ClientStateJournal.StateEntry selected = selectedEntry();
		List<ClientStateJournal.TrackedFile> files = selectedFiles();
		List<ActionRow> actions = new ArrayList<>();
		actions.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY,
				primaryAction(VersionedText.translatable("automodpack.stateHistory.restoreFile"), press -> restoreFile()),
				optionalAction(VersionedText.translatable("automodpack.stateHistory.saveCopy"), press -> saveFileCopy())));
		if (lastResult != null) actions.add(confirmationRow());
		actions.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.stateHistory.timeline"), press -> showTimeline()),
				secondaryAction(VersionedText.translatable("automodpack.back"), press -> back())));
		int listBottom = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actions.toArray(ActionRow[]::new)) - 8;
		List<RowListWidget.Row> rows = new ArrayList<>();
		for (ClientStateJournal.TrackedFile file : files)
			rows.add(new RowListWidget.Row(List.of(
					VersionedText.literal(truncateToWidth(this.font, file.path(), panelWidth(PANEL_WIDTH) - 16)).withStyle(ChatFormatting.WHITE),
					VersionedText.literal(truncateToWidth(this.font, file.root().name() + " · " + humanSize(file.size()), panelWidth(PANEL_WIDTH) - 16)).withStyle(ChatFormatting.GRAY))));
		RowListWidget list = new RowListWidget(this.minecraft, this.width, this.height, panelWidth(PANEL_WIDTH), 0, LIST_TOP, listBottom, ROW_HEIGHT, rows, this::pickFile);
		if (selectedFileIndex >= 0 && selectedFileIndex < list.children().size()) list.setSelected(list.children().get(selectedFileIndex));
		this.addRenderableWidget(list);
		List<AbstractWidget> buttons = addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, actions.toArray(ActionRow[]::new));
		AbstractWidget restore = buttons.get(0);
		PreservationVault.OriginalRestore gate = gate(selectedFile());
		restore.active = !busy && gate == PreservationVault.OriginalRestore.AVAILABLE;
		setTooltip(restore, fileRestoreTooltip(gate));
		buttons.get(1).active = !busy && selectedFile() != null;
	}

	private ActionRow confirmationRow() {
		String key = lastResultRestore ? "automodpack.stateHistory.restoredTo" : "automodpack.stateHistory.savedTo";
		String confirmation = truncateToWidth(this.font, VersionedText.translatable(key, displayPath(lastResult)).getString(), panelWidth(PANEL_WIDTH) - 12);
		return actionRow(ActionAreaLayout.RowKind.AUXILIARY, disabledAction(VersionedText.literal(confirmation).withStyle(ChatFormatting.GREEN)));
	}

	private RowListWidget.Row entryRow(ClientStateJournal.StateEntry entry) {
		ChatFormatting color = switch (entry.kind()) {
			case INSTALL, ROLLBACK -> ChatFormatting.GREEN;
			case REMOVAL, DEACTIVATION -> ChatFormatting.RED;
			case FILE_RESTORE, REPAIR, RECOVERY_REVERT -> ChatFormatting.YELLOW;
			case UPDATE -> ChatFormatting.WHITE;
		};
		MutableComponent title = VersionedText.translatable("automodpack.stateHistory.kind." + entry.kind().name()).withStyle(color);
		long added = 0;
		long changed = 0;
		long removed = 0;
		for (ClientStateJournal.Change change : entry.changes()) {
			switch (change.kind()) {
				case ADDED -> added++;
				case CHANGED -> changed++;
				case REMOVED -> removed++;
			}
		}
		String summary = VersionedText.translatable("automodpack.stateHistory.entrySummary", DATE_FORMAT.format(entry.createdAt()), packName(entry.modpackId()), entry.state().size(), added, changed, removed)
				.getString();
		return new RowListWidget.Row(List.of(title, VersionedText.literal(truncateToWidth(this.font, summary, panelWidth(PANEL_WIDTH) - 16)).withStyle(ChatFormatting.GRAY)));
	}

	private MutableComponent restoreTooltip(ClientStateJournal.StateEntry selected, StateHistory.RestoreOption option) {
		if (selected == null) return VersionedText.translatable("automodpack.stateHistory.restorePickFirst");
		if (option == null) return VersionedText.translatable("automodpack.stateHistory.checking");
		return switch (option.restorability()) {
			case READY -> VersionedText.translatable("automodpack.stateHistory.restoreReady");
			case CURRENT -> VersionedText.translatable("automodpack.stateHistory.restoreCurrent");
			case NOT_KEPT -> VersionedText.translatable("automodpack.stateHistory.restoreNotKept");
			case INACTIVE_PACK -> VersionedText.translatable("automodpack.stateHistory.restoreInactive");
			case MIXED -> VersionedText.translatable("automodpack.stateHistory.restoreMixed");
		};
	}

	private PreservationVault.OriginalRestore gate(ClientStateJournal.TrackedFile file) {
		ClientStateJournal.StateEntry selected = selectedEntry();
		if (selected == null || file == null) return null;
		return fileGates.get(gateKey(selected, file));
	}

	private MutableComponent fileRestoreTooltip(PreservationVault.OriginalRestore gate) {
		if (gate == null) return VersionedText.translatable("automodpack.stateHistory.restorePickFirst");
		return switch (gate) {
			case AVAILABLE -> VersionedText.translatable("automodpack.stateHistory.restoreFileReady");
			case NOT_GAME_DIR -> VersionedText.translatable("automodpack.stateHistory.restoreFileManaged");
			case STILL_OWNED -> VersionedText.translatable("automodpack.stateHistory.restoreFileOwned");
			case INACTIVE_PACK -> VersionedText.translatable("automodpack.stateHistory.restoreFileManaged");
		};
	}

	private void load() {
		loading = true;
		work = ScreenManager.background(() -> {
			try {
				List<ClientStateJournal.StateEntry> loaded = controller.stateEntries();
				this.minecraft.execute(() -> loaded(loaded));
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private void loaded(List<ClientStateJournal.StateEntry> loaded) {
		if (closed) return;
		entries = List.copyOf(loaded);
		for (InstalledModpackController.Pack pack : controller.installed()) packNames.put(pack.modpackId(), pack.name());
		loading = false;
		busy = false;
		if (selectedSeq != null && entries.stream().noneMatch(entry -> entry.seq() == selectedSeq)) selectedSeq = null;
		rebuild();
	}

	private void select(long seq) {
		if (busy || loading) return;
		selectedSeq = seq;
		selectedFileIndex = -1;
		lastResult = null;
		rebuild();
		resolveRestorability(selectedEntry());
	}

	private void resolveRestorability(ClientStateJournal.StateEntry entry) {
		if (entry == null || restorabilityBySeq.containsKey(entry.seq())) return;
		work = ScreenManager.background(() -> {
			try {
				StateHistory.RestoreOption option = controller.stateRestorability(entry);
				this.minecraft.execute(() -> {
					if (closed) return;
					restorabilityBySeq.put(entry.seq(), option);
					rebuild();
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> {
					if (closed) return;
					restorabilityBySeq.put(entry.seq(), new StateHistory.RestoreOption(StateHistory.Restorability.MIXED, null));
					rebuild();
				});
			}
		});
	}

	private void pickFile(int index) {
		if (busy || loading) return;
		selectedFileIndex = index;
		lastResult = null;
		rebuild();
		resolveFileGate(selectedFile());
	}

	private void resolveFileGate(ClientStateJournal.TrackedFile file) {
		ClientStateJournal.StateEntry selected = selectedEntry();
		if (selected == null || file == null || fileGates.containsKey(gateKey(selected, file))) return;
		work = ScreenManager.background(() -> {
			try {
				PreservationVault.OriginalRestore gate = controller.stateFileGate(file.root(), file.path());
				this.minecraft.execute(() -> {
					if (closed) return;
					fileGates.put(gateKey(selected, file), gate);
					rebuild();
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> {
					if (closed) return;
					fileGates.put(gateKey(selected, file), PreservationVault.OriginalRestore.STILL_OWNED);
					rebuild();
				});
			}
		});
	}

	private static String gateKey(ClientStateJournal.StateEntry entry, ClientStateJournal.TrackedFile file) {
		return entry.seq() + "/" + file.root().name() + "/" + file.path();
	}

	private void restoreState() {
		ClientStateJournal.StateEntry entry = selectedEntry();
		StateHistory.RestoreOption option = entry == null ? null : restorabilityBySeq.get(entry.seq());
		if (entry == null || option == null || busy || option.restorability() != StateHistory.Restorability.READY) return;
		controller.restoreState(entry, option, packName(entry.modpackId()), this::reopenAfterFlow);
	}

	private void undoArrival() {
		ClientStateJournal.StateEntry entry = selectedEntry();
		if (entry == null || busy || !isUndoableArrival(entry)) return;
		InstalledModpackController.Pack pack = pack(entry.modpackId());
		if (pack == null) return;
		controller.remove(pack, this::reopenAfterFlow, this::reopenAfterFlow);
	}

	private void openFiles() {
		if (busy || selectedEntry() == null) return;
		mode = Mode.FILES;
		selectedFileIndex = -1;
		lastResult = null;
		rebuild();
	}

	private void showTimeline() {
		mode = Mode.TIMELINE;
		selectedFileIndex = -1;
		lastResult = null;
		rebuild();
	}

	private void restoreFile() {
		ClientStateJournal.TrackedFile file = selectedFile();
		if (file == null || busy) return;
		run(() -> controller.restoreStateFile(selectedSeq, file.root(), file.path()), true);
	}

	private void saveFileCopy() {
		ClientStateJournal.TrackedFile file = selectedFile();
		if (file == null || busy) return;
		run(() -> controller.saveStateFileCopy(selectedSeq, file.root(), file.path()), false);
	}

	private void run(StateOperation operation, boolean restoreAttempt) {
		busy = true;
		rebuild();
		work = ScreenManager.background(() -> {
			try {
				Path destination = operation.run();
				List<ClientStateJournal.StateEntry> refreshed = controller.stateEntries();
				this.minecraft.execute(() -> {
					lastResult = destination;
					lastResultRestore = restoreAttempt;
					loaded(refreshed);
				});
			} catch (Exception e) {
				this.minecraft.execute(() -> fail(e));
			}
		});
	}

	private void fail(Exception exception) {
		if (closed) return;
		loading = false;
		busy = false;
		if (entries == null) entries = List.of();
		rebuild();
		presentingFailure = true;
		ScreenManager.failure(FailureRequest.of(exception, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.CURRENT_SCREEN, null));
	}

	/** The flows behind Restore this state and Undo arrival run their own screens; they land back on a fresh history. */
	private void reopenAfterFlow() {
		ScreenImpl.setScreen(new StateHistoryScreen(parent, controller, closedCallback));
	}

	private void back() {
		if (closed) return;
		closed = true;
		cancelWork();
		closedCallback.run();
		ScreenImpl.setScreen(parent);
	}

	private void cancelWork() {
		Future<?> current = work;
		if (current != null && !current.isDone()) current.cancel(true);
	}

	private ClientStateJournal.StateEntry selectedEntry() {
		if (selectedSeq == null || entries == null) return null;
		return entries.stream().filter(entry -> entry.seq() == selectedSeq).findFirst().orElse(null);
	}

	private void selectRow(RowListWidget list, List<ClientStateJournal.StateEntry> newestFirst, ClientStateJournal.StateEntry selected) {
		if (selected == null) return;
		for (int index = 0; index < newestFirst.size(); index++)
			if (newestFirst.get(index).seq() == selected.seq()) {
				list.setSelected(list.children().get(index));
				list.revealRow(index);
				return;
			}
	}

	private List<ClientStateJournal.StateEntry> reversed() {
		List<ClientStateJournal.StateEntry> newestFirst = new ArrayList<>(entries == null ? List.of() : entries);
		Collections.reverse(newestFirst);
		return newestFirst;
	}

	private List<ClientStateJournal.TrackedFile> selectedFiles() {
		ClientStateJournal.StateEntry entry = selectedEntry();
		return entry == null ? List.of() : entry.state();
	}

	private ClientStateJournal.TrackedFile selectedFile() {
		List<ClientStateJournal.TrackedFile> files = selectedFiles();
		return selectedFileIndex >= 0 && selectedFileIndex < files.size() ? files.get(selectedFileIndex) : null;
	}

	private boolean isUndoableArrival(ClientStateJournal.StateEntry entry) {
		return entry.kind() == ClientStateJournal.Kind.INSTALL && entry.modpackId().equals(controller.activeModpackId()) && pack(entry.modpackId()) != null;
	}

	private InstalledModpackController.Pack pack(String modpackId) {
		return controller.installed().stream().filter(candidate -> candidate.modpackId().equals(modpackId)).findFirst().orElse(null);
	}

	private String packName(String modpackId) {
		String name = packNames.get(modpackId);
		return name == null ? modpackId : name;
	}

	private static String humanSize(long bytes) {
		if (bytes < 0) return "?";
		if (bytes < 1024) return bytes + " B";
		double value = bytes;
		for (String unit : new String[]{"KiB", "MiB", "GiB"}) {
			value /= 1024;
			if (value < 1024) return String.format(Locale.ROOT, "%.1f %s", value, unit);
		}
		return String.format(Locale.ROOT, "%.1f TiB", value / 1024);
	}

	private static String displayPath(Path path) {
		Path game = GameDirectory.current().toAbsolutePath().normalize();
		Path absolute = path.toAbsolutePath().normalize();
		if (absolute.startsWith(game)) return game.relativize(absolute).toString().replace('\\', '/');
		return absolute.toString().replace('\\', '/');
	}

	@Override
	public void removed() {
		if (presentingFailure) {
			presentingFailure = false;
			super.removed();
			return;
		}
		if (!closed) {
			closed = true;
			cancelWork();
			closedCallback.run();
		}
		super.removed();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.stateHistory.title").withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
		String description;
		if (loading) description = VersionedText.translatable("automodpack.stateHistory.loading").getString();
		else if (mode == Mode.TIMELINE) description = VersionedText.translatable("automodpack.stateHistory.description", entries == null ? 0 : entries.size()).getString();
		else {
			ClientStateJournal.StateEntry selected = selectedEntry();
			description = VersionedText.translatable("automodpack.stateHistory.filesDescription", selectedFiles().size(), selected == null ? "" : packName(selected.modpackId())).getString();
		}
		List<String> descriptionLines = wrapToWidth(this.font, description, this.width - 28, 2);
		for (int index = 0; index < descriptionLines.size(); index++)
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(descriptionLines.get(index)).withStyle(ChatFormatting.GRAY), this.width / 2, 28 + index * 12, TextColors.WHITE);
		if (busy) drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.stateHistory.working").withStyle(ChatFormatting.YELLOW), this.width / 2, 52, TextColors.WHITE);
		if (!loading && entries != null && entries.isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.stateHistory.empty").withStyle(ChatFormatting.GRAY), this.width / 2, 88, TextColors.WHITE);
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
