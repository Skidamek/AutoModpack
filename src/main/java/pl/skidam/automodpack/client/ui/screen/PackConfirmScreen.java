package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.UiFormat;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.TextScrollWidget;
import pl.skidam.automodpack.client.ui.widget.UnverifiedJarList;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.HistoryViewRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Confirm before an update starts; the unverified-jar list and typed-ack gate appear only when unverified jars were selected. */
public final class PackConfirmScreen extends VersionedScreen {
	private static final int BODY = 420;
	private static final int TIMER_TICKS = 10 * 20;
	private final ModpackUpdater updater;
	private final boolean firstInstall;
	private final boolean unverified;
	private final Screen parent;
	private final UpdatePreview laterPreview;
	private final Runnable laterContinue;
	private final Runnable laterCancel;
	private final List<String> unverifiedPaths = new ArrayList<>();
	private final List<UnverifiedJarList.UnverifiedFile> unverifiedFiles = new ArrayList<>();
	private boolean keepExistingMods;
	private boolean acknowledged;
	private boolean finished;
	private int ticksRemaining = TIMER_TICKS;
	private AbstractWidget cancelButton;
	private AbstractWidget primaryButton;
	private AbstractWidget ackCheckbox;
	private String originFull = "";
	private String originDisplay = "";
	private String previousUnverifiedKey = "";

	/** First-install confirm; every selected jar matched Modrinth or CurseForge unless unverified jars were picked. */
	public PackConfirmScreen(ModpackUpdater updater) {
		super(VersionedText.translatable("automodpack.firstConnect.title"));
		this.updater = Objects.requireNonNull(updater, "updater");
		this.firstInstall = true;
		this.unverified = !updater.unverifiedSelectedJarPaths().isEmpty();
		this.parent = null;
		this.laterPreview = null;
		this.laterContinue = null;
		this.laterCancel = null;
		this.unverifiedPaths.addAll(updater.unverifiedSelectedJarPaths());
		this.previousUnverifiedKey = String.join("\n", unverifiedPaths);
	}

	/** Confirm before writing unverified jars on a later update. */
	public PackConfirmScreen(Screen parent, ModpackUpdater updater, UpdatePreview preview, Runnable continueAction, Runnable cancelAction) {
		super(VersionedText.translatable("automodpack.update.title"));
		this.updater = Objects.requireNonNull(updater, "updater");
		this.firstInstall = false;
		this.unverified = true;
		this.parent = parent;
		this.laterPreview = Objects.requireNonNull(preview, "preview");
		this.laterContinue = Objects.requireNonNull(continueAction, "continueAction");
		this.laterCancel = Objects.requireNonNull(cancelAction, "cancelAction");
		this.unverifiedPaths.addAll(updater.unverifiedSelectedJarPaths());
		this.previousUnverifiedKey = String.join("\n", unverifiedPaths);
	}

	@Override
	protected void init() {
		super.init();
		originFull = updater.joinOrigin();
		originDisplay = truncateToWidth(this.font, PackConfirmCopy.displayOrigin(originFull), panelWidth(BODY) - 8);
		List<String> currentPaths = updater.unverifiedSelectedJarPaths();
		String currentKey = String.join("\n", currentPaths);
		if (!currentKey.equals(previousUnverifiedKey)) {
			previousUnverifiedKey = currentKey;
			acknowledged = false;
			ticksRemaining = currentPaths.isEmpty() ? 0 : TIMER_TICKS;
		}
		unverifiedPaths.clear();
		unverifiedPaths.addAll(currentPaths);
		unverifiedFiles.clear();
		var target = updater.getSelectedTarget();
		for (String path : currentPaths) unverifiedFiles.add(new UnverifiedJarList.UnverifiedFile(path, PackConfirmCopy.selectedJarSize(target, path)));

		boolean leftover = firstInstall && updater.firstInstallLocalModCount() > 0;
		boolean customize = PackConfirmCopy.canCustomize(updater.getSelectedTarget().manifest());
		boolean notes = firstInstall
				? Changelogs.hasNotes(updater.getFirstInstallPatchNotes())
				: laterPreview != null && Changelogs.hasNotes(laterPreview.journal());

		List<ActionRow> rows = new ArrayList<>();
		if (notes) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.management.history"), button -> openHistory())));
		if (leftover)
			rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, checkboxAction(PackConfirmCopy.leftoverLabel(updater.firstInstallLocalModCount()), keepExistingMods, value -> {
				keepExistingMods = value;
				updater.setFirstInstallLocalModCleanup(!keepExistingMods);
				// The checkbox label is constant now; the rebuild only refreshes the existing-mods summary line.
				rebuild();
			})));
		if (customize) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(PackConfirmCopy.customizeLabel(), button -> customize())));
		if (unverified) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, checkboxAction(ackMessage(), acknowledged, value -> onAckToggled(value))));
		Component cancelLabel = VersionedText.translatable(firstInstall ? "automodpack.firstConnect.cancel" : "automodpack.back");
		Component primaryLabel = VersionedText.translatable(firstInstall ? "automodpack.firstConnect.download" : "automodpack.update.apply");
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(cancelLabel, button -> cancel()),
				optionalAction(VersionedText.translatable("automodpack.browser.reviewFiles"), button -> openFiles()),
				primaryAction(primaryLabel, button -> confirm())));
		ActionRow[] rowArray = rows.toArray(ActionRow[]::new);
		List<AbstractWidget> widgets = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray);
		int widgetIndex = 0;
		if (notes) widgetIndex++;
		if (leftover) {
			String joined = String.join("\n", wrapToWidth(this.font, String.join(", ", updater.firstInstallLocalModPaths()), 240, 8));
			VersionedScreen.setTooltip(widgets.get(widgetIndex), VersionedText.translatable("automodpack.confirm.leftoverTooltip", joined));
			widgetIndex++;
		}
		if (customize) widgetIndex++;
		if (unverified) {
			ackCheckbox = widgets.get(widgetIndex);
			// The risk acknowledgement stays locked until the read countdown ran out.
			ackCheckbox.active = ticksRemaining <= 0;
			cancelButton = widgets.get(widgetIndex + 1);
			primaryButton = widgets.get(widgetIndex + 3);
			primaryButton.active = ticksRemaining <= 0 && acknowledged;
			this.setInitialFocus(cancelButton);
		}

		int bottomY = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray);
		layoutBody(bottomY);
	}

	/** The acknowledge checkbox flips only after the read timer ran out; the primary gate follows it. */
	private void onAckToggled(boolean value) {
		if (ticksRemaining > 0) {
			acknowledged = false;
			if (ackCheckbox != null) ackCheckbox.setMessage(ackMessage());
			if (primaryButton != null) primaryButton.active = false;
			return;
		}
		acknowledged = value;
		if (primaryButton != null) primaryButton.active = acknowledged;
	}

	private MutableComponent ackMessage() {
		MutableComponent label = PackConfirmCopy.ackLabel();
		int seconds = (ticksRemaining + 19) / 20;
		if (seconds > 0) label = label.append(" (" + seconds + "s)");
		return label;
	}

	private void layoutBody(int footerTop) {
		if (!unverified) {
			layoutMatchedBody(footerTop);
			return;
		}
		int bottomY = footerTop - 4;
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		List<MutableComponent> topLines = new ArrayList<>();
		if (firstInstall) {
			topLines.addAll(wrapWithHighlight(this.font, PackConfirmCopy.intro(originDisplay), originDisplay, wrapWidth, ChatFormatting.YELLOW, ChatFormatting.BOLD));
			topLines.add(blankLine());
		}
		appendStatLines(topLines, wrapWidth);
		topLines.add(blankLine());
		int jars = PackConfirmCopy.selectedJarCount(updater.getSelectedTarget());
		topLines.addAll(wrapParagraph(this.font, PackConfirmCopy.unverifiedCount(unverifiedPaths.size(), jars), wrapWidth, ChatFormatting.RED));
		List<MutableComponent> bottomLines = new ArrayList<>();
		bottomLines.addAll(wrapParagraph(this.font, PackConfirmCopy.unverifiedExplain(), wrapWidth, ChatFormatting.RED));
		bottomLines.add(blankLine());
		bottomLines.addAll(wrapParagraph(this.font, PackConfirmCopy.computerRisk(), wrapWidth, ChatFormatting.RED));
		bottomLines.add(blankLine());
		bottomLines.addAll(wrapParagraph(this.font, PackConfirmCopy.sharedCommands(), wrapWidth, ChatFormatting.YELLOW));

		int topHeight = topLines.size() * LINE_HEIGHT;
		int bottomHeight = bottomLines.size() * LINE_HEIGHT;
		int available = Math.max(LINE_HEIGHT, bottomY - 42);
		int listRows = preferredListRows(available - topHeight - bottomHeight - 12);
		// The window carries the list's content padding, or vanilla reports a phantom scroll and clips the last row.
		int listHeight = listRows * UnverifiedJarList.ROW_HEIGHT + TextScrollWidget.CONTENT_PADDING;
		int needed = topHeight + 4 + listHeight + 4 + bottomHeight;

		if (needed > available) {
			listRows = Math.max(3, listRows - 1);
			listHeight = listRows * UnverifiedJarList.ROW_HEIGHT + TextScrollWidget.CONTENT_PADDING;
			needed = topHeight + 4 + listHeight + 4 + bottomHeight;
		}
		if (needed <= available) {
			// The whole assembly centers, so a short window never opens a hole between the blocks.
			int assemblyTop = 42 + (available - needed) / 2;
			placeUnverifiedBody(assemblyTop, assemblyTop + needed, topLines, bottomLines, topHeight, listHeight);
			return;
		}

		List<MutableComponent> all = new ArrayList<>(topLines);
		all.add(blankLine());
		for (UnverifiedJarList.UnverifiedFile file : unverifiedFiles)
			all.addAll(wrapParagraph(this.font, file.size() > 0 ? file.path() + " · " + UiFormat.formatSize(file.size()) : file.path(), wrapWidth, ChatFormatting.GRAY));
		all.add(blankLine());
		all.addAll(bottomLines);
		this.addCenteredScrollBody(BODY, 42, bottomY, all);
	}

	/** The matched layout: one centered scroll body between the pinned title and the action area. */
	private void layoutMatchedBody(int footerTop) {
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
		DialogColumn column = layoutDialogColumn(42, footerTop, lines.size() * LINE_HEIGHT, 0);
		this.addCenteredScrollBody(BODY, column.bodyTop(), column.bodyBottom(), lines);
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

	private void placeUnverifiedBody(int topY, int bottomY, List<MutableComponent> topLines, List<MutableComponent> bottomLines, int topHeight, int listHeight) {
		this.addCenteredScrollBody(BODY, topY, topY + topHeight + 2, topLines);
		int listTop = topY + topHeight + 4;
		this.addRenderableWidget(new UnverifiedJarList(this.minecraft, this.width, this.height, panelWidth(BODY), listTop, listTop + listHeight, unverifiedFiles));
		this.addCenteredScrollBody(BODY, listTop + listHeight + 4, bottomY, bottomLines);
	}

	private int preferredListRows(int freeHeight) {
		int prefer = freeHeight >= UnverifiedJarList.ROW_HEIGHT * 4 ? 4 : 3;
		int maxBySpace = Math.max(3, freeHeight / UnverifiedJarList.ROW_HEIGHT);
		return Math.max(3, Math.min(6, Math.min(prefer, maxBySpace)));
	}

	private void confirm() {
		if (finished) return;
		if (unverified && (!acknowledged || ticksRemaining > 0)) return;
		if (firstInstall) {
			if (updater.getConfirmationState() != ModpackUpdater.ConfirmationState.WAITING) return;
			finished = true;
			updater.setFirstInstallLocalModCleanup(!keepExistingMods);
			ScreenManager.waiting(updater::cancelFromPlayer);
			updater.startConfirmedUpdate();
			return;
		}
		finished = true;
		ScreenManager.waiting(updater::cancelFromPlayer);
		laterContinue.run();
	}

	private void customize() {
		if (finished) return;
		Consumer<SelectionIntent> action = intent -> {
			try {
				if (!unverified && updater.getConfirmationState() != ModpackUpdater.ConfirmationState.WAITING) throw new IllegalStateException("Modpack confirmation is no longer active");
				if (firstInstall) updater.setFirstInstallLocalModCleanup(!keepExistingMods);
				updater.reselectAndPreview(intent);
			} catch (RuntimeException e) {
				finished = false;
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.MULTIPLAYER, null));
			}
		};
		ScreenImpl.setScreen(new ModpackSelectionScreen(this, updater, action));
	}

	private void openFiles() {
		if (firstInstall) {
			var target = updater.getSelectedTarget();
			ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.translatable("automodpack.browser.previewTitle"), VersionedText.translatable("automodpack.firstConnect.description"),
					PackConfirmCopy.catalogue(updater), PackConfirmCopy.groupNames(target.manifest()), null, List.of(), true, PackConfirmCopy.selectedBytes(target), ""));
			return;
		}
		ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.translatable("automodpack.browser.previewTitle"), VersionedText.translatable("automodpack.update.reviewUpdate"), laterPreview.changeSet(),
				laterPreview.featureNames(), null, List.of(), true, laterPreview.uncachedAcquisitionBytes(), ""));
	}

	private void openHistory() {
		var history = firstInstall ? updater.getFirstInstallPatchNotes() : laterPreview.journal();
		String name = updater.getSelectedTarget().manifest().modpackName();
		// The journal is the server's timeline, so no entry of a pending preview is installed yet.
		ScreenImpl.setScreen(new ContentHistoryScreen(this, new HistoryViewRequest(history, -1, name, () -> {})));
	}

	private void cancel() {
		if (firstInstall) {
			if (updater.getConfirmationState() == ModpackUpdater.ConfirmationState.WAITING) updater.cancelConfirmation();
			finished = true;
			ScreenImpl.multiplayer();
			return;
		}
		if (!finished) laterCancel.run();
		finished = true;
		ScreenImpl.setScreen(parent);
	}

	@Override
	public void tick() {
		super.tick();
		if (ticksRemaining > 0) {
			ticksRemaining--;
			// Matched installs have no ack box; the countdown only gates unverified risk.
			if (ticksRemaining == 0 && ackCheckbox != null) {
				ackCheckbox.setMessage(ackMessage());
				ackCheckbox.active = ticksRemaining <= 0;
			}
			if (primaryButton != null) primaryButton.active = ticksRemaining <= 0 && acknowledged;
		}
		if (firstInstall) {
			if (updater.getConfirmationState() == ModpackUpdater.ConfirmationState.CANCELLED) {
				finished = true;
				ScreenImpl.multiplayer();
				return;
			}
			if (finished && updater.getConfirmationState() == ModpackUpdater.ConfirmationState.WAITING && !updater.isCancelledByPlayer()) finished = false;
		} else if (finished && updater.getConfirmationState() == ModpackUpdater.ConfirmationState.WAITING && !updater.isCancelledByPlayer()) {
			finished = false;
		}
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String name = updater.getSelectedTarget().manifest().modpackName().isBlank() ? "AutoModpack" : updater.getSelectedTarget().manifest().modpackName();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, name, panelWidth(BODY))).withStyle(ChatFormatting.WHITE), this.width / 2, 14, TextColors.WHITE);
		// The disabled primary needs its reason on screen: the gate is the risk checkbox (the label carries the countdown).
		if (!finished && primaryButton != null && !primaryButton.active && !unverifiedPaths.isEmpty())
			drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.confirm.ackUnlock").withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 40, TextColors.WHITE);
	}

	@Override
	public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
		if (unverified && isEnterKey(keyCode)) return true;
		return super.onKeyPress(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::cancel);
	}
}
