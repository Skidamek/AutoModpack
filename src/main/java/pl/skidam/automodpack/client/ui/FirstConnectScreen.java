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
		int actionY = this.height - 28;
		int twoButtonWidth = actionButtonWidth(310, 2);
		this.addRenderableWidget(buttonWidget(actionButtonX(310, 2, 0), actionY, twoButtonWidth, 20,
				VersionedText.translatable("automodpack.firstConnect.cancel"), button -> cancel()));
		this.addRenderableWidget(buttonWidget(actionButtonX(310, 2, 1), actionY, twoButtonWidth, 20,
				VersionedText.translatable("automodpack.firstConnect.continue").withStyle(ChatFormatting.BOLD), button -> continueWithDefaults()));
		int optionalY = actionY - 26;
		this.addRenderableWidget(buttonWidget(this.width / 2 - 75, optionalY, 150, 20, VersionedText.translatable("automodpack.firstConnect.customize"), button -> customize()));
		if (GenerationPatchNoteHistory.containsNotes(target.patchNotesHistory())) {
			this.addRenderableWidget(buttonWidget(this.width / 2 - 75, optionalY - 26, 150, 20, VersionedText.literal("All patch notes"), button -> openPatchNotes()));
		}
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
				if (updater.getConfirmationState() != ModpackUpdater.ConfirmationState.WAITING) throw new IllegalStateException("Modpack confirmation is no longer active");
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
		if (finished && updater.getConfirmationState() == ModpackUpdater.ConfirmationState.WAITING) {
			finished = false;
			return;
		}
		if (finished) return;
		ModpackUpdater.ConfirmationState state = updater.getConfirmationState();
		if (state != ModpackUpdater.ConfirmationState.CANCELLED) return;
		finished = true;
		ScreenImpl.multiplayer();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String name = target.manifest().modpackName().isBlank() ? "AutoModpack" : target.manifest().modpackName();
		ResolvedSelection selection = target.selection();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, name, this.width - 20)).withStyle(ChatFormatting.BOLD), this.width / 2, 16, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.firstConnect.description").withStyle(ChatFormatting.GRAY), this.width / 2, 31,
				TextColors.WHITE);
		int y = 51;
		if (!updater.getPatchNotes().isBlank()) {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.firstConnect.patchNotes").withStyle(ChatFormatting.YELLOW), this.width / 2, y,
					TextColors.WHITE);
			for (String line : wrapToWidth(this.font, updater.getPatchNotes(), Math.max(1, this.width - 20), 2)) {
				y += 13;
				drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(line).withStyle(ChatFormatting.WHITE), this.width / 2, y, TextColors.WHITE);
			}
		} else {
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.firstConnect.noPatchNotes").withStyle(ChatFormatting.GRAY), this.width / 2, y,
					TextColors.WHITE);
		}

		y += 19;
		long bytes = target.flatTarget().list.stream().mapToLong(item -> Long.parseLong(item.size)).sum();
		String summary = truncateToWidth(this.font, "Selected groups: " + selection.selectedGroups().size() + "  Files: " + target.flatTarget().list.size() + "  Content size: " + UiFormat.formatSize(bytes),
				this.width - 20);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(summary).withStyle(ChatFormatting.GREEN), this.width / 2, y, TextColors.WHITE);
		y += 16;
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.firstConnect.bundleExplanation").withStyle(ChatFormatting.GRAY), this.width / 2, y,
				TextColors.WHITE);
		y += 16;
		String tags = names(target.manifest().selectionTags(), selection.intent().requestedTags());
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Recommended options: " + tags, this.width - 20)).withStyle(ChatFormatting.WHITE), this.width / 2, y,
				TextColors.WHITE);
		y += 14;
		String groups = names(target.manifest().groups(), selection.selectedGroups());
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, "Included groups: " + groups, this.width - 20)).withStyle(ChatFormatting.WHITE), this.width / 2, y,
				TextColors.WHITE);
		if (!selection.staleRequestedTags().isEmpty() || !selection.staleRequestedGroups().isEmpty()) {
			y += 14;
			String stale = "Unavailable old choices: " + names(target.manifest().selectionTags(), selection.staleRequestedTags()) + " / "
					+ names(target.manifest().groups(), selection.staleRequestedGroups());
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, stale, this.width - 20)).withStyle(ChatFormatting.RED), this.width / 2, y,
					TextColors.WHITE);
		}
		if (!selection.unavailableGroups().isEmpty()) {
			y += 14;
			drawCenteredTextWithShadow(matrices, this.font,
					VersionedText.literal(truncateToWidth(this.font, selection.unavailableGroups().size() + " selected groups are unavailable on this platform.", this.width - 20)).withStyle(ChatFormatting.RED),
					this.width / 2, y, TextColors.WHITE);
		}
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
