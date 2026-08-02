package pl.skidam.automodpack.client.ui;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.clientConfig;
import static pl.skidam.automodpack_core.Constants.clientSelectionFile;
import static pl.skidam.automodpack_core.Constants.modpackCatalogueFileName;
import static pl.skidam.automodpack_core.Constants.modpackHistoryFileName;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Consumer;

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
import pl.skidam.automodpack_core.modpack.group.GroupResolution;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.modpack.group.SelectionResolutionException;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientContentHistory;
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
	private final Consumer<SelectionIntent> selectionAction;
	private final ModpackUpdater pendingUpdater;

	// What the player has actually ticked; resolved is what that implies once required groups,
	// dependencies, conflicts and platform rules are applied.
	private final Set<String> chosen = new LinkedHashSet<>();
	private final Set<String> chosenTags = new LinkedHashSet<>();
	private final Set<String> excluded = new LinkedHashSet<>();
	private ResolvedSelection resolution;
	private final List<Row> rows = new ArrayList<>();

	private int page = 0;
	private int rowsPerPage = 1;
	private boolean saved = false;

	public ModpackSelectionScreen(Screen parent, GroupManifest manifest) {
		this(parent, manifest, null, null, null, null);
	}

	public ModpackSelectionScreen(Screen parent, ModpackUpdater updater, Consumer<SelectionIntent> selectionAction) {
		this(parent, updater.getSelectedTarget().manifest(), updater.getSelectedTarget().expectedPriorIntent(), updater.getSelectedTarget().selection().intent(), selectionAction, updater);
	}

	private ModpackSelectionScreen(Screen parent, GroupManifest manifest, SelectionIntent expectedSelection, SelectionIntent initialSelection,
			Consumer<SelectionIntent> selectionAction, ModpackUpdater pendingUpdater) {
		super(VersionedText.translatable("automodpack.selection.title"));
		this.parent = parent;
		this.manifest = Objects.requireNonNull(manifest);
		this.modpackId = manifest.modpackId();
		this.modpackName = manifest.modpackName();
		this.groups = manifest.groups();
		this.expectedSelection = expectedSelection == null && initialSelection == null
				? new ClientSelectionStore(clientSelectionFile).get(modpackId).orElse(null)
				: expectedSelection;
		this.selectionAction = selectionAction;
		this.pendingUpdater = pendingUpdater;
		SelectionIntent initial = initialSelection != null
				? initialSelection
				: this.expectedSelection == null ? GroupSelectionResolver.defaultIntent(manifest) : this.expectedSelection;
		this.chosenTags.addAll(initial.requestedTags());
		this.chosen.addAll(initial.requestedGroups());
		this.excluded.addAll(initial.excludedGroups());
		this.resolution = GroupSelectionResolver.resolve(manifest, initial, ClientPlatform.current());
		rebuildRows();
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
		if (manifest == null) {
			LOGGER.info("Modpack {} catalogue is unavailable", modpackId);
			return parent;
		}

		return new ModpackSelectionScreen(parent, manifest);
	}

	public static boolean hasModpackManagement() {
		return modpackHasCatalogue(clientConfig == null ? null : clientConfig.selectedModpackId);
	}

	private static boolean modpackHasCatalogue(String modpackId) {
		if (modpackId == null || modpackId.isBlank()) return false;

		GroupManifest manifest = Optional.ofNullable(ModpackContentTools.readGenerationRecord(ModpackUtils.getModpackPath(modpackId).resolve(modpackCatalogueFileName)))
				.map(record -> record.manifest()).orElse(null);
		return manifest != null;
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
			Row row = rows.get(i);
			int y = listTop + (i - start) * ROW_HEIGHT;
			if (row.groupId() == null) {
				Button section = buttonWidget(x, y, ROW_WIDTH, 20, sectionLabel(row), press -> {
					if (row.tagId() != null) toggleTag(row.tagId());
				});
				section.active = row.tagId() != null && manifest.selectionTags().containsKey(row.tagId()) && !manifest.selectionTags().get(row.tagId()).serverForced();
				if (row.tagId() != null) {
					GroupManifest.SelectionTag tag = manifest.selectionTags().get(row.tagId());
					if (tag != null && !tag.description().isBlank()) setTooltip(section, VersionedText.literal(tag.description()));
				}
				this.addRenderableWidget(section);
				continue;
			}

			String groupId = row.groupId();
			var group = groups.get(groupId);
			Button button = buttonWidget(x, y, ROW_WIDTH - 68, 20, rowLabel(groupId, group), press -> toggle(groupId));
			// Required groups, forced groups, dependencies and unavailable groups are shown so the player
			// can understand the target, but only optional compatible choices are togglable.
			button.active = group != null && canToggle(groupId, group);
			MutableComponent tooltip = rowTooltip(groupId, group);
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

		if (selectionAction == null) {
			this.addRenderableWidget(buttonWidget(this.width / 2 - 155, this.height - 80, 100, 20, VersionedText.literal("Remove"), press -> requestRemoval()));
			this.addRenderableWidget(buttonWidget(this.width / 2 - 50, this.height - 80, 100, 20, VersionedText.literal("Recovery"), press -> requestRecovery()));
			this.addRenderableWidget(buttonWidget(this.width / 2 + 55, this.height - 80, 100, 20, VersionedText.literal("History"), press -> requestHistory()));
		}

		this.addRenderableWidget(buttonWidget(this.width / 2 - 155, this.height - 28, 100, 20, VersionedText.translatable("automodpack.selection.reset"), press -> {
			SelectionIntent defaults = GroupSelectionResolver.defaultIntent(manifest);
			chosenTags.clear();
			chosenTags.addAll(defaults.requestedTags());
			chosen.clear();
			chosen.addAll(defaults.requestedGroups());
			excluded.clear();
			reresolve();
		}));

		this.addRenderableWidget(buttonWidget(this.width / 2 - 50, this.height - 28, 100, 20, VersionedText.translatable("automodpack.selection.cancel"), press -> this.minecraft.gui.setScreen(parent)));

		this.addRenderableWidget(buttonWidget(this.width / 2 + 55, this.height - 28, 100, 20,
				VersionedText.translatable(selectionAction == null ? "automodpack.selection.save" : "automodpack.selection.preview").withStyle(ChatFormatting.BOLD), press -> save()));
	}

	@Override
	public void tick() {
		super.tick();
		if (pendingUpdater == null) return;
		ModpackUpdater.ConfirmationState state = pendingUpdater.getConfirmationState();
		if (state == ModpackUpdater.ConfirmationState.EXPIRED || state == ModpackUpdater.ConfirmationState.CANCELLED) new ScreenManager().title();
	}

	private boolean canToggle(String groupId, GroupManifest.Group group) {
		if (isMandatory(manifest, group)) return false;
		GroupResolution explanation = resolution.explanation(groupId);
		if (explanation == null) return group.supports(ClientPlatform.current());
		if (explanation.selected() && (resolution.requiredGroups().contains(groupId) || resolution.forcedGroups().contains(groupId)
				|| resolution.dependencyGroups().contains(groupId))) return false;
		return group.supports(ClientPlatform.current()) || excluded.contains(groupId);
	}

	/** Toggling a tag requests or removes its complete compatible bundle. */
	private void toggleTag(String tagId) {
		SelectionIntent previous = new SelectionIntent(chosenTags, chosen, excluded);
		ResolvedSelection previousResolution = resolution;
		try {
			SelectionIntent next = GroupSelectionResolver.preferTag(manifest, previous, tagId, ClientPlatform.current());
			chosenTags.clear();
			chosenTags.addAll(next.requestedTags());
			chosen.clear();
			chosen.addAll(next.requestedGroups());
			excluded.clear();
			excluded.addAll(next.excludedGroups());
			reresolve();
		} catch (SelectionResolutionException e) {
			chosenTags.clear();
			chosenTags.addAll(previous.requestedTags());
			chosen.clear();
			chosen.addAll(previous.requestedGroups());
			excluded.clear();
			excluded.addAll(previous.excludedGroups());
			resolution = previousResolution;
			rebuild();
			LOGGER.warn("Could not apply tag preference for {}: {}", tagId, e.getMessage());
		}
	}

	/**
	 * A group inside a selected tag becomes an explicit exclusion when it is not otherwise required.
	 * A direct group choice still uses the resolver's conflict preference behavior.
	 */
	private void toggle(String groupId) {
		GroupManifest.Group group = groups.get(groupId);
		if (group == null) return;
		if (isMandatory(manifest, group)) return;
		SelectionIntent previous = new SelectionIntent(chosenTags, chosen, excluded);
		ResolvedSelection previousResolution = resolution;
		try {
			boolean selectedByTag = !group.tag().isEmpty() && chosenTags.contains(group.tag()) && !chosen.contains(groupId);
			if (selectedByTag) {
				if (!excluded.add(groupId)) excluded.remove(groupId);
				reresolve();
				return;
			}
			SelectionIntent next = GroupSelectionResolver.prefer(manifest, previous, groupId, ClientPlatform.current());
			chosenTags.clear();
			chosenTags.addAll(next.requestedTags());
			chosen.clear();
			chosen.addAll(next.requestedGroups());
			excluded.clear();
			excluded.addAll(next.excludedGroups());
			reresolve();
		} catch (SelectionResolutionException e) {
			chosenTags.clear();
			chosenTags.addAll(previous.requestedTags());
			chosen.clear();
			chosen.addAll(previous.requestedGroups());
			excluded.clear();
			excluded.addAll(previous.excludedGroups());
			resolution = previousResolution;
			rebuild();
			LOGGER.warn("Could not apply group preference for {}: {}", groupId, e.getMessage());
		}
	}

	private void reresolve() {
		resolution = GroupSelectionResolver.resolve(manifest, new SelectionIntent(chosenTags, chosen, excluded), ClientPlatform.current());
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

	private ModpackUpdater createUpdater() {
		if (clientConfig == null || clientConfig.modpackConnections == null) {
			new ScreenManager().error("automodpack.error.critical", "The modpack connection is unavailable", "automodpack.error.logs");
			return null;
		}
		Jsons.ConnectionInfo connection = clientConfig.modpackConnections.get(modpackId);
		if (connection == null || !connection.isComplete()) {
			new ScreenManager().error("automodpack.error.critical", "The modpack connection is unavailable", "automodpack.error.logs");
			return null;
		}
		Secrets.Secret secret = SecretsStore.getClientSecret(connection.origin);
		if (secret == null) secret = Secrets.anonymousSecret();
		try {
			return new ModpackUpdater(connection, secret, ModpackUtils.getModpackPath(modpackId));
		} catch (RuntimeException e) {
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			return null;
		}
	}

	private void requestRemoval() {
		ModpackUpdater removalUpdater = createUpdater();
		if (removalUpdater == null) return;
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

	private void requestRecovery() {
		ModpackUpdater recoveryUpdater = createUpdater();
		if (recoveryUpdater == null) return;
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				new ScreenManager().recovery(recoveryUpdater, recoveryUpdater.recoverySnapshot(), modpackName);
			} catch (Exception e) {
				recoveryUpdater.close();
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
		});
	}

	private void requestHistory() {
		DownloadClient.NET_EXECUTOR.execute(() -> {
			try {
				ClientContentHistory.History history = ClientContentHistory.read(ModpackUtils.getModpackPath(modpackId).resolve(modpackHistoryFileName));
				new ScreenManager().history(history, modpackName);
			} catch (Exception e) {
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
		SelectionIntent target = new SelectionIntent(chosenTags, chosen, excluded);
		if (selectionAction != null) {
			try {
				selectionAction.accept(target);
			} catch (RuntimeException e) {
				LOGGER.error("Failed to prepare the selected modpack target", e);
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
			return;
		}
		try {
			selectionStore.compareAndSet(modpackId, expectedSelection, target);
			saved = true;
			rebuild();
		} catch (IOException e) {
			LOGGER.error("Failed to save group selection for modpack {}", modpackId, e);
			new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
		}
	}

	private void rebuildRows() {
		rows.clear();
		rows.add(new Row("General", null, null));
		for (var entry : groups.entrySet()) if (entry.getValue().tag().isEmpty()) rows.add(new Row("", entry.getKey(), null));
		for (var tagEntry : manifest.selectionTags().entrySet()) {
			GroupManifest.SelectionTag tag = tagEntry.getValue();
			String title = tag.displayName().isBlank() ? tagEntry.getKey() : tag.displayName();
			rows.add(new Row(title, null, tagEntry.getKey()));
			for (var entry : groups.entrySet()) if (tagEntry.getKey().equals(entry.getValue().tag())) rows.add(new Row("", entry.getKey(), tagEntry.getKey()));
		}
	}

	private MutableComponent sectionLabel(Row row) {
		if (row.tagId() == null) return VersionedText.literal(row.section()).withStyle(ChatFormatting.BOLD);
		GroupManifest.SelectionTag tag = manifest.selectionTags().get(row.tagId());
		String title = tag == null || tag.displayName().isBlank() ? row.tagId() : tag.displayName();
		if (tag != null && tag.serverForced()) return VersionedText.literal("[#] " + title + " (forced)").withStyle(ChatFormatting.GRAY);
		return VersionedText.literal((chosenTags.contains(row.tagId()) ? "[x] " : "[ ] ") + title).withStyle(ChatFormatting.BOLD);
	}

	/** The group's metadata and the resolver explanation, shown on hover. */
	private MutableComponent rowTooltip(String groupId, GroupManifest.Group group) {
		if (group == null) return null;
		StringBuilder tooltip = new StringBuilder();
		if (!group.description().isBlank()) tooltip.append(group.description());
		GroupResolution explanation = resolution.explanation(groupId);
		if (explanation != null) appendTooltipLine(tooltip, explanation.explanation());
		if (explanation != null && !explanation.relatedGroups().isEmpty()) appendTooltipLine(tooltip, "Related: " + names(explanation.relatedGroups()));
		appendTooltipLine(tooltip, "Tag: " + tagLabel(group));
		if (!group.requires().isEmpty()) appendTooltipLine(tooltip, "Requires: " + names(group.requires()));
		if (!group.breaksWith().isEmpty()) appendTooltipLine(tooltip, "Conflicts: " + names(group.breaksWith()));
		appendTooltipLine(tooltip, "Files: " + group.files().size() + " (" + formatSize(groupBytes(group)) + ")");
		appendTooltipLine(tooltip, group.supports(ClientPlatform.current()) ? "Available on " + ClientPlatform.current().id() : "Not available on " + ClientPlatform.current().id());
		return VersionedText.literal(tooltip.toString()).withStyle(ChatFormatting.GRAY);
	}

	private String tagLabel(GroupManifest.Group group) {
		if (group.tag().isEmpty()) return "General";
		GroupManifest.SelectionTag tag = manifest.selectionTags().get(group.tag());
		return tag == null || tag.displayName().isBlank() ? group.tag() : tag.displayName();
	}

	private static void appendTooltipLine(StringBuilder tooltip, String line) {
		if (tooltip.length() > 0) tooltip.append('\n');
		tooltip.append(line);
	}

	private MutableComponent rowLabel(String groupId, GroupManifest.Group group) {
		if (group == null) return VersionedText.literal(groupId);

		String name = group.displayName().isBlank() ? groupId : group.displayName();
		GroupResolution explanation = resolution.explanation(groupId);
		String suffix = " (" + group.files().size() + " files, " + formatSize(groupBytes(group)) + ")";
		if (isMandatory(manifest, group)) return VersionedText.literal("[#] " + name + suffix + " (required)").withStyle(ChatFormatting.GRAY);
		if (explanation != null && explanation.status() == GroupResolution.Status.UNAVAILABLE) return VersionedText.literal("[-] " + name + suffix + " (unavailable)").withStyle(ChatFormatting.RED);
		if (explanation != null && explanation.status() == GroupResolution.Status.BLOCKED) return VersionedText.literal("[-] " + name + suffix + " (dependency unavailable)").withStyle(ChatFormatting.RED);
		if (explanation != null && explanation.status() == GroupResolution.Status.CONFLICT) return VersionedText.literal("[!] " + name + suffix + " (conflict)").withStyle(ChatFormatting.RED);
		if (excluded.contains(groupId)) return VersionedText.literal("[-] " + name + suffix + " (excluded)").withStyle(ChatFormatting.YELLOW);
		if (resolution.selectedGroups().contains(groupId)) {
			if (chosen.contains(groupId)) return VersionedText.literal("[x] " + name + suffix + " (selected)").withStyle(ChatFormatting.GREEN);
			if (resolution.tagExpandedGroups().contains(groupId)) return VersionedText.literal("[+] " + name + suffix + " (selected by tag)").withStyle(ChatFormatting.AQUA);
			if (resolution.dependencyGroups().contains(groupId)) return VersionedText.literal("[+] " + name + suffix + " (required by selection)").withStyle(ChatFormatting.AQUA);
			return VersionedText.literal("[+] " + name + suffix).withStyle(ChatFormatting.AQUA);
		}
		return group.recommended()
					? VersionedText.literal("[ ] " + name + suffix + " (recommended)").withStyle(ChatFormatting.YELLOW)
					: VersionedText.literal("[ ] " + name + suffix).withStyle(ChatFormatting.GRAY);
	}

	private static boolean isMandatory(GroupManifest manifest, GroupManifest.Group group) {
		return group.required() || (!group.tag().isEmpty() && Optional.ofNullable(manifest.selectionTags().get(group.tag()))
				.map(GroupManifest.SelectionTag::serverForced).orElse(false));
	}

	private static long groupBytes(GroupManifest.Group group) {
		long total = 0;
		for (GroupManifest.GroupFile file : group.files().values()) total = Math.addExact(total, file.size());
		return total;
	}

	private static String names(Iterable<String> values) {
		StringBuilder result = new StringBuilder();
		for (String value : values) {
			if (result.length() > 0) result.append(", ");
			result.append(value);
		}
		return result.length() == 0 ? "none" : result.toString();
	}

	private static String formatSize(long bytes) {
		if (bytes < 1024) return bytes + " B";
		if (bytes < 1024 * 1024) return (bytes / 1024) + " KiB";
		if (bytes < 1024L * 1024L * 1024L) return (bytes / (1024 * 1024)) + " MiB";
		return (bytes / (1024L * 1024L * 1024L)) + " GiB";
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		// Header names the modpack when the server set one, so the player knows which pack they are editing.
		MutableComponent header = modpackName.isBlank()
				? VersionedText.translatable("automodpack.selection.title")
				: VersionedText.literal(modpackName + " – ").append(VersionedText.translatable("automodpack.selection.title"));
		drawCenteredTextWithShadow(matrices, this.font, header.withStyle(ChatFormatting.BOLD), this.width / 2, 18, TextColors.WHITE);

		if (saved) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.saved").withStyle(ChatFormatting.GREEN), this.width / 2, this.height / 2 - 30,
					TextColors.WHITE);
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.selection.restartRequired").withStyle(ChatFormatting.YELLOW), this.width / 2, this.height / 2 - 15,
					TextColors.WHITE);
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
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal("Platform: " + ClientPlatform.current().id() + "  Selected groups: " + resolution.selectedGroups().size())
					.withStyle(ChatFormatting.GRAY), this.width / 2, 43, TextColors.WHITE);
		}
	}

	private record Row(String section, String groupId, String tagId) {}
}
