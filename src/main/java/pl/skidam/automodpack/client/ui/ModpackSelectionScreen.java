package pl.skidam.automodpack.client.ui;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.clientConfig;
import static pl.skidam.automodpack_core.Constants.hostModpackContentFile;

import java.nio.file.Path;
import java.util.*;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ClientSelectionManager;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;

/**
 * Lets the player pick which optional groups of a modpack they want. Deliberately built out of
 * plain buttons rather than a scrolling list widget: buttons are the one widget whose API is
 * stable across every Minecraft version this mod targets.
 *
 * Changes only take effect on the next launch, because mods are loaded during preload.
 */
public class ModpackSelectionScreen extends VersionedScreen {

	private static final int ROW_HEIGHT = 22;
	private static final int ROW_WIDTH = 280;

	private final Screen parent;
	private final String modpackId;
	private final String modpackName;
	private final Map<String, Jsons.ModpackContentFields.ModpackGroupFields> groups;

	// What the player has actually ticked; resolved is what that implies once required groups,
	// dependencies and conflicts are applied.
	private final Set<String> chosen = new LinkedHashSet<>();
	private Set<String> resolved;
	private final List<String> rows = new ArrayList<>();

	private int page = 0;
	private int rowsPerPage = 1;
	private boolean saved = false;

	public ModpackSelectionScreen(Screen parent, String modpackId, String modpackName,
			Map<String, Jsons.ModpackContentFields.ModpackGroupFields> groups) {
		super(VersionedText.translatable("automodpack.selection.title"));
		this.parent = parent;
		this.modpackId = modpackId;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.groups = groups == null ? Map.of() : groups;

		Set<String> saved = ClientSelectionManager.getManager().getSelection(modpackId).map(selection -> selection.selectedGroups)
				.orElseGet(() -> ClientSelectionManager.defaultSelection(this.groups));
		this.chosen.addAll(saved);
		this.resolved = ClientSelectionManager.resolve(this.groups, this.chosen);
		this.rows.addAll(this.groups.keySet());
	}

	/**
	 * Builds the screen for whichever modpack the client currently has selected. Returns the parent
	 * untouched when there is nothing to choose, so callers can hand the result straight to setScreen.
	 */
	public static Screen forSelectedModpack(Screen parent) {
		return forModpackId(parent, clientConfig == null ? null : clientConfig.selectedModpackId);
	}

	/**
	 * Builds the screen for the modpack belonging to a given Minecraft server address, or returns the
	 * parent when that server is not a known AutoModpack modpack with optional groups.
	 */
	public static Screen forServerAddress(Screen parent, String serverAddress) {
		return forModpackId(parent, modpackIdForServer(serverAddress));
	}

	private static Screen forModpackId(Screen parent, String modpackId) {
		if (modpackId == null || modpackId.isBlank()) {
			LOGGER.info("No modpack selected, nothing to configure");
			return parent;
		}

		Path contentFile = ModpackUtils.getModpackPath(modpackId).resolve(hostModpackContentFile.getFileName());
		Jsons.ModpackContentFields content = ModpackContentTools.read(contentFile);
		if (content == null || content.groups == null || content.groups.isEmpty()) {
			LOGGER.info("Modpack {} declares no groups", modpackId);
			return parent;
		}

		return new ModpackSelectionScreen(parent, modpackId, content.modpackName, content.groups);
	}

	public static boolean hasGroupsToConfigure() {
		return modpackHasOptionalGroups(clientConfig == null ? null : clientConfig.selectedModpackId);
	}

	/** Whether the server at the given address is a known modpack the player can configure groups for. */
	public static boolean serverHasGroupsToConfigure(String serverAddress) {
		return modpackHasOptionalGroups(modpackIdForServer(serverAddress));
	}

	private static boolean modpackHasOptionalGroups(String modpackId) {
		if (modpackId == null || modpackId.isBlank()) return false;

		Jsons.ModpackContentFields content = ModpackContentTools.read(ModpackUtils.getModpackPath(modpackId).resolve(hostModpackContentFile.getFileName()));
		if (content == null || content.groups == null) return false;
		// Nothing worth showing a button for when every group is mandatory.
		return content.groups.values().stream().anyMatch(group -> group != null && !group.required);
	}

	/**
	 * Maps a Minecraft server address to the modpack the client installed from it. The client config
	 * records each modpack's origin (the address the player connected to), so a match there means we
	 * already downloaded that server's modpack and know its groups.
	 */
	private static String modpackIdForServer(String serverAddress) {
		if (serverAddress == null || clientConfig == null || clientConfig.modpackConnections == null) return null;

		String wanted = normalizeAddress(serverAddress);
		// A bare host with no port is ambiguous between saved modpacks, so it may fall back to a
		// host-only match; an address that specifies a port must match that port exactly, otherwise
		// the button can open and save selections for the wrong modpack among several on one host.
		boolean addressHasPort = serverAddress.lastIndexOf(':') > 0;
		String wantedHost = normalizeAddress(hostOnly(serverAddress));
		for (var entry : clientConfig.modpackConnections.entrySet()) {
			var connection = entry.getValue();
			if (connection == null || connection.origin == null) continue;
			boolean exactMatch = normalizeAddress(connection.origin.getHostString() + ":" + connection.origin.getPort()).equals(wanted);
			boolean hostOnlyMatch = !addressHasPort && normalizeAddress(connection.origin.getHostString()).equals(wantedHost);
			if (exactMatch || hostOnlyMatch) {
				return entry.getKey();
			}
		}
		return null;
	}

	private static String normalizeAddress(String address) {
		return address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
	}

	private static String hostOnly(String address) {
		if (address == null) return "";
		int colon = address.lastIndexOf(':');
		return colon > 0 ? address.substring(0, colon) : address;
	}

	@Override
	protected void init() {
		super.init();

		// Once saved, the screen becomes a restart prompt: the new selection only takes effect after
		// a relaunch, so there is nothing left to toggle here.
		if (saved) {
			this.addRenderableWidget(buttonWidget(this.width / 2 - 155, this.height / 2 + 20, 150, 20,
					VersionedText.translatable("automodpack.selection.restartNow").withStyle(ChatFormatting.BOLD), press -> this.minecraft.stop()));
			this.addRenderableWidget(buttonWidget(this.width / 2 + 5, this.height / 2 + 20, 150, 20,
					VersionedText.translatable("automodpack.selection.later"), press -> this.minecraft.gui.setScreen(parent)));
			return;
		}

		int listTop = 50;
		int listBottom = this.height - 60;
		rowsPerPage = Math.max(1, (listBottom - listTop) / ROW_HEIGHT);

		int pageCount = Math.max(1, (int) Math.ceil((double) rows.size() / rowsPerPage));
		if (page >= pageCount) page = pageCount - 1;

		int x = this.width / 2 - ROW_WIDTH / 2;
		int start = page * rowsPerPage;

		for (int i = start; i < Math.min(rows.size(), start + rowsPerPage); i++) {
			String groupId = rows.get(i);
			var group = groups.get(groupId);
			int y = listTop + (i - start) * ROW_HEIGHT;

			Button button = buttonWidget(x, y, ROW_WIDTH, 20, rowLabel(groupId, group), press -> toggle(groupId));
			// Required groups are shown so the player can see what they are getting, but not togglable.
			button.active = group != null && !group.required;
			MutableComponent tooltip = rowTooltip(group);
			// A disabled button still shows its tooltip, so required groups keep their description on hover.
			if (tooltip != null) setTooltip(button, tooltip);
			this.addRenderableWidget(button);
		}

		if (pageCount > 1) {
			this.addRenderableWidget(buttonWidget(x, listBottom + 4, 60, 20, VersionedText.literal("< Prev"), press -> {
				if (page > 0) {
					page--;
					rebuild();
				}
			}));
			this.addRenderableWidget(buttonWidget(x + ROW_WIDTH - 60, listBottom + 4, 60, 20, VersionedText.literal("Next >"), press -> {
				if (page < pageCount - 1) {
					page++;
					rebuild();
				}
			}));
		}

		this.addRenderableWidget(buttonWidget(this.width / 2 - 155, this.height - 28, 100, 20, VersionedText.translatable("automodpack.selection.reset"),
				press -> {
					chosen.clear();
					chosen.addAll(ClientSelectionManager.defaultSelection(groups));
					reresolve();
				}));

		this.addRenderableWidget(buttonWidget(this.width / 2 - 50, this.height - 28, 100, 20, VersionedText.translatable("automodpack.selection.cancel"),
				press -> this.minecraft.gui.setScreen(parent)));

		this.addRenderableWidget(buttonWidget(this.width / 2 + 55, this.height - 28, 100, 20,
				VersionedText.translatable("automodpack.selection.save").withStyle(ChatFormatting.BOLD), press -> save()));
	}

	/**
	 * Ticking a group that conflicts with an already-selected one wins the conflict, which is what
	 * a player expects from the click they just made. The resolver still has the final say.
	 */
	private void toggle(String groupId) {
		if (resolved.contains(groupId)) {
			chosen.remove(groupId);
		} else {
			groups.keySet().stream().filter(other -> ClientSelectionManager.conflicts(groups, other, groupId)).forEach(chosen::remove);
			chosen.add(groupId);
		}
		reresolve();
	}

	private void reresolve() {
		resolved = ClientSelectionManager.resolve(groups, chosen);
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

	private void save() {
		ClientSelectionManager.getManager().saveSelection(modpackId, resolved);
		saved = true;
		rebuild();
	}

	/** The group's description, shown on hover. Null when the server set none, so no tooltip appears. */
	private MutableComponent rowTooltip(Jsons.ModpackContentFields.ModpackGroupFields group) {
		if (group == null || group.description == null || group.description.isBlank()) return null;
		return VersionedText.literal(group.description).withStyle(ChatFormatting.GRAY);
	}

	private MutableComponent rowLabel(String groupId, Jsons.ModpackContentFields.ModpackGroupFields group) {
		if (group == null) return VersionedText.literal(groupId);

		String name = group.displayName == null || group.displayName.isBlank() ? groupId : group.displayName;
		boolean on = resolved.contains(groupId);

		if (group.required) return VersionedText.literal("[#] " + name + " (required)").withStyle(ChatFormatting.GRAY);
		if (on) return VersionedText.literal("[x] " + name).withStyle(ChatFormatting.GREEN);
		return VersionedText.literal("[ ] " + name).withStyle(ChatFormatting.GRAY);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		// Header names the modpack when the server set one, so the player knows which pack they are editing.
		MutableComponent header = modpackName.isBlank()
				? VersionedText.translatable("automodpack.selection.title")
				: VersionedText.literal(modpackName + " – ").append(VersionedText.translatable("automodpack.selection.title"));
		drawCenteredTextWithShadow(matrices, this.font, header.withStyle(ChatFormatting.BOLD), this.width / 2, 18, TextColors.WHITE);

		if (saved) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.saved").withStyle(ChatFormatting.GREEN),
					this.width / 2, this.height / 2 - 30, TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.restartRequired").withStyle(ChatFormatting.YELLOW),
					this.width / 2, this.height / 2 - 15, TextColors.WHITE);
			// updateSelectedModpackOnLaunch skips reconciliation entirely on the next launch, so a
			// restart alone will not apply this selection change; say so instead of implying it will.
			if (clientConfig != null && !clientConfig.updateSelectedModpackOnLaunch) {
				drawCenteredTextWithShadow(matrices, this.font,
						VersionedText.translatable("automodpack.selection.updateOnLaunchDisabled").withStyle(ChatFormatting.RED), this.width / 2,
						this.height / 2 - 45, TextColors.WHITE);
			}
		} else {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.description").withStyle(ChatFormatting.GRAY),
					this.width / 2, 32, TextColors.WHITE);
		}
	}
}
