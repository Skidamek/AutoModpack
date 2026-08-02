package pl.skidam.automodpack.client.ui;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.clientConfig;
import static pl.skidam.automodpack_core.Constants.clientSelectionFile;
import static pl.skidam.automodpack_core.Constants.modpackCatalogueFileName;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.modpack.group.SelectionResolutionException;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/**
 * Lets the player pick which optional groups of a modpack they want. Deliberately built out of
 * plain buttons rather than a scrolling list widget: buttons are the one widget whose API is
 * stable across every Minecraft version this mod targets.
 *
 * Changes only take effect on the next launch, because mods are loaded during preload.
 */
public class ModpackSelectionScreen extends VersionedScreen {

	private static final int ROW_HEIGHT = 24;
	private static final int ROW_WIDTH = 320;

	private final Screen parent;
	private final GroupManifest manifest;
	private final String modpackId;
	private final String modpackName;
	private final Map<String, GroupManifest.Group> groups;
	private final ClientSelectionStore selectionStore = new ClientSelectionStore(clientSelectionFile);
	private final SelectionIntent expectedSelection;

	// What the player has actually ticked; resolved is what that implies once required groups,
	// dependencies and conflicts are applied.
	private final Set<String> chosen = new LinkedHashSet<>();
	private Set<String> resolved;
	private final List<String> rows = new ArrayList<>();

	private int page = 0;
	private int rowsPerPage = 1;
	private boolean saved = false;

	public ModpackSelectionScreen(Screen parent, GroupManifest manifest) {
		super(VersionedText.translatable("automodpack.selection.title"));
		this.parent = parent;
		this.manifest = Objects.requireNonNull(manifest);
		this.modpackId = manifest.modpackId();
		this.modpackName = manifest.modpackName();
		this.groups = manifest.groups();

		this.expectedSelection = selectionStore.get(modpackId).orElse(null);
		SelectionIntent initial = expectedSelection == null ? GroupSelectionResolver.defaultIntent(manifest) : expectedSelection;
		this.chosen.addAll(initial.requestedGroups());
		this.resolved = GroupSelectionResolver.resolve(manifest, initial, ClientPlatform.current()).selectedGroups();
		this.rows.addAll(this.groups.keySet());
	}

	/**
	 * Builds the screen for whichever modpack the client currently has selected. Returns the parent
	 * untouched when there is nothing to choose, so callers can hand the result straight to setScreen.
	 */
	public static Screen forSelectedModpack(Screen parent) {
		return forModpackId(parent, clientConfig == null ? null : clientConfig.selectedModpackId);
	}

	private static Screen forModpackId(Screen parent, String modpackId) {
		if (modpackId == null || modpackId.isBlank()) {
			LOGGER.info("No modpack selected, nothing to configure");
			return parent;
		}

		Path contentFile = ModpackUtils.getModpackPath(modpackId).resolve(modpackCatalogueFileName);
		GroupManifest manifest = Optional.ofNullable(ModpackContentTools.readGenerationRecord(contentFile)).map(record -> record.manifest()).orElse(null);
		if (manifest == null || manifest.groups().isEmpty()) {
			LOGGER.info("Modpack {} declares no groups", modpackId);
			return parent;
		}

		return new ModpackSelectionScreen(parent, manifest);
	}

	public static boolean hasGroupsToConfigure() {
		return modpackHasOptionalGroups(clientConfig == null ? null : clientConfig.selectedModpackId);
	}


	private static boolean modpackHasOptionalGroups(String modpackId) {
		if (modpackId == null || modpackId.isBlank()) return false;

		GroupManifest manifest = Optional.ofNullable(ModpackContentTools.readGenerationRecord(ModpackUtils.getModpackPath(modpackId).resolve(modpackCatalogueFileName)))
				.map(record -> record.manifest()).orElse(null);
		if (manifest == null) return false;
		// Nothing worth showing a button for when every available group is mandatory.
		return manifest.groups().values().stream().anyMatch(group -> !isMandatory(manifest, group) && group.supports(ClientPlatform.current()));
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

			Button button = buttonWidget(x, y, ROW_WIDTH - 68, 20, rowLabel(groupId, group), press -> toggle(groupId));
			// Required groups are shown so the player can see what they are getting, but not togglable.
			button.active = group != null && !isMandatory(manifest, group) && group.supports(ClientPlatform.current());
			MutableComponent tooltip = rowTooltip(group);
			// A disabled button still shows its tooltip, so required groups keep their description on hover.
			if (tooltip != null) setTooltip(button, tooltip);
			this.addRenderableWidget(button);
			Button inspect = buttonWidget(x + ROW_WIDTH - 64, y, 64, 20, VersionedText.literal("Info"), press -> inspect(groupId));
			inspect.active = group != null;
			if (tooltip != null) setTooltip(inspect, tooltip);
			this.addRenderableWidget(inspect);
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

		this.addRenderableWidget(buttonWidget(this.width / 2 - 155, this.height - 80, 310, 20, VersionedText.literal("Remove modpack and restore instance"), press -> requestRemoval()));

		this.addRenderableWidget(buttonWidget(this.width / 2 - 155, this.height - 28, 100, 20, VersionedText.translatable("automodpack.selection.reset"),
				press -> {
					chosen.clear();
					chosen.addAll(GroupSelectionResolver.defaultIntent(manifest).requestedGroups());
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
		SelectionIntent previous = new SelectionIntent(chosen);
		Set<String> previousResolved = resolved;
		SelectionIntent next = GroupSelectionResolver.prefer(manifest, previous, groupId, ClientPlatform.current());
		chosen.clear();
		chosen.addAll(next.requestedGroups());
		try {
			reresolve();
		} catch (SelectionResolutionException e) {
			chosen.clear();
			chosen.addAll(previous.requestedGroups());
			resolved = previousResolved;
			rebuild();
			LOGGER.warn("Could not apply group preference for {}: {}", groupId, e.getMessage());
		}
	}

	private void reresolve() {
		resolved = GroupSelectionResolver.resolve(manifest, new SelectionIntent(chosen), ClientPlatform.current()).selectedGroups();
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

	private void inspect(String groupId) {
		this.minecraft.gui.setScreen(new GroupInspectorScreen(this, manifest, groupId));
	}

	private void requestRemoval() {
		if (clientConfig == null || clientConfig.modpackConnections == null) {
			new ScreenManager().error("automodpack.error.critical", "The modpack connection is unavailable", "automodpack.error.logs");
			return;
		}
		Jsons.ConnectionInfo connection = clientConfig.modpackConnections.get(modpackId);
		if (connection == null || !connection.isComplete()) {
			new ScreenManager().error("automodpack.error.critical", "The modpack connection is unavailable", "automodpack.error.logs");
			return;
		}
		Secrets.Secret secret = SecretsStore.getClientSecret(connection.origin);
		if (secret == null) secret = Secrets.anonymousSecret();
		ModpackUpdater updater;
		try {
			updater = new ModpackUpdater(connection, secret, ModpackUtils.getModpackPath(modpackId));
		} catch (RuntimeException e) {
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			return;
		}
		ModpackUpdater removalUpdater = updater;
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				UpdatePreview preview = removalUpdater.previewRemoval();
				new ScreenManager().preview(preview, modpackName,
						(Runnable) () -> DownloadClient.NET_EXECUTOR.execute(() -> executeRemoval(removalUpdater)),
						(Runnable) removalUpdater::close, true);
			} catch (Exception e) {
				removalUpdater.close();
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
		});
	}

	private void executeRemoval(ModpackUpdater updater) {
		try {
			if (updater.removeModpack().success()) {
				new ScreenManager().title();
			} else {
				new ScreenManager().error("automodpack.error.critical", "The modpack removal did not complete", "automodpack.error.logs");
			}
		} catch (Exception e) {
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
		} finally {
			updater.close();
		}
	}

	private void save() {
		try {
			selectionStore.compareAndSet(modpackId, expectedSelection, new SelectionIntent(chosen));
			saved = true;
			rebuild();
		} catch (IOException e) {
			LOGGER.error("Failed to save group selection for modpack {}", modpackId, e);
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
		}
	}

	/** The group's metadata, shown on hover. */
	private MutableComponent rowTooltip(GroupManifest.Group group) {
		if (group == null) return null;
		StringBuilder tooltip = new StringBuilder();
		if (!group.description().isBlank()) tooltip.append(group.description());
		if (!group.tags().isEmpty()) appendTooltipLine(tooltip, "Tags: " + String.join(", ", group.tags()));
		if (!group.requires().isEmpty()) appendTooltipLine(tooltip, "Requires: " + String.join(", ", group.requires()));
		if (!group.breaksWith().isEmpty()) appendTooltipLine(tooltip, "Conflicts: " + String.join(", ", group.breaksWith()));
		appendTooltipLine(tooltip, "Files: " + group.files().size());
		appendTooltipLine(tooltip, group.supports(ClientPlatform.current()) ? "Available on this platform" : "Not available on this platform");
		return VersionedText.literal(tooltip.toString()).withStyle(ChatFormatting.GRAY);
	}

	private static void appendTooltipLine(StringBuilder tooltip, String line) {
		if (tooltip.length() > 0) tooltip.append('\n');
		tooltip.append(line);
	}

	private MutableComponent rowLabel(String groupId, GroupManifest.Group group) {
		if (group == null) return VersionedText.literal(groupId);

		String name = group.displayName().isBlank() ? groupId : group.displayName();
		boolean on = resolved.contains(groupId);
		boolean explicit = chosen.contains(groupId);

		if (isMandatory(manifest, group)) return VersionedText.literal("[#] " + name + " (required)").withStyle(ChatFormatting.GRAY);
		if (on && !explicit) return VersionedText.literal("[+] " + name + " (required by selection)").withStyle(ChatFormatting.AQUA);
		if (explicit) return VersionedText.literal("[x] " + name).withStyle(ChatFormatting.GREEN);
		return VersionedText.literal("[ ] " + name).withStyle(ChatFormatting.GRAY);
	}

	private static boolean isMandatory(GroupManifest manifest, GroupManifest.Group group) {
		return group.required() || group.tags().stream().map(manifest.selectionTags()::get).filter(Objects::nonNull)
				.anyMatch(GroupManifest.SelectionTag::serverForced);
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
