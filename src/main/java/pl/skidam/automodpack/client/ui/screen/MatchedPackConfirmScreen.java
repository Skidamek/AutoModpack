package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** First-install confirm when every selected jar matched Modrinth or CurseForge. */
public final class MatchedPackConfirmScreen extends VersionedScreen {
	private static final int PANEL = ActionAreaLayout.FOOTER_RAIL;
	private static final int LINE = 11;
	private final ModpackUpdater updater;
	private boolean keepExistingMods;
	private boolean finished;
	private String originFull = "";

	public MatchedPackConfirmScreen(ModpackUpdater updater) {
		super(VersionedText.translatable("automodpack.firstConnect.title"));
		this.updater = Objects.requireNonNull(updater, "updater");
	}

	@Override
	protected void init() {
		super.init();
		originFull = updater.joinOrigin();
		boolean leftover = updater.firstInstallLocalModCount() > 0;
		boolean customize = PackConfirmCopy.hasOptionalGroups(updater.getSelectedTarget().manifest());
		boolean notes = GenerationPatchNoteHistory.containsNotes(updater.getFirstInstallPatchNotes());
		List<ActionRow> rows = new ArrayList<>();
		if (notes) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.patchNotes.all"), button -> openPatchNotes())));
		if (leftover) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.literal(" "), button -> {})));
		if (customize) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.firstConnect.customize"), button -> customize())));
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.firstConnect.cancel"), button -> cancel()),
				optionalAction(VersionedText.translatable("automodpack.browser.reviewFiles"), button -> openFiles()),
				primaryAction(VersionedText.translatable("automodpack.firstConnect.download"), button -> download())));
		ActionRow[] rowArray = rows.toArray(ActionRow[]::new);
		List<Button> buttons = this.addActionArea(PANEL, this.height - 28, rowArray);
		if (leftover) replaceLeftoverPlaceholder(buttons, notes);
		addOriginHitbox();
		int bottomY = actionAreaTop(PANEL, this.height - 28, rowArray) - 4;
		int topY = 42;
		List<String> lines = buildBodyLines();
		int contentHeight = Math.max(LINE, lines.size() * LINE);
		int available = Math.max(LINE, bottomY - topY);
		if (contentHeight < available) topY += (available - contentHeight) / 2;
		this.addScrollBody(PANEL, topY, bottomY, lines);
	}

	private void addOriginHitbox() {
		String label = truncateToWidth(this.font, originFull, panelWidth(PANEL));
		int width = Math.max(1, this.font.width(label));
		if (label.equals(originFull)) return;
		Button hit = buttonWidget(this.width / 2 - width / 2, 27, width, 12, VersionedText.literal(""), button -> {});
		this.addRenderableWidget(hit);
		setTooltip(hit, VersionedText.literal(originFull));
	}

	private void replaceLeftoverPlaceholder(List<Button> buttons, boolean notes) {
		int index = notes ? 1 : 0;
		if (index >= buttons.size()) return;
		Button placeholder = buttons.get(index);
		this.removeWidget(placeholder);
		/*? if >=1.19.4 {*/
		int x = placeholder.getX();
		int y = placeholder.getY();
		/*?} else {*/
		/*int x = placeholder.x;
		int y = placeholder.y;
		*//*?}*/
		Component label = PackConfirmCopy.leftoverLabel(keepExistingMods, updater.firstInstallLocalModCount());
		AbstractWidget checkbox = checkboxWidget(this.font, x, y, placeholder.getWidth(), ActionAreaLayout.BUTTON_HEIGHT, label, keepExistingMods, value -> {
			keepExistingMods = value;
			updater.setFirstInstallLocalModCleanup(!keepExistingMods);
			rebuild();
		});
		this.addRenderableWidget(checkbox);
		String joined = String.join("\n", wrapToWidth(this.font, String.join(", ", updater.firstInstallLocalModPaths()), 240, 8));
		/*? if > 1.19.2 {*/
		checkbox.setTooltip(net.minecraft.client.gui.components.Tooltip.create(VersionedText.translatable("automodpack.firstConnect.cleanupTooltip", joined)));
		/*?}*/
	}

	private List<String> buildBodyLines() {
		int wrapWidth = Math.max(1, panelWidth(PANEL) - 20);
		List<String> lines = new ArrayList<>();
		lines.addAll(wrapToWidth(this.font, PackConfirmCopy.intro(originFull), wrapWidth));
		lines.add("");
		lines.addAll(wrapToWidth(this.font, PackConfirmCopy.matchedHonesty(), wrapWidth));
		lines.add("");
		lines.addAll(wrapToWidth(this.font, PackConfirmCopy.computerRisk(), wrapWidth));
		lines.add("");
		lines.addAll(wrapToWidth(this.font, PackConfirmCopy.sharedCommands(), wrapWidth));
		lines.add("");
		lines.add(PackConfirmCopy.selectedSummary(updater.getSelectedTarget()));
		return lines;
	}

	private void download() {
		if (finished) return;
		if (updater.getConfirmationState() != ModpackUpdater.ConfirmationState.WAITING) {
			ScreenImpl.multiplayer();
			return;
		}
		finished = true;
		updater.setFirstInstallLocalModCleanup(!keepExistingMods);
		ScreenManager.waiting();
		updater.startConfirmedUpdate();
	}

	private void customize() {
		if (finished) return;
		Consumer<SelectionIntent> action = intent -> {
			try {
				if (updater.getConfirmationState() != ModpackUpdater.ConfirmationState.WAITING) throw new IllegalStateException("Modpack confirmation is no longer active");
				updater.setFirstInstallLocalModCleanup(!keepExistingMods);
				updater.reselectAndPreview(intent);
			} catch (RuntimeException e) {
				finished = false;
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.MULTIPLAYER, null));
			}
		};
		ScreenImpl.setScreen(new ModpackSelectionScreen(this, updater, action));
	}

	private void openFiles() {
		var target = updater.getSelectedTarget();
		ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.translatable("automodpack.browser.previewTitle"), VersionedText.translatable("automodpack.firstConnect.description"), PackConfirmCopy.catalogue(target), PackConfirmCopy.featureNames(target.manifest())));
	}

	private void openPatchNotes() {
		ScreenImpl.setScreen(new PatchNotesHistoryScreen(this, updater.getFirstInstallPatchNotes(), updater.getSelectedTarget().manifest().modpackName()));
	}

	private void cancel() {
		if (finished) return;
		finished = true;
		updater.cancelConfirmation();
		ScreenImpl.multiplayer();
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
	public void tick() {
		super.tick();
		if (finished && updater.getConfirmationState() == ModpackUpdater.ConfirmationState.WAITING) {
			finished = false;
			return;
		}
		if (finished) return;
		if (updater.getConfirmationState() != ModpackUpdater.ConfirmationState.CANCELLED) return;
		finished = true;
		ScreenImpl.multiplayer();
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String name = updater.getSelectedTarget().manifest().modpackName().isBlank() ? "AutoModpack" : updater.getSelectedTarget().manifest().modpackName();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, name, panelWidth(PANEL))).withStyle(ChatFormatting.WHITE), this.width / 2, 14, TextColors.WHITE);
		String originLabel = truncateToWidth(this.font, originFull, panelWidth(PANEL));
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(originLabel).withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD), this.width / 2, 29, TextColors.WHITE);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::cancel);
	}
}
