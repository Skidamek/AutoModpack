package pl.skidam.automodpack.client.ui;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.util.Comparator;
import java.util.List;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

/** Lists locally installed packs without changing the active projection. */
public final class InstalledModpacksScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 310;
	private static final int ROW_HEIGHT = 24;

	private final Screen parent;
	private final ClientStorage storage;
	private final List<Entry> entries;
	private int page;

	public InstalledModpacksScreen(Screen parent) {
		super(VersionedText.translatable("automodpack.packManager.title"));
		this.parent = parent;
		this.storage = ClientStorage.fromGameDirectory(SmartFileUtils.CWD);
		this.entries = loadEntries(storage);
	}

	@Override
	protected void init() {
		super.init();
		int rowWidth = panelWidth(PANEL_WIDTH);
		int x = panelLeft(PANEL_WIDTH);
		int listTop = 58;
		int listBottom = this.height - 54;
		int rowsPerPage = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);
		int pageCount = Math.max(1, (int) Math.ceil((double) entries.size() / rowsPerPage));
		if (page >= pageCount) page = pageCount - 1;

		int start = page * rowsPerPage;
		for (int index = start; index < Math.min(entries.size(), start + rowsPerPage); index++) {
			Entry entry = entries.get(index);
			Button row = buttonWidget(x, listTop + (index - start) * ROW_HEIGHT, rowWidth, 20, rowLabel(entry), press -> open(entry));
			this.addRenderableWidget(row);
		}

		if (pageCount > 1) {
			int pageY = listBottom + 4;
			int pageWidth = actionButtonWidth(PANEL_WIDTH, 3);
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 3, 0), pageY, pageWidth, 20,
					VersionedText.literal("< Prev"), press -> {
						if (page > 0) {
							page--;
							rebuild();
						}
					}));
			Button pageLabel = buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 3, 1), pageY, pageWidth, 20,
					VersionedText.literal((page + 1) + " / " + pageCount), press -> {});
			pageLabel.active = false;
			this.addRenderableWidget(pageLabel);
			this.addRenderableWidget(buttonWidget(centeredActionButtonX(PANEL_WIDTH, 3, 3, 2), pageY, pageWidth, 20,
					VersionedText.literal("Next >"), press -> {
						if (page < pageCount - 1) {
							page++;
							rebuild();
						}
					}));
		}

		int actionWidth = actionButtonWidth(PANEL_WIDTH, 2);
		int actionY = this.height - 28;
		this.addRenderableWidget(buttonWidget(centeredActionButtonX(PANEL_WIDTH, 2, 2, 0), actionY, actionWidth, 20,
				VersionedText.translatable("automodpack.packManager.localStorage"), press -> ScreenImpl.setScreen(new ClientStorageMaintenanceScreen(this, storage))));
		this.addRenderableWidget(buttonWidget(centeredActionButtonX(PANEL_WIDTH, 2, 2, 1), actionY, actionWidth, 20,
				VersionedText.translatable("automodpack.back"), press -> ScreenImpl.setScreen(parent)));
	}

	private void open(Entry entry) {
		// Opening a row only enters the existing selection/review flow. It never changes active-state.json.
		ScreenImpl.setScreen(ModpackSelectionScreen.forInstalledRecord(this, entry.record(), entries.size() > 1));
	}

	private void rebuild() {
		/*? if >=1.19.2 {*/
		this.rebuildWidgets();
		/*?} else {*/
		/*
		this.init(this.minecraft, this.width, this.height);
		*//*?}*/
	}

	private MutableComponent rowLabel(Entry entry) {
		String name = entry.record().manifest().modpackName().isBlank() ? entry.record().manifest().modpackId() : entry.record().manifest().modpackName();
		String state = entry.active() ? "  [active]" : "  [review switch]";
		String connection = entry.connectionAvailable() ? "  connected" : "  local record";
		return VersionedText.literal(truncateToWidth(this.font, name + state + connection, panelWidth(PANEL_WIDTH) - 12)).withStyle(entry.active() ? ChatFormatting.GREEN : ChatFormatting.WHITE);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.packManager.title").withStyle(ChatFormatting.BOLD), this.width / 2, 16, TextColors.WHITE);
		String description = entries.isEmpty()
				? VersionedText.translatable("automodpack.packManager.empty").getString()
				: VersionedText.translatable("automodpack.packManager.description").getString();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, description, this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, 32, TextColors.WHITE);
		if (!entries.isEmpty()) {
			String active = entries.stream().filter(Entry::active).findFirst().map(entry -> {
				String name = entry.record().manifest().modpackName();
				return VersionedText.translatable("automodpack.packManager.active", name.isBlank() ? entry.record().manifest().modpackId() : name).getString();
			}).orElse(VersionedText.translatable("automodpack.packManager.noActive").getString());
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
					.sorted(Comparator.comparing(record -> record.manifest().modpackName().isBlank() ? record.manifest().modpackId() : record.manifest().modpackName(), String.CASE_INSENSITIVE_ORDER))
					.map(record -> new Entry(record, record.manifest().modpackId().equals(selectedId), hasConnection(storage, record.manifest().modpackId())))
					.toList();
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Could not enumerate installed modpacks", e);
			return List.of();
		}
	}

	private static boolean hasConnection(ClientStorage storage, String modpackId) {
		try {
			Jsons.ConnectionRecordFields fields = ConnectionStore.read(storage, modpackId);
			return fields.connection != null && fields.connection.isComplete();
		} catch (IOException | RuntimeException e) {
			return false;
		}
	}

	private record Entry(GenerationRecord record, boolean active, boolean connectionAvailable) {}
}
