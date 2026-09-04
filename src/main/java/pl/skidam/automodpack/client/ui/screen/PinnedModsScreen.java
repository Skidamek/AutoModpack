package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.MOD_ID;
import static pl.skidam.automodpack_core.Constants.clientConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;
import net.minecraft.util.Util;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.RowListWidget;
import pl.skidam.automodpack_core.change.PlatformReferences;
import pl.skidam.automodpack_core.loader.PinnedMods;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
import pl.skidam.automodpack_core.utils.cache.PlatformCache;

/** Instance-wide list of live mods/ ids that stay loaded instead of the pack copy. */
public final class PinnedModsScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 500;
	private static final int FOOTER_WIDTH = ActionAreaLayout.FOOTER_RAIL;
	private static final int ROW_HEIGHT = 22;
	private static final int LIST_TOP = 90;

	private final Screen parent;
	private final List<LiveMod> liveMods;
	private final Map<String, List<PlatformReferences.Page>> platformPagesByRowKey = new HashMap<>();
	private EditBox idField;
	private String typedId = "";
	private String selectedKey;
	private boolean closed;

	public PinnedModsScreen(Screen parent) {
		super(VersionedText.translatable("automodpack.pinnedMods.title"));
		this.parent = parent;
		this.liveMods = scanLiveMods();
	}

	@Override
	protected void init() {
		super.init();
		int width = panelWidth(PANEL_WIDTH);
		int x = panelLeft(PANEL_WIDTH);
		int fieldY = 64;
		int addWidth = Math.max(64, this.font.width(VersionedText.translatable("automodpack.pinnedMods.add").getString()) + 16);
		this.idField = fieldWidget(x, fieldY, width - addWidth - ActionAreaLayout.SEAM, VersionedText.translatable("automodpack.pinnedMods.field"), null, 128);
		this.idField.setValue(typedId);
		this.idField.setResponder(value -> typedId = value);
		this.addRenderableWidget(buttonWidget(x + width - addWidth, fieldY, addWidth, 20, VersionedText.translatable("automodpack.pinnedMods.add"), press -> addTypedId()));

		List<Row> rows = rows();
		Row selected = selected(rows);
		List<PlatformReferences.Page> platformPages = selected == null ? List.of() : platformPagesByRowKey.getOrDefault(key(selected), List.of());
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), press -> ScreenImpl.setScreen(parent)));
		List<ActionRow> actions = new ArrayList<>();
		if (!platformPages.isEmpty()) actions.add(platformRow(platformPages));
		actions.add(footer);
		ActionRow[] actionRows = actions.toArray(ActionRow[]::new);
		addActionArea(FOOTER_WIDTH, this.height - 28, actionRows);
		int listBottom = actionAreaTop(FOOTER_WIDTH, this.height - 28, actionRows) - 8;
		int rowWidth = Math.max(1, width - 8);
		List<RowListWidget.Row> listRows = new ArrayList<>(rows.size());
		for (Row row : rows) {
			listRows.add(new RowListWidget.Row(List.of(rowLabel(row, rowWidth)), VersionedText.translatable(row.present ? "automodpack.pinnedMods.liveTooltip" : "automodpack.pinnedMods.missingTooltip")));
		}
		RowListWidget list = new RowListWidget(this.minecraft, this.width, this.height, width, LIST_TOP, listBottom, ROW_HEIGHT, listRows, index -> toggle(rows.get(index)), this::showComponentTooltip);
		this.addRenderableWidget(list);
	}

	private List<Row> rows() {
		List<String> pins = currentPins();
		Set<String> listed = new LinkedHashSet<>(pins);
		List<Row> rows = new ArrayList<>();
		Set<String> covered = new LinkedHashSet<>();
		for (LiveMod mod : liveMods) {
			boolean pinned = PinnedMods.matches(listed, mod.ids());
			rows.add(new Row(mod.modId(), mod.fileName(), true, pinned, mod.ids(), mod.path()));
			if (pinned) covered.addAll(mod.ids());
		}
		for (String pin : pins) if (!covered.contains(pin)) rows.add(new Row(pin, "", false, true, Set.of(pin), null));
		return rows;
	}

	private MutableComponent rowLabel(Row row, int width) {
		String raw = row.present
				? VersionedText.translatable(row.pinned ? "automodpack.pinnedMods.liveOn" : "automodpack.pinnedMods.liveOff", row.id, row.fileName).getString()
				: VersionedText.translatable("automodpack.pinnedMods.missing", row.id).getString();
		return VersionedText.literal(truncateToWidth(this.font, raw, Math.max(1, width - 8))).withStyle(key(row).equals(selectedKey) ? ChatFormatting.GREEN : ChatFormatting.WHITE);
	}

	private void toggle(Row row) {
		selectedKey = key(row);
		resolvePlatformPages(row);
		List<String> pins = currentPins();
		if (row.pinned) pins.removeIf(pin -> PinnedMods.matches(Set.of(pin), row.ids));
		else if (!pins.contains(row.id)) pins.add(row.id);
		ClientPreferences.setPinnedModIds(pins);
		rebuild();
	}

	private Row selected(List<Row> rows) {
		if (selectedKey == null) return null;
		return rows.stream().filter(row -> key(row).equals(selectedKey)).findFirst().orElse(null);
	}

	private static String key(Row row) {
		return row.present ? row.fileName : row.id;
	}

	/** Cache-only Modrinth/CurseForge page lookup for the selected live mod file; no buttons when nothing was cached. */
	private void resolvePlatformPages(Row row) {
		if (row.path == null || platformPagesByRowKey.containsKey(key(row))) return;
		DownloadClient.NET_EXECUTOR.execute(() -> {
			List<PlatformReferences.Page> pages = cachedPlatformPages(row.path);
			this.minecraft.execute(() -> {
				if (closed) return;
				platformPagesByRowKey.put(key(row), pages);
				rebuild();
			});
		});
	}

	private ActionRow platformRow(List<PlatformReferences.Page> pages) {
		List<ActionDefinition> definitions = new ArrayList<>();
		for (PlatformReferences.Page page : pages) definitions.add(optionalAction(VersionedText.translatable("automodpack.browser." + page.platform()), button -> Util.getPlatform().openUri(page.url())));
		return actionRow(ActionAreaLayout.RowKind.AUXILIARY, definitions.toArray(ActionDefinition[]::new));
	}

	private static List<PlatformReferences.Page> cachedPlatformPages(Path file) {
		try {
			ClientStorage storage = ClientStorage.open(GameDirectory.current());
			try (FileCache hashes = FileCache.open(storage.fileCacheDirectory()); PlatformCache cache = PlatformCache.open(storage.platformCacheDirectory())) {
				String sha1 = hashes.getHashOrNull(file);
				return sha1 == null ? List.of() : PlatformReferences.cachedPages(cache, sha1);
			}
		} catch (IOException | RuntimeException e) {
			return List.of();
		}
	}

	private void addTypedId() {
		List<String> added = PinnedMods.normalize(List.of(typedId));
		if (added.isEmpty()) return;
		List<String> pins = currentPins();
		for (String id : added) if (!pins.contains(id)) pins.add(id);
		ClientPreferences.setPinnedModIds(pins);
		typedId = "";
		rebuild();
	}

	private List<String> currentPins() {
		return new ArrayList<>(PinnedMods.normalize(clientConfig == null ? List.of() : clientConfig.pinnedModIds));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.pinnedMods.title").withStyle(ChatFormatting.BOLD), this.width / 2, 12, TextColors.WHITE);
		int width = panelWidth(PANEL_WIDTH);
		List<String> warning = wrapToWidth(this.font, VersionedText.translatable("automodpack.pinnedMods.warning").getString(), width, 3);
		int y = 26;
		for (String line : warning) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(ChatFormatting.YELLOW), this.width / 2, y, TextColors.WHITE);
			y += 11;
		}
		if (rows().isEmpty()) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.pinnedMods.empty").withStyle(ChatFormatting.GRAY), this.width / 2, 94, TextColors.WHITE);
		}
	}

	@Override
	public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
		if (idField != null && idField.isFocused() && isEnterKey(keyCode)) {
			addTypedId();
			return true;
		}
		return super.onKeyPress(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(() -> ScreenImpl.setScreen(parent));
	}

	@Override
	public void removed() {
		closed = true;
		super.removed();
	}

	private static List<LiveMod> scanLiveMods() {
		List<LiveMod> mods = new ArrayList<>();
		try {
			ClientStorage storage = ClientStorage.open(GameDirectory.current());
			Path modsDirectory = storage.modsDirectory();
			if (!Files.isDirectory(modsDirectory, LinkOption.NOFOLLOW_LINKS)) return List.of();
			try (var cache = FileCache.open(storage.fileCacheDirectory()); var modCache = ModFileCache.open(storage.modCacheDirectory()); Stream<Path> stream = Files.list(modsDirectory)) {
				for (Path path : stream.sorted().toList()) {
					if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
					FileInspection.Mod mod = modCache.getModOrNull(path, cache);
					if (mod == null || mod.IDs() == null || mod.IDs().isEmpty() || mod.id() == null || mod.id().isBlank()) continue;
					String canonical = mod.id().strip().toLowerCase(Locale.ROOT);
					if (MOD_ID.equals(canonical) || "automodpack_mod".equals(canonical)) continue;
					mods.add(new LiveMod(canonical, PinnedMods.ids(mod.IDs()), path.getFileName().toString(), path));
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to list live mods for pinned-mod settings", e);
		}
		return List.copyOf(mods);
	}

	private record LiveMod(String modId, Set<String> ids, String fileName, Path path) {}

	private record Row(String id, String fileName, boolean present, boolean pinned, Set<String> ids, Path path) {}
}
