package pl.skidam.automodpack.client.ui;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_loader_core.client.CertificateTrustStore;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Lists locally installed packs and keeps each pack's lifecycle actions beside its row. */
public final class InstalledModpacksScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 320;
	private static final int ROW_HEIGHT = 24;
	private static final int BUTTON_GAP = 4;
	private static final int MAX_ACTION_WIDTH = 60;

	private final Screen parent;
	private final ClientStorage storage;
	private final Set<String> upToDate = new HashSet<>();
	private final List<RowActions> rowActions = new ArrayList<>();
	private List<Entry> entries;
	private int page;
	private boolean managementInFlight;

	public InstalledModpacksScreen(Screen parent) {
		super(VersionedText.translatable("automodpack.packManager.title"));
		this.parent = parent;
		this.storage = ClientStorage.fromGameDirectory(GameDirectory.current());
		this.entries = loadEntries(storage);
	}

	@Override
	protected void init() {
		super.init();
		rowActions.clear();
		int rowWidth = panelWidth(PANEL_WIDTH);
		int x = panelLeft(PANEL_WIDTH);
		int listTop = 58;
		int actionY = this.height - 28;
		int listBottomWithoutPagination = actionY - BUTTON_GAP;
		int rowsWithoutPagination = Math.max(1, (listBottomWithoutPagination - listTop) / ROW_HEIGHT);
		boolean showPagination = entries.size() > rowsWithoutPagination;
		int paginationY = showPagination ? actionY - ROW_HEIGHT : -1;
		int listBottom = showPagination ? paginationY - BUTTON_GAP : listBottomWithoutPagination;
		int rowsPerPage = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
		int pageCount = Math.max(1, (int) Math.ceil((double) entries.size() / rowsPerPage));
		if (page >= pageCount) page = pageCount - 1;

		int actionWidth = Math.min(MAX_ACTION_WIDTH, Math.max(1, (rowWidth - BUTTON_GAP * 3) / 4));
		int labelWidth = rowWidth - actionWidth * 3 - BUTTON_GAP * 3;
		int start = page * rowsPerPage;
		for (int index = start; index < Math.min(entries.size(), start + rowsPerPage); index++) {
			Entry entry = entries.get(index);
			int y = listTop + (index - start) * ROW_HEIGHT;
			Button row = buttonWidget(x, y, labelWidth, 20, rowLabel(entry, labelWidth), press -> open(entry));
			this.addRenderableWidget(row);
			int updateX = x + labelWidth + BUTTON_GAP;
			Button update = buttonWidget(updateX, y, actionWidth, 20,
					VersionedText.translatable(upToDate.contains(entry.modpackId()) ? "automodpack.management.upToDate" : "automodpack.management.update"), press -> requestUpdate(entry));
			Button activeToggle = buttonWidget(updateX + actionWidth + BUTTON_GAP, y, actionWidth, 20,
					VersionedText.translatable(entry.active() ? "automodpack.management.deactivate" : "automodpack.management.activate"),
					press -> {
						if (entry.active()) requestActiveRemovalLike(entry, true);
						else requestActivation(entry);
					});
			Button remove = buttonWidget(updateX + (actionWidth + BUTTON_GAP) * 2, y, actionWidth, 20,
					VersionedText.translatable("automodpack.management.remove"), press -> requestRemoval(entry));
			this.addRenderableWidget(update);
			this.addRenderableWidget(activeToggle);
			this.addRenderableWidget(remove);
			rowActions.add(new RowActions(entry, update, activeToggle, remove));
		}
		updateManagementButtons();

		if (showPagination) {
			int paginationWidth = actionButtonWidth(PANEL_WIDTH, 3);
			Button previous = buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 3, 0), paginationY, paginationWidth, 20,
					VersionedText.translatable("automodpack.ui.previous"), press -> {
						if (page > 0) {
							page--;
							rebuild();
						}
					});
			previous.active = page > 0;
			this.addRenderableWidget(previous);
			Button pageLabel = buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 3, 1), paginationY, paginationWidth, 20,
					VersionedText.translatable("automodpack.ui.page", page + 1, pageCount), press -> {});
			pageLabel.active = false;
			this.addRenderableWidget(pageLabel);
			Button next = buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 3, 2), paginationY, paginationWidth, 20,
					VersionedText.translatable("automodpack.ui.next"), press -> {
						if (page < pageCount - 1) {
							page++;
							rebuild();
						}
					});
			next.active = page < pageCount - 1;
			this.addRenderableWidget(next);
		}

		int footerWidth = actionButtonWidth(PANEL_WIDTH, 2);
		this.addRenderableWidget(buttonWidget(actionButtonX(PANEL_WIDTH, 2, 0), actionY, footerWidth, 20,
				VersionedText.translatable("automodpack.packManager.localStorage"), press -> ScreenImpl.setScreen(new ClientStorageMaintenanceScreen(this, storage))));
		this.addRenderableWidget(buttonWidget(actionButtonX(PANEL_WIDTH, 2, 1), actionY, footerWidth, 20,
				VersionedText.translatable("automodpack.back"), press -> ScreenImpl.setScreen(parent)));
	}

	private void open(Entry entry) {
		ScreenImpl.setScreen(ModpackSelectionScreen.forInstalledRecord(this, entry.record(), entries.size() > 1));
	}

	private void requestUpdate(Entry entry) {
		if (!entry.active() || !entry.connectionAvailable() || !beginManagement()) return;
		upToDate.remove(entry.modpackId());
		DownloadClient.NET_EXECUTOR.execute(() -> {
			ModpackUpdater updater = null;
			DownloadClient downloadClient = null;
			try {
				ConnectionJsons.ConnectionInfo stored = ConnectionStore.getConnection(storage, entry.modpackId());
				if (stored.connectionMode == null || stored.origin == null || stored.endpoint == null) throw new IOException("Saved modpack connection is unavailable");
				ConnectionJsons.ConnectionInfo connection = new ConnectionJsons.ConnectionInfo(stored.origin, stored.endpoint, stored.connectionMode,
						CertificateTrustStore.getFingerprint(stored.origin), null);
				Secrets.Secret secret = SecretsStore.getClientSecret(storage, entry.modpackId(), stored.origin);
				if (secret == null) secret = Secrets.anonymousSecret();
				ModpackUtils.ManifestFetchResult result = ModpackUtils.requestServerModpackContent(storage, connection, secret, true);
				if (!result.successful()) throw new IOException(result.failure() == null ? "Could not fetch the latest modpack generation" : result.failure().getMessage(), result.failure());
				downloadClient = result.client();
				GenerationRecord downloaded = GenerationRecord.fromFields(result.content());
				if (!entry.modpackId().equals(downloaded.manifest().modpackId())) throw new IOException("Downloaded modpack identity does not match the installed pack");
				SelectionIntent savedSelection = new ClientSelectionStore(storage.selectionFile()).get(entry.modpackId()).orElse(null);
				SelectedModpackTarget target = savedSelection == null
						? SelectedModpackTarget.prepareDefault(result.content(), ClientPlatform.current())
						: SelectedModpackTarget.prepare(result.content(), savedSelection, savedSelection, ClientPlatform.current());
				updater = new ModpackUpdater(target, connection, secret, storage, downloadClient);
				downloadClient = null;
				ModpackUtils.UpdateCheckResult updateResult = ModpackUtils.isUpdate(target.flatTarget(), storage);
				if (!updater.requiresUpdateBeforeLogin(updateResult)) {
					updater.close();
					upToDate.add(entry.modpackId());
					this.minecraft.execute(() -> {
						endManagement();
						rebuild();
					});
					return;
				}
				updater.processModpackUpdate(updateResult);
				endManagement();
			} catch (Exception e) {
				if (updater != null) updater.close();
				if (downloadClient != null) downloadClient.close();
				endManagement();
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
		});
	}

	private void requestRemoval(Entry entry) {
		if (entry.active()) requestActiveRemovalLike(entry, false);
		else requestInactiveRemoval(entry);
	}

	private void requestActivation(Entry entry) {
		if (entry.active() || !beginManagement()) return;
		try {
			SelectionIntent savedSelection = new ClientSelectionStore(storage.selectionFile()).get(entry.modpackId()).orElse(null);
			SelectionIntent targetSelection = savedSelection == null ? GroupSelectionResolver.defaultIntent(entry.record().manifest()) : savedSelection;
			CachedModpackSwitch.start(storage, entry.record(), savedSelection, targetSelection, entry.name(), false, this::endManagement);
		} catch (RuntimeException e) {
			endManagement();
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
		}
	}

	private void requestActiveRemovalLike(Entry entry, boolean deactivation) {
		if (!entry.active() || !beginManagement()) return;
		ModpackUpdater updater;
		try {
			updater = new ModpackUpdater(null, null, storage);
		} catch (RuntimeException e) {
			endManagement();
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			return;
		}
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				UpdatePreview preview = deactivation ? updater.previewDeactivation() : updater.previewRemoval();
				new ScreenManager().preview(preview, entry.name(),
						(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> executeActiveRemovalLike(updater, deactivation)),
						(Runnable) () -> {
							updater.close();
							endManagement();
						}, false, Map.of());
			} catch (Exception e) {
				updater.close();
				endManagement();
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
		});
	}

	private void requestInactiveRemoval(Entry entry) {
		if (!beginManagement()) return;
		try {
			UpdatePlan plan = new UpdatePlan(entry.modpackId(), GenerationTarget.from(entry.record()), List.of(), List.of(), null, Set.of(), List.of(), List.of(), List.of(), List.of());
			UpdatePreview preview = new UpdatePreview(plan, List.of(), new UpdatePreview.GroupConsequences(Set.of(), Set.of(), Set.of()), "",
					new ClientGenerationStore(storage).patchNotesHistory(entry.record().metadata().generationId()), UpdatePreview.Mode.REMOVAL);
			new ScreenManager().preview(preview, entry.name(),
					(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> executeInactiveRemoval(entry)),
					(Runnable) this::endManagement, false, Map.of());
		} catch (Exception e) {
			endManagement();
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
		}
	}

	private void executeActiveRemovalLike(ModpackUpdater updater, boolean deactivation) {
		try {
			if ((deactivation ? updater.deactivateModpack() : updater.removeModpack()).success()) new ScreenManager().title();
			else new ScreenManager().error("automodpack.error.critical", deactivation ? "automodpack.error.deactivationIncomplete" : "automodpack.error.removalIncomplete", "automodpack.error.logs");
		} catch (Exception e) {
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
		} finally {
			updater.close();
		}
	}

	private void executeInactiveRemoval(Entry entry) {
		try {
			new ClientGenerationStore(storage).forgetModpack(entry.modpackId());
			this.minecraft.execute(() -> ScreenImpl.setScreen(new InstalledModpacksScreen(parent)));
		} catch (Exception e) {
			endManagement();
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
		}
	}

	private boolean beginManagement() {
		if (managementInFlight) return false;
		managementInFlight = true;
		updateManagementButtons();
		return true;
	}

	private void endManagement() {
		managementInFlight = false;
		updateManagementButtons();
	}

	private void updateManagementButtons() {
		for (RowActions actions : rowActions) {
			actions.update().active = !managementInFlight && actions.entry().active() && actions.entry().connectionAvailable();
			actions.activeToggle().active = !managementInFlight;
			actions.remove().active = !managementInFlight;
		}
	}

	private void rebuild() {
		/*? if >=1.19.2 {*/
		this.rebuildWidgets();
		/*?} else {*/
		/*
		this.init(this.minecraft, this.width, this.height);
		*//*?}*/
	}

	private MutableComponent rowLabel(Entry entry, int width) {
		String state = VersionedText.translatable(entry.active() ? "automodpack.packManager.activeMarker" : "automodpack.packManager.reviewMarker").getString();
		return VersionedText.literal(truncateToWidth(this.font, entry.name() + "  " + state, width - 12))
				.withStyle(entry.active() ? ChatFormatting.GREEN : ChatFormatting.WHITE);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.packManager.title").withStyle(ChatFormatting.BOLD), this.width / 2, 16, TextColors.WHITE);
		String description = entries.isEmpty()
				? VersionedText.translatable("automodpack.packManager.empty").getString()
				: VersionedText.translatable("automodpack.packManager.description").getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, description, this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, 32, TextColors.WHITE);
		if (!entries.isEmpty()) {
			String active = entries.stream().filter(Entry::active).findFirst()
					.map(entry -> VersionedText.translatable("automodpack.packManager.active", entry.name()).getString())
					.orElse(VersionedText.translatable("automodpack.packManager.noActive").getString());
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, active, this.width - 20)).withStyle(ChatFormatting.YELLOW), this.width / 2, 44, TextColors.WHITE);
		}
	}

	private static List<Entry> loadEntries(ClientStorage storage) {
		String activeId = "";
		try {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			activeId = state == null ? "" : state.modpackId;
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not read the active modpack state; showing installed records without an active marker", e);
		}
		try {
			String selectedId = activeId;
			return new ClientGenerationStore(storage).installedRecords().stream()
					.sorted(Comparator.comparing(record -> name(record), String.CASE_INSENSITIVE_ORDER))
					.map(record -> new Entry(record, record.manifest().modpackId().equals(selectedId), hasConnection(storage, record.manifest().modpackId())))
					.toList();
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not enumerate installed modpacks", e);
			return List.of();
		}
	}

	private static boolean hasConnection(ClientStorage storage, String modpackId) {
		try {
			ConnectionJsons.ConnectionRecordFields fields = ConnectionStore.read(storage, modpackId);
			return fields.connection != null && fields.connection.isComplete();
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private static String name(GenerationRecord record) {
		return record.manifest().modpackName().isBlank() ? record.manifest().modpackId() : record.manifest().modpackName();
	}

	private record Entry(GenerationRecord record, boolean active, boolean connectionAvailable) {
		private String modpackId() {
			return record.manifest().modpackId();
		}

		private String name() {
			return InstalledModpacksScreen.name(record);
		}
	}

	private record RowActions(Entry entry, Button update, Button activeToggle, Button remove) {}
}
