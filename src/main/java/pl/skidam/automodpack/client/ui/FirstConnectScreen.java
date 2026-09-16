package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.ResolvedSelection;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public final class FirstConnectScreen extends VersionedScreen {
	private final ModpackUpdater updater;
	private final SelectedModpackTarget target;
	private boolean finished;

	public FirstConnectScreen(ModpackUpdater updater) {
		super(VersionedText.translatable("automodpack.firstConnect.title"));
		this.updater = Objects.requireNonNull(updater, "updater");
		this.target = updater.getSelectedTarget();
	}

	@Override
	protected void init() {
		super.init();
		int y = this.height - 52;
		int left = Math.max(5, (this.width - 310) / 2);
		this.addRenderableWidget(buttonWidget(left, y, 150, 20,
				VersionedText.translatable("automodpack.firstConnect.continue").withStyle(ChatFormatting.BOLD), button -> continueWithDefaults()));
		this.addRenderableWidget(buttonWidget(left + 160, y, 150, 20, VersionedText.translatable("automodpack.firstConnect.customize"), button -> customize()));
		this.addRenderableWidget(buttonWidget(this.width / 2 - 75, y + 26, 150, 20, VersionedText.translatable("automodpack.firstConnect.cancel"), button -> cancel()));
		if (GenerationPatchNoteHistory.containsNotes(target.patchNotesHistory()))
			this.addRenderableWidget(buttonWidget(this.width / 2 - 75, y - 26, 150, 20, VersionedText.literal("All patch notes"), button -> openPatchNotes()));
	}

	private void continueWithDefaults() {
		if (finished) return;
		if (updater.getConfirmationState() != ModpackUpdater.ConfirmationState.WAITING) {
			ScreenImpl.multiplayer();
			return;
		}
		finished = true;
		new ScreenManager().waiting();
		updater.startConfirmedUpdate();
	}

	private void customize() {
		if (finished) return;
		Consumer<SelectionIntent> action = intent -> {
			try {
				if (updater.getConfirmationState() != ModpackUpdater.ConfirmationState.WAITING) throw new IllegalStateException("Modpack confirmation expired");
				updater.selectTarget(intent);
				new ScreenManager().waiting();
				updater.startConfirmedUpdate();
			} catch (RuntimeException e) {
				finished = false;
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			}
		};
		ScreenImpl.setScreen(new ModpackSelectionScreen(this, updater, action));
	}

	private void cancel() {
		if (finished) return;
		finished = true;
		updater.cancelConfirmation();
		ScreenImpl.multiplayer();
	}

	private void openPatchNotes() {
		ScreenImpl.setScreen(new PatchNotesHistoryScreen(this, target.patchNotesHistory(), target.manifest().modpackName()));
	}

	@Override
	public void tick() {
		super.tick();
		if (finished) return;
		ModpackUpdater.ConfirmationState state = updater.getConfirmationState();
		if (state != ModpackUpdater.ConfirmationState.EXPIRED && state != ModpackUpdater.ConfirmationState.CANCELLED) return;
		finished = true;
		ScreenImpl.multiplayer();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String name = target.manifest().modpackName().isBlank() ? "AutoModpack" : target.manifest().modpackName();
		ResolvedSelection selection = target.selection();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(name).withStyle(ChatFormatting.BOLD), this.width / 2, 16, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.firstConnect.description").withStyle(ChatFormatting.GRAY), this.width / 2, 31,
				TextColors.WHITE);
		int y = 51;
		if (!updater.getPatchNotes().isBlank()) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.firstConnect.patchNotes").withStyle(ChatFormatting.YELLOW), this.width / 2, y,
					TextColors.WHITE);
			for (String line : wrapToWidth(this.font, updater.getPatchNotes(), Math.max(1, this.width - 20), Math.min(3, Math.max(1, (this.height - 208) / 13)))) {
				y += 13;
				drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(ChatFormatting.WHITE), this.width / 2, y, TextColors.WHITE);
			}
		} else {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.firstConnect.noPatchNotes").withStyle(ChatFormatting.GRAY), this.width / 2, y,
					TextColors.WHITE);
		}

		y += 22;
		long bytes = target.flatTarget().list.stream().mapToLong(item -> Long.parseLong(item.size)).sum();
		String summary = truncateToWidth(this.font, "Selected groups: " + selection.selectedGroups().size() + "  Files: " + target.flatTarget().list.size() + "  Download: " + UiFormat.formatSize(bytes), this.width - 20);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(summary).withStyle(ChatFormatting.GREEN), this.width / 2, y, TextColors.WHITE);
		y += 14;
		String compatibility = "Compatibility: " + ClientPlatform.current().id() + "  " + (selection.unavailableGroups().isEmpty()
				? "all selected choices available"
				: selection.unavailableGroups().size() + " choices unavailable");
		String stale = "Stale choices: tags=" + names(target.manifest().selectionTags(), selection.staleRequestedTags()) + " groups="
				+ names(target.manifest().groups(), selection.staleRequestedGroups());
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, stale + "  " + compatibility, this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, y,
				TextColors.WHITE);
		y += 14;
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.firstConnect.bundleExplanation").withStyle(ChatFormatting.GRAY), this.width / 2, y,
				TextColors.WHITE);
		y += 17;
		String tags = names(target.manifest().selectionTags(), selection.intent().requestedTags());
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Default tags: " + tags, this.width - 20)).withStyle(ChatFormatting.WHITE), this.width / 2, y, TextColors.WHITE);
		y += 14;
		String groups = names(target.manifest().groups(), selection.selectedGroups());
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Default groups: " + groups, this.width - 20)).withStyle(ChatFormatting.WHITE), this.width / 2, y,
				TextColors.WHITE);
		if (updater.getSourceAvailability().totalFiles() > 0) {
			y += 14;
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, sourceAvailability(), this.width - 20)).withStyle(ChatFormatting.GRAY), this.width / 2, y,
					TextColors.WHITE);
		}
	}

	private String sourceAvailability() {
		ModpackUpdater.SourceAvailability availability = updater.getSourceAvailability();
		if (availability.cancelled()) return "Third-party sources: lookup cancelled; server download remains available";
		if (!availability.complete()) return "Third-party sources: resolving (" + availability.resolvedFiles() + " / " + availability.totalFiles() + " files matched)";
		return "Third-party sources: " + availability.resolvedFiles() + " / " + availability.totalFiles() + " files matched; unmatched files use the server";
	}

	private String names(Map<String, ?> values, Iterable<String> ids) {
		List<String> names = new ArrayList<>();
		for (String id : ids) {
			Object value = values.get(id);
			String display;
			if (value instanceof GroupManifest.SelectionTag tag) display = tag.displayName().isBlank() ? id : tag.displayName();
			else if (value instanceof GroupManifest.Group group) display = group.displayName().isBlank() ? id : group.displayName();
			else display = id;
			names.add(display);
		}
		if (names.isEmpty()) return "none";
		String joined = String.join(", ", names);
		return truncateToWidth(this.font, joined, Math.max(1, this.width - 20));
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return false;
	}
}
