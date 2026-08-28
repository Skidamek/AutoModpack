package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.MOD_ID;
import static pl.skidam.automodpack_core.Constants.clientConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.stream.Stream;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.loader.PinnedMods;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;

/** Instance-wide list of live mods/ ids that stay loaded instead of the pack copy. */
public final class PinnedModsScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 500;
	private static final int FOOTER_WIDTH = ActionAreaLayout.FOOTER_RAIL;
	private static final int ROW_HEIGHT = 22;

	private final Screen parent;
	private final List<LiveMod> liveMods;
	private EditBox idField;
	private String typedId = "";
	private int page;

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
		ActionRow footer = actionRow(ActionAreaLayout.RowKind.FOOTER, secondaryAction(VersionedText.translatable("automodpack.back"), press -> ScreenImpl.setScreen(parent)));
		int listTop = 90;
		int pageSize = Math.max(1, (actionAreaTop(FOOTER_WIDTH, this.height - 28, footer) - 8 - listTop) / ROW_HEIGHT);
		boolean showPagination = rows.size() > pageSize;
		ActionRow navigation = showPagination ? actionRow(ActionAreaLayout.RowKind.NAVIGATION, navigationAction(VersionedText.literal(""), press -> {}),
				disabledNavigationAction(VersionedText.literal("")), navigationAction(VersionedText.literal(""), press -> {})) : null;
		if (showPagination) pageSize = Math.max(1, (actionAreaTop(FOOTER_WIDTH, this.height - 28, navigation, footer) - 8 - listTop) / ROW_HEIGHT);
		final int pageCount = Math.max(1, (int) Math.ceil((double) Math.max(rows.size(), 1) / pageSize));
		List<ActionRow> actions = new ArrayList<>();
		if (showPagination) {
			actions.add(actionRow(ActionAreaLayout.RowKind.NAVIGATION,
					navigationAction(VersionedText.translatable("automodpack.ui.previous"), press -> {
						if (page > 0) {
							page--;
							rebuild();
						}
					}),
					disabledNavigationAction(VersionedText.translatable("automodpack.ui.page", page + 1, pageCount)),
					navigationAction(VersionedText.translatable("automodpack.ui.next"), press -> {
						if (page < pageCount - 1) {
							page++;
							rebuild();
						}
					})));
		}
		actions.add(footer);
		if (page >= pageCount) page = pageCount - 1;
		int start = page * pageSize;
		for (int index = start; index < Math.min(rows.size(), start + pageSize); index++) {
			Row row = rows.get(index);
			int y = listTop + (index - start) * ROW_HEIGHT;
			Button button = buttonWidget(x, y, width, 20, rowLabel(row, width), press -> toggle(row));
			setTooltip(button, VersionedText.translatable(row.present ? "automodpack.pinnedMods.liveTooltip" : "automodpack.pinnedMods.missingTooltip"));
			this.addRenderableWidget(button);
		}

		List<Button> actionButtons = addActionArea(FOOTER_WIDTH, this.height - 28, actions.toArray(ActionRow[]::new));
		if (pageCount > 1) {
			actionButtons.get(0).active = page > 0;
			actionButtons.get(2).active = page < pageCount - 1;
		}
	}

	private List<Row> rows() {
		List<String> pins = currentPins();
		Set<String> listed = new LinkedHashSet<>(pins);
		List<Row> rows = new ArrayList<>();
		Set<String> covered = new LinkedHashSet<>();
		for (LiveMod mod : liveMods) {
			boolean pinned = PinnedMods.matches(listed, mod.ids());
			rows.add(new Row(mod.modId(), mod.fileName(), true, pinned, mod.ids()));
			if (pinned) covered.addAll(mod.ids());
		}
		for (String pin : pins) if (!covered.contains(pin)) rows.add(new Row(pin, "", false, true, Set.of(pin)));
		return rows;
	}

	private MutableComponent rowLabel(Row row, int width) {
		String raw = row.present
				? VersionedText.translatable(row.pinned ? "automodpack.pinnedMods.liveOn" : "automodpack.pinnedMods.liveOff", row.id, row.fileName).getString()
				: VersionedText.translatable("automodpack.pinnedMods.missing", row.id).getString();
		return VersionedText.literal(truncateToWidth(this.font, raw, Math.max(1, width - 8)));
	}

	private void toggle(Row row) {
		List<String> pins = currentPins();
		if (row.pinned) pins.removeIf(pin -> PinnedMods.matches(Set.of(pin), row.ids));
		else if (!pins.contains(row.id)) pins.add(row.id);
		ClientPreferences.setPinnedModIds(pins);
		rebuild();
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

	private void rebuild() {
		/*? if >=1.19.2 {*/
		this.rebuildWidgets();
		/*?} else {*/
		/*
		this.init(this.minecraft, this.width, this.height);
		*//*?}*/
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

	private static List<LiveMod> scanLiveMods() {
		List<LiveMod> mods = new ArrayList<>();
		try {
			ClientStorage storage = ClientStorage.open(GameDirectory.current());
			Path modsDirectory = storage.modsDirectory();
			if (!Files.isDirectory(modsDirectory, LinkOption.NOFOLLOW_LINKS)) return List.of();
			try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory()); var modCache = ModFileCache.open(storage.modMetadataDirectory()); Stream<Path> stream = Files.list(modsDirectory)) {
				for (Path path : stream.sorted().toList()) {
					if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
					FileInspection.Mod mod = modCache.getModOrNull(path, cache);
					if (mod == null || mod.IDs() == null || mod.IDs().isEmpty() || mod.id() == null || mod.id().isBlank()) continue;
					String canonical = mod.id().strip().toLowerCase(Locale.ROOT);
					if (MOD_ID.equals(canonical) || "automodpack_mod".equals(canonical)) continue;
					mods.add(new LiveMod(canonical, PinnedMods.ids(mod.IDs()), path.getFileName().toString()));
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to list live mods for pinned-mod settings", e);
		}
		return List.copyOf(mods);
	}

	private record LiveMod(String modId, Set<String> ids, String fileName) {}

	private record Row(String id, String fileName, boolean present, boolean pinned, Set<String> ids) {}
}
