package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;
/*? if > 1.19.2 {*/
import net.minecraft.client.gui.components.Tooltip;
/*?}*/

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.TextScrollWidget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** First-install confirm when every selected jar matched Modrinth or CurseForge. */
public final class MatchedPackConfirmScreen extends VersionedScreen {
	private static final int BODY = 420;
	private static final int LINE = TextScrollWidget.ROW_HEIGHT;
	private final ModpackUpdater updater;
	private boolean keepExistingMods;
	private boolean finished;
	private String originFull = "";
	private String originDisplay = "";
	private int bodyTop = 42;
	private List<MutableComponent> bodyLines = List.of();

	public MatchedPackConfirmScreen(ModpackUpdater updater) {
		super(VersionedText.translatable("automodpack.firstConnect.title"));
		this.updater = Objects.requireNonNull(updater, "updater");
	}

	@Override
	protected void init() {
		super.init();
		originFull = updater.joinOrigin();
		originDisplay = truncateToWidth(this.font, PackConfirmCopy.displayOrigin(originFull), panelWidth(BODY) - 8);
		boolean leftover = updater.firstInstallLocalModCount() > 0;
		boolean notes = Changelogs.hasNotes(updater.getFirstInstallPatchNotes());
		boolean customize = PackConfirmCopy.canCustomize(updater.getSelectedTarget().manifest());
		List<ActionRow> rows = new ArrayList<>();
		if (notes) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.patchNotes.all"), button -> openPatchNotes())));
		if (leftover) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.literal(" "), button -> {})));
		if (customize) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(PackConfirmCopy.customizeLabel(), button -> customize())));
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(VersionedText.translatable("automodpack.firstConnect.cancel"), button -> cancel()),
				optionalAction(VersionedText.translatable("automodpack.browser.reviewFiles"), button -> openFiles()),
				primaryAction(VersionedText.translatable("automodpack.firstConnect.download"), button -> download())));
		ActionRow[] rowArray = rows.toArray(ActionRow[]::new);
		List<Button> buttons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray);
		if (leftover) replaceLeftoverPlaceholder(buttons, notes);
		int bottomY = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray) - 4;
		List<MutableComponent> lines = buildBodyLines();
		int contentHeight = Math.max(LINE, lines.size() * LINE);
		int available = Math.max(LINE, bottomY - 42);
		if (contentHeight <= available) {
			bodyTop = 42 + (available - contentHeight) / 2;
			bodyLines = lines;
		} else {
			bodyTop = 42;
			bodyLines = List.of();
			this.addCenteredScrollBody(BODY, 42, bottomY, lines);
		}
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
		checkbox.setTooltip(Tooltip.create(VersionedText.translatable("automodpack.firstConnect.cleanupTooltip", joined)));
		/*?}*/
	}

	private List<MutableComponent> buildBodyLines() {
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		List<MutableComponent> lines = new ArrayList<>();
		lines.addAll(wrapWithHighlight(this.font, PackConfirmCopy.intro(originDisplay), originDisplay, wrapWidth, ChatFormatting.YELLOW, ChatFormatting.BOLD));
		lines.add(blankLine());
		appendStatLines(lines, wrapWidth);
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, PackConfirmCopy.matchedHonesty(), wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, PackConfirmCopy.computerRisk(), wrapWidth));
		lines.add(blankLine());
		lines.addAll(wrapParagraph(this.font, PackConfirmCopy.sharedCommands(), wrapWidth, ChatFormatting.YELLOW));
		return lines;
	}

	private void appendStatLines(List<MutableComponent> lines, int wrapWidth) {
		var target = updater.getSelectedTarget();
		appendStat(lines, wrapWidth, PackConfirmCopy.selectedSummary(target), ChatFormatting.GREEN);
		appendStat(lines, wrapWidth, PackConfirmCopy.existingMods(keepExistingMods, updater.firstInstallLocalModCount()), keepExistingMods ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
		appendStat(lines, wrapWidth, PackConfirmCopy.requestedGroups(target), ChatFormatting.WHITE);
		appendStat(lines, wrapWidth, PackConfirmCopy.includedGroups(target), ChatFormatting.WHITE);
		appendStat(lines, wrapWidth, PackConfirmCopy.staleRequestedGroups(target), ChatFormatting.RED);
		appendStat(lines, wrapWidth, PackConfirmCopy.requestedUnavailableGroups(target), ChatFormatting.RED);
	}

	private void appendStat(List<MutableComponent> lines, int wrapWidth, String text, ChatFormatting style) {
		if (text.isEmpty()) return;
		lines.addAll(wrapParagraph(this.font, text, wrapWidth, style));
	}

	private void download() {
		if (finished || updater.getConfirmationState() != ModpackUpdater.ConfirmationState.WAITING) return;
		finished = true;
		updater.setFirstInstallLocalModCleanup(!keepExistingMods);
		ScreenManager.waiting(updater::cancelFromPlayer);
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
		ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.translatable("automodpack.browser.previewTitle"), VersionedText.translatable("automodpack.firstConnect.description"), PackConfirmCopy.catalogue(updater), PackConfirmCopy.featureNames(target.manifest())));
	}

	private void openPatchNotes() {
		ScreenImpl.setScreen(new PatchNotesHistoryScreen(this, updater.getFirstInstallPatchNotes(), updater.getSelectedTarget().manifest().modpackName()));
	}

	private void cancel() {
		if (updater.getConfirmationState() == ModpackUpdater.ConfirmationState.WAITING) updater.cancelConfirmation();
		finished = true;
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
		if (updater.getConfirmationState() == ModpackUpdater.ConfirmationState.CANCELLED) {
			finished = true;
			ScreenImpl.multiplayer();
			return;
		}
		if (finished && updater.getConfirmationState() == ModpackUpdater.ConfirmationState.WAITING && !updater.isCancelledByPlayer()) finished = false;
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String name = updater.getSelectedTarget().manifest().modpackName().isBlank() ? "AutoModpack" : updater.getSelectedTarget().manifest().modpackName();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, name, panelWidth(BODY))).withStyle(ChatFormatting.WHITE), this.width / 2, 14, TextColors.WHITE);
		int y = bodyTop;
		for (MutableComponent line : bodyLines) {
			drawCenteredTextWithShadow(matrices, this.font, line, this.width / 2, y, TextColors.WHITE);
			y += LINE;
		}
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::cancel);
	}
}
