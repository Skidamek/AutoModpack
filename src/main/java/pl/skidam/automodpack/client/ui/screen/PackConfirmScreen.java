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
import pl.skidam.automodpack.client.ui.widget.CheckboxWidget;
import pl.skidam.automodpack.client.ui.widget.Countdown;
import pl.skidam.automodpack.client.ui.widget.UnverifiedJarList;
import pl.skidam.automodpack_core.client.Changelogs;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.HistoryViewRequest;
import pl.skidam.automodpack_core.screen.PreviewPayload;
import pl.skidam.automodpack_core.screen.ReviewActions;
import pl.skidam.automodpack_core.screen.ReviewPayload;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;

/** Confirm before an update starts; the unverified-jar list and typed-ack gate appear only when unverified jars were selected. */
public final class PackConfirmScreen extends VersionedScreen {
	private static final int BODY = 420;
	private static final int TIMER_SECONDS = 10;
	private final ReviewPayload welcome;
	private final PreviewPayload later;
	private final ReviewActions actions;
	private final boolean firstInstall;
	private final boolean unverified;
	private final Screen parent;
	private final UpdatePreview laterPreview;
	private final Runnable laterContinue;
	private final Runnable laterCancel;
	private final Countdown countdown = new Countdown(TIMER_SECONDS);
	private final List<String> unverifiedPaths = new ArrayList<>();
	private final List<UnverifiedJarList.UnverifiedFile> unverifiedFiles = new ArrayList<>();
	private boolean keepExistingMods;
	private boolean acknowledged;
	private boolean finished;
	private AbstractWidget cancelButton;
	private AbstractWidget primaryButton;
	private CheckboxWidget leftoverCheckbox;
	private CheckboxWidget ackCheckbox;
	private int ackReasonY = -1;
	private String originFull = "";
	private String originDisplay = "";

	/** First-install confirm; every selected jar matched Modrinth or CurseForge unless unverified jars were picked. */
	public PackConfirmScreen(ReviewPayload payload) {
		super(VersionedText.text("automodpack.firstConnect.title"));
		this.welcome = Objects.requireNonNull(payload, "payload");
		this.later = null;
		this.actions = payload.actions();
		this.firstInstall = true;
		this.unverified = !payload.unverifiedJarPaths().isEmpty();
		this.parent = null;
		this.laterPreview = null;
		this.laterContinue = null;
		this.laterCancel = null;
	}

	/** Confirm before writing unverified jars on a later update or generation rollback. */
	public PackConfirmScreen(Screen parent, PreviewPayload payload) {
		super(VersionedText.text(UpdatePreviewScreen.titleKey(payload.preview().mode())));
		this.welcome = null;
		this.later = Objects.requireNonNull(payload, "payload");
		this.actions = Objects.requireNonNull(payload.actions(), "actions");
		this.firstInstall = false;
		this.unverified = true;
		this.parent = parent;
		this.laterPreview = Objects.requireNonNull(payload.preview(), "preview");
		this.laterContinue = Objects.requireNonNull(payload.continueAction(), "continueAction");
		this.laterCancel = Objects.requireNonNull(payload.cancelAction(), "cancelAction");
	}

	private SelectedModpackTarget target() {
		return firstInstall ? welcome.target() : later.target();
	}

	private List<String> unverifiedJarPaths() {
		return firstInstall ? welcome.unverifiedJarPaths() : later.unverifiedJarPaths();
	}

	@Override
	protected void init() {
		super.init();
		originFull = firstInstall ? welcome.origin() : "";
		originDisplay = truncateToWidth(this.font, PackConfirmCopy.displayOrigin(originFull), panelWidth(BODY) - 8);
		refreshUnverifiedFiles();
		if (unverified) {
			// The risk read rearms while the player has not consented yet, so returning from a sub-screen
			// restarts the countdown; a given acknowledgement survives coming back from review or history.
			if (unverifiedPaths.isEmpty() || acknowledged) countdown.finish();
			else countdown.restart();
		} else {
			countdown.finish();
		}

		boolean leftover = firstInstall && welcome.firstInstallLocalModPaths().size() > 0;
		boolean customize = PackConfirmCopy.canCustomize(target().manifest());
		boolean notes = firstInstall
				? Changelogs.hasNotes(welcome.patchNotes())
				: laterPreview != null && Changelogs.hasNotes(laterPreview.journal());

		ActionDefinition historyAction = notes ? optionalAction(VersionedText.text("automodpack.management.history"), button -> openHistory()) : null;
		ActionDefinition customizeAction = customize ? optionalAction(PackConfirmCopy.customizeLabel(), button -> customize()) : null;
		Component cancelLabel = VersionedText.text(firstInstall ? "automodpack.firstConnect.cancel" : "automodpack.back");
		Component primaryLabel = VersionedText.text(firstInstall ? "automodpack.firstConnect.download" : UpdatePreviewScreen.actionKey(laterPreview.mode()));
		ActionDefinition cancelAction = secondaryAction(cancelLabel, button -> cancel());
		ActionDefinition reviewAction = optionalAction(VersionedText.text("automodpack.browser.reviewFiles"), button -> openFiles());
		ActionDefinition primaryDef = primaryAction(primaryLabel, button -> confirm());
		List<ActionRow> rows = new ArrayList<>();
		// History and Customize are both compact optional actions: one shared row instead of two full-width ones.
		if (historyAction != null && customizeAction != null) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, historyAction, customizeAction));
		else if (historyAction != null) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, historyAction));
		else if (customizeAction != null) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, customizeAction));
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER, cancelAction, reviewAction, primaryDef));
		ActionRow[] rowArray = rows.toArray(ActionRow[]::new);
		this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray);
		// The consent choices live in the body next to the text they belong to; layoutBody places them.
		leftoverCheckbox = null;
		if (leftover) {
			leftoverCheckbox = new CheckboxWidget(this.font, 0, 0, panelWidth(BODY), PackConfirmCopy.leftoverLabel(welcome.firstInstallLocalModPaths().size()), keepExistingMods, value -> {
				keepExistingMods = value;
				actions.setFirstInstallLocalModCleanup().accept(!keepExistingMods);
				// The checkbox label is constant now; the rebuild only refreshes the existing-mods summary line.
				rebuild();
			});
			String joined = String.join("\n", wrapToWidth(this.font, String.join(", ", welcome.firstInstallLocalModPaths()), 240, 8));
			VersionedScreen.setTooltip(leftoverCheckbox, VersionedText.text("automodpack.confirm.leftoverTooltip", joined));
			this.addRenderableWidget(leftoverCheckbox);
		}
		ackCheckbox = null;
		if (unverified) {
			ackCheckbox = new CheckboxWidget(this.font, 0, 0, panelWidth(BODY), PackConfirmCopy.ackLabel(), acknowledged, this::onAckToggled);
			// The risk acknowledgement stays locked until the read countdown ran out.
			ackCheckbox.active = !countdown.running();
			this.addRenderableWidget(ackCheckbox);
		}
		cancelButton = cancelAction.widget();
		primaryButton = primaryDef.widget();
		primaryButton.active = !unverified || (!countdown.running() && acknowledged);
		if (unverified) {
			// A real focus (not the deferred initial-focus request) keeps the highlighted state on every version.
			this.setFocused(cancelButton);
		}

		int bottomY = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray);
		layoutBody(bottomY);
	}

	/** Snapshots the unverified set; the lookup settles before this screen opens, so the set never moves while it is open. */
	private void refreshUnverifiedFiles() {
		List<String> currentPaths = unverifiedJarPaths();
		unverifiedPaths.clear();
		unverifiedPaths.addAll(currentPaths);
		unverifiedFiles.clear();
		for (String path : currentPaths) unverifiedFiles.add(new UnverifiedJarList.UnverifiedFile(path, PackConfirmCopy.selectedJarSize(target(), path)));
	}

	/** The acknowledge checkbox is inactive until the read timer ran out, so a flip is always a real consent. */
	private void onAckToggled(boolean value) {
		acknowledged = value;
		if (primaryButton != null) primaryButton.active = acknowledged;
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
		appendSourceLines(topLines, wrapWidth);
		topLines.add(blankLine());
		List<MutableComponent> bottomLines = new ArrayList<>();
		bottomLines.addAll(wrapParagraph(this.font, PackConfirmCopy.unverifiedExplain(), wrapWidth, ChatFormatting.RED));
		bottomLines.add(blankLine());
		bottomLines.addAll(wrapParagraph(this.font, PackConfirmCopy.computerRisk(), wrapWidth, ChatFormatting.RED));
		bottomLines.add(blankLine());
		bottomLines.addAll(wrapParagraph(this.font, PackConfirmCopy.sharedCommands(), wrapWidth, ChatFormatting.YELLOW));
		if (laterPreview != null && laterPreview.mode() == UpdatePreview.Mode.ROLLBACK) {
			bottomLines.add(blankLine());
			bottomLines.addAll(wrapParagraph(this.font, VersionedText.text("automodpack.update.rollbackDetaches").getString(), wrapWidth, ChatFormatting.YELLOW));
		}

		int topHeight = topLines.size() * LINE_HEIGHT;
		int bottomHeight = bottomLines.size() * LINE_HEIGHT;
		int consentHeight = (leftoverCheckbox == null ? 0 : ActionAreaLayout.SEAM + leftoverCheckbox.boxHeight())
				+ (ackCheckbox == null ? 0 : ActionAreaLayout.SEAM + ackCheckbox.boxHeight());
		int available = Math.max(LINE_HEIGHT, bottomY - 42);
		int listRows = preferredListRows(available - topHeight - bottomHeight - consentHeight - 2 * ActionAreaLayout.SEAM);
		int listHeight = listRows * UnverifiedJarList.ROW_HEIGHT;
		int needed = topHeight + consentHeight + ActionAreaLayout.SEAM + listHeight + ActionAreaLayout.SEAM + bottomHeight;

		if (needed > available) {
			listRows = Math.max(3, listRows - 1);
			listHeight = listRows * UnverifiedJarList.ROW_HEIGHT;
			needed = topHeight + consentHeight + ActionAreaLayout.SEAM + listHeight + ActionAreaLayout.SEAM + bottomHeight;
		}
		if (needed <= available) {
			// The whole assembly centers, so a short window never opens a hole between the blocks; the risk
			// acknowledgement sits directly under the risk paragraphs it consents to.
			int assemblyTop = 42 + (available - needed) / 2;
			placeUnverifiedBody(assemblyTop, topLines, bottomLines, topHeight, listHeight, bottomHeight);
			return;
		}

		int scrollBottom = pinConsentCheckboxes(bottomY);
		List<MutableComponent> all = new ArrayList<>(topLines);
		all.add(blankLine());
		for (UnverifiedJarList.UnverifiedFile file : unverifiedFiles)
			all.addAll(wrapParagraph(this.font, file.size() > 0 ? file.path() + " · " + UiFormat.formatSize(file.size()) : file.path(), wrapWidth, ChatFormatting.GRAY));
		all.add(blankLine());
		all.addAll(bottomLines);
		this.addCenteredScrollBody(BODY, 42, scrollBottom, all);
	}

	/** Pins the consent checkboxes above the action rail when the body overflows; returns the scroll body's new bottom. */
	private int pinConsentCheckboxes(int bottomY) {
		int nextBottom = bottomY;
		if (ackCheckbox != null) {
			ackCheckbox.moveTo(panelLeft(BODY), nextBottom - ackCheckbox.boxHeight());
			// The reason sits between the ack and whatever is stacked above it.
			ackReasonY = ackCheckbox.yPosition() - 11;
			nextBottom = ackCheckbox.yPosition() - 20;
		}
		if (leftoverCheckbox != null) {
			leftoverCheckbox.moveTo(panelLeft(BODY), nextBottom - leftoverCheckbox.boxHeight());
			nextBottom = leftoverCheckbox.yPosition() - ActionAreaLayout.SEAM;
		}
		return nextBottom;
	}

	/** The platform lookup has settled before this screen opens, so the unverified count is final and red. */
	private void appendSourceLines(List<MutableComponent> lines, int wrapWidth) {
		int jars = PackConfirmCopy.selectedJarCount(target());
		lines.addAll(wrapParagraph(this.font, PackConfirmCopy.unverifiedCount(unverifiedPaths.size(), jars), wrapWidth, ChatFormatting.RED));
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
		int bodyFooter = footerTop;
		if (leftoverCheckbox != null) {
			// The keep choice pins above the rail; the dialog column stops above it.
			leftoverCheckbox.moveTo(panelLeft(BODY), footerTop - ActionAreaLayout.SEAM - leftoverCheckbox.boxHeight());
			bodyFooter = leftoverCheckbox.yPosition();
		}
		DialogColumn column = layoutDialogColumn(42, bodyFooter, lines.size() * LINE_HEIGHT, 0);
		this.addCenteredScrollBody(BODY, column.bodyTop(), column.bodyBottom(), lines);
	}

	private void appendStatLines(List<MutableComponent> lines, int wrapWidth) {
		appendStat(lines, wrapWidth, PackConfirmCopy.selectedSummary(target()), ChatFormatting.GREEN);
		// Where the jars come from: platform matches overlap, server-only equals the unverified set.
		appendStat(lines, wrapWidth, PackConfirmCopy.sourceCounts(firstInstall ? welcome.sourceCounts() : later.sourceCounts()), ChatFormatting.GRAY);
		// Same stat the update preview shows: what the local store still misses of the announced content.
		if (firstInstall) {
			if (welcome.uncachedTargetBytes().isPresent()) {
				appendStat(lines, wrapWidth,
						VersionedText.text("automodpack.firstConnect.downloadSummary", UiFormat.formatSize(welcome.uncachedTargetBytes().getAsLong()), UiFormat.formatSize(PackConfirmCopy.selectedBytes(target())))
								.getString(),
						ChatFormatting.GRAY);
			}
		}
		appendStat(lines, wrapWidth, PackConfirmCopy.existingMods(keepExistingMods, firstInstall ? welcome.firstInstallLocalModPaths().size() : 0), keepExistingMods ? ChatFormatting.YELLOW : ChatFormatting.GRAY);
		appendStat(lines, wrapWidth, PackConfirmCopy.requestedGroups(target()), ChatFormatting.WHITE);
		appendStat(lines, wrapWidth, PackConfirmCopy.includedGroups(target()), ChatFormatting.WHITE);
		appendStat(lines, wrapWidth, PackConfirmCopy.staleRequestedGroups(target()), ChatFormatting.RED);
		appendStat(lines, wrapWidth, PackConfirmCopy.requestedUnavailableGroups(target()), ChatFormatting.RED);
	}

	private void appendStat(List<MutableComponent> lines, int wrapWidth, String text, ChatFormatting style) {
		if (text.isEmpty()) return;
		lines.addAll(wrapParagraph(this.font, text, wrapWidth, style));
	}

	private void placeUnverifiedBody(int topY, List<MutableComponent> topLines, List<MutableComponent> bottomLines, int topHeight, int listHeight, int bottomHeight) {
		int y = topY;
		this.addCenteredScrollBody(BODY, y, y + topHeight, topLines);
		y += topHeight;
		if (leftoverCheckbox != null) {
			y += ActionAreaLayout.SEAM;
			leftoverCheckbox.moveTo(panelLeft(BODY), y);
			y += leftoverCheckbox.boxHeight();
		}
		y += ActionAreaLayout.SEAM;
		this.addRenderableWidget(new UnverifiedJarList(this.minecraft, this.width, this.height, panelWidth(BODY), y, y + listHeight, unverifiedFiles));
		y += listHeight + ActionAreaLayout.SEAM;
		this.addCenteredScrollBody(BODY, y, y + bottomHeight, bottomLines);
		y += bottomHeight;
		if (ackCheckbox != null) {
			y += ActionAreaLayout.SEAM;
			ackCheckbox.moveTo(panelLeft(BODY), y);
			ackReasonY = y + ackCheckbox.boxHeight() + 2;
		}
	}

	private int preferredListRows(int freeHeight) {
		int prefer = freeHeight >= UnverifiedJarList.ROW_HEIGHT * 4 ? 4 : 3;
		int maxBySpace = Math.max(3, freeHeight / UnverifiedJarList.ROW_HEIGHT);
		return Math.max(3, Math.min(6, Math.min(prefer, maxBySpace)));
	}

	private void confirm() {
		if (finished && (actions == null || !actions.reviewActive().getAsBoolean() || actions.cancelledByPlayer().getAsBoolean())) return;
		if (unverified && (!acknowledged || countdown.running())) return;
		if (firstInstall) {
			if (!actions.reviewActive().getAsBoolean()) return;
			finished = true;
			actions.setFirstInstallLocalModCleanup().accept(!keepExistingMods);
			ScreenManager.waiting(actions.cancelFromPlayer());
			actions.startConfirmedUpdate().run();
			return;
		}
		finished = true;
		ScreenManager.waiting(actions.cancelFromPlayer());
		laterContinue.run();
	}

	private void customize() {
		if (finished) return;
		Consumer<SelectionIntent> action = intent -> {
			try {
				if (!unverified && !actions.reviewActive().getAsBoolean()) throw new IllegalStateException("Modpack confirmation is no longer active");
				if (firstInstall) actions.setFirstInstallLocalModCleanup().accept(!keepExistingMods);
				actions.reselectAndPreview().accept(intent);
			} catch (RuntimeException e) {
				finished = false;
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.update", FailureCategory.UPDATE, FailureDestination.MULTIPLAYER, null));
			}
		};
		ScreenImpl.setScreen(new GroupSelectionScreen(this, target(), actions, action));
	}

	private void openFiles() {
		if (firstInstall) {
			ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.text("automodpack.browser.previewTitle"), VersionedText.text("automodpack.firstConnect.description"),
					welcome.catalogue(), PackConfirmCopy.groupNames(target().manifest()), null, List.of(), PackConfirmCopy.selectedBytes(target()), ""));
			return;
		}
		ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.text("automodpack.browser.previewTitle"), VersionedText.text(UpdatePreviewScreen.reviewKey(laterPreview.mode())),
				laterPreview.changeSet(), laterPreview.featureNames(), null, List.of(), laterPreview.uncachedAcquisitionBytes(), ""));
	}

	private void openHistory() {
		var history = firstInstall ? welcome.patchNotes() : laterPreview.journal();
		String name = target().manifest().modpackName();
		// The journal is the server's timeline, so no entry of a pending preview is installed yet.
		ScreenImpl.setScreen(new ContentHistoryScreen(this, new HistoryViewRequest(history, -1, name, () -> {})));
	}

	private void cancel() {
		if (firstInstall) {
			if (actions.reviewActive().getAsBoolean()) actions.cancelConfirmation().run();
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
		countdown.tick();
		if (!countdown.running() && ackCheckbox != null) ackCheckbox.active = true;
		if (primaryButton != null) primaryButton.active = !unverified || (!countdown.running() && acknowledged);
		if (firstInstall) {
			if (actions.reviewCancelled().getAsBoolean()) {
				finished = true;
				ScreenImpl.multiplayer();
				return;
			}
			if (finished && actions.reviewActive().getAsBoolean() && !actions.cancelledByPlayer().getAsBoolean()) finished = false;
		} else if (finished && actions.reviewActive().getAsBoolean() && !actions.cancelledByPlayer().getAsBoolean()) {
			finished = false;
		}
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String name = target().manifest().modpackName().isBlank() ? "AutoModpack" : target().manifest().modpackName();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, name, panelWidth(BODY))).withStyle(ChatFormatting.WHITE), this.width / 2, 14, TextColors.WHITE);
		// The disabled primary needs its reason on screen: the countdown while the risk read runs, the checkbox after it.
		if (!finished && !unverifiedPaths.isEmpty() && !acknowledged) {
			// The reason travels with the risk box: under it in the centered assembly, above it when pinned.
			int reasonY = ackReasonY >= 0 ? ackReasonY : this.height - 40;
			if (countdown.running()) drawCountdown(matrices, VersionedText.text("automodpack.confirm.ackCountdown", countdown.secondsRemaining()), reasonY);
			else drawCenteredTextWithShadow(matrices, this.font, VersionedText.text("automodpack.confirm.ackUnlock").withStyle(ChatFormatting.GRAY), this.width / 2, reasonY, TextColors.WHITE);
		}
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
