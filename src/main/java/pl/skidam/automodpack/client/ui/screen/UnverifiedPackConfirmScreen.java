package pl.skidam.automodpack.client.ui.screen;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;
import net.minecraft.network.chat.MutableComponent;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.TextScrollWidget;
import pl.skidam.automodpack.client.ui.widget.UnverifiedJarList;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.ActionAreaLayout;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Confirm before writing unverified jars on first install or a later update. */
public final class UnverifiedPackConfirmScreen extends VersionedScreen {
	private static final int BODY = 420;
	private static final int LINE = TextScrollWidget.ROW_HEIGHT;
	private static final int TIMER_TICKS = 10 * 20;
	private final ModpackUpdater updater;
	private final boolean firstInstall;
	private final Screen parent;
	private final UpdatePreview laterPreview;
	private final Runnable laterContinue;
	private final Runnable laterCancel;
	private final List<String> unverifiedPaths = new ArrayList<>();
	private boolean keepExistingMods;
	private boolean acknowledged;
	private boolean finished;
	private int ticksRemaining = TIMER_TICKS;
	private Button cancelButton;
	private Button primaryButton;
	private AbstractWidget ackCheckbox;
	private String originFull = "";
	private String originDisplay = "";
	private String previousUnverifiedKey = "";

	public UnverifiedPackConfirmScreen(ModpackUpdater updater) {
		super(VersionedText.translatable("automodpack.firstConnect.title"));
		this.updater = Objects.requireNonNull(updater, "updater");
		this.firstInstall = true;
		this.parent = null;
		this.laterPreview = null;
		this.laterContinue = null;
		this.laterCancel = null;
		this.unverifiedPaths.addAll(updater.unverifiedSelectedJarPaths());
		this.previousUnverifiedKey = String.join("\n", unverifiedPaths);
	}

	public UnverifiedPackConfirmScreen(Screen parent, ModpackUpdater updater, UpdatePreview preview, Runnable continueAction, Runnable cancelAction) {
		super(VersionedText.translatable("automodpack.update.title"));
		this.updater = Objects.requireNonNull(updater, "updater");
		this.firstInstall = false;
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

		boolean leftover = firstInstall && updater.firstInstallLocalModCount() > 0;
		boolean notes = firstInstall ? GenerationPatchNoteHistory.containsNotes(updater.getFirstInstallPatchNotes())
				: laterPreview != null && GenerationPatchNoteHistory.containsNotes(laterPreview.patchNotesHistory());

		List<ActionRow> rows = new ArrayList<>();
		if (notes) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.patchNotes.all"), button -> openPatchNotes())));
		if (leftover) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.literal(" "), button -> {})));
		rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(PackConfirmCopy.customizeLabel(), button -> customize())));
		rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.literal(" "), button -> {})));
		Component cancelLabel = VersionedText.translatable(firstInstall ? "automodpack.firstConnect.cancel" : "automodpack.cancel");
		Component primaryLabel = VersionedText.translatable(firstInstall ? "automodpack.firstConnect.download" : "automodpack.update.apply");
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(cancelLabel, button -> cancel()),
				optionalAction(VersionedText.translatable("automodpack.browser.reviewFiles"), button -> openFiles()),
				primaryAction(primaryLabel, button -> confirm())));
		ActionRow[] rowArray = rows.toArray(ActionRow[]::new);
		List<Button> buttons = this.addActionArea(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray);
		int buttonIndex = 0;
		if (notes) buttonIndex++;
		if (leftover) {
			replacePlaceholderWithLeftover(buttons.get(buttonIndex));
			buttonIndex++;
		}
		buttonIndex++;
		replacePlaceholderWithAck(buttons.get(buttonIndex));
		buttonIndex++;
		cancelButton = buttons.get(buttonIndex);
		primaryButton = buttons.get(buttonIndex + 2);
		primaryButton.active = ticksRemaining <= 0 && acknowledged;
		this.setInitialFocus(cancelButton);

		int bottomY = actionAreaTop(ActionAreaLayout.FOOTER_RAIL, this.height - 28, rowArray) - 4;
		layoutBody(bottomY);
	}

	private void layoutBody(int bottomY) {
		int wrapWidth = Math.max(1, panelWidth(BODY) - 8);
		List<MutableComponent> topLines = new ArrayList<>();
		if (firstInstall) {
			topLines.addAll(wrapWithHighlight(this.font, PackConfirmCopy.intro(originDisplay), originDisplay, wrapWidth, ChatFormatting.YELLOW, ChatFormatting.BOLD));
			topLines.add(blankLine());
		}
		int jars = PackConfirmCopy.selectedJarCount(updater.getSelectedTarget());
		topLines.addAll(wrapParagraph(this.font, PackConfirmCopy.unverifiedCount(unverifiedPaths.size(), jars), wrapWidth, ChatFormatting.RED));
		List<MutableComponent> bottomLines = new ArrayList<>();
		bottomLines.addAll(wrapParagraph(this.font, PackConfirmCopy.unverifiedExplain(), wrapWidth, ChatFormatting.RED));
		bottomLines.add(blankLine());
		bottomLines.addAll(wrapParagraph(this.font, PackConfirmCopy.computerRisk(), wrapWidth, ChatFormatting.RED));
		bottomLines.add(blankLine());
		bottomLines.addAll(wrapParagraph(this.font, PackConfirmCopy.sharedCommands(), wrapWidth, ChatFormatting.YELLOW));

		int topHeight = topLines.size() * LINE;
		int bottomHeight = bottomLines.size() * LINE;
		int available = Math.max(LINE, bottomY - 42);
		int listRows = preferredListRows(available - topHeight - bottomHeight - 8);
		int listHeight = listRows * UnverifiedJarList.ROW_HEIGHT;
		int needed = topHeight + 4 + listHeight + 4 + bottomHeight;

		if (needed <= available) {
			placeUnverifiedBody(42, bottomY, topLines, bottomLines, topHeight, listHeight);
			return;
		}

		listRows = Math.max(3, listRows - 1);
		listHeight = listRows * UnverifiedJarList.ROW_HEIGHT;
		needed = topHeight + 4 + listHeight + 4 + bottomHeight;
		if (needed <= available) {
			placeUnverifiedBody(42, bottomY, topLines, bottomLines, topHeight, listHeight);
			return;
		}

		List<MutableComponent> all = new ArrayList<>(topLines);
		all.add(blankLine());
		for (String path : unverifiedPaths) all.addAll(wrapParagraph(this.font, path, wrapWidth, ChatFormatting.GRAY));
		all.add(blankLine());
		all.addAll(bottomLines);
		this.addCenteredScrollBody(BODY, 42, bottomY, all);
	}

	private void placeUnverifiedBody(int topY, int bottomY, List<MutableComponent> topLines, List<MutableComponent> bottomLines, int topHeight, int listHeight) {
		this.addCenteredScrollBody(BODY, topY, topY + topHeight + 2, topLines);
		int listTop = topY + topHeight + 4;
		this.addRenderableWidget(new UnverifiedJarList(this.minecraft, this.width, this.height, panelWidth(BODY), listTop, listTop + listHeight, unverifiedPaths));
		this.addCenteredScrollBody(BODY, listTop + listHeight + 4, bottomY, bottomLines);
	}

	private int preferredListRows(int freeHeight) {
		int prefer = freeHeight >= UnverifiedJarList.ROW_HEIGHT * 4 ? 4 : 3;
		int maxBySpace = Math.max(3, freeHeight / UnverifiedJarList.ROW_HEIGHT);
		return Math.max(3, Math.min(6, Math.min(prefer, maxBySpace)));
	}

	private void replacePlaceholderWithLeftover(Button placeholder) {
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

	private void replacePlaceholderWithAck(Button placeholder) {
		this.removeWidget(placeholder);
		/*? if >=1.19.4 {*/
		int x = placeholder.getX();
		int y = placeholder.getY();
		/*?} else {*/
		/*int x = placeholder.x;
		int y = placeholder.y;
		*//*?}*/
		ackCheckbox = checkboxWidget(this.font, x, y, placeholder.getWidth(), ActionAreaLayout.BUTTON_HEIGHT, ackMessage(), acknowledged, value -> {
			if (ticksRemaining > 0) {
				acknowledged = false;
				ackCheckbox.setMessage(ackMessage());
				if (primaryButton != null) primaryButton.active = false;
				return;
			}
			acknowledged = value;
			if (primaryButton != null) primaryButton.active = acknowledged;
		});
		ackCheckbox.active = ticksRemaining <= 0;
		this.addRenderableWidget(ackCheckbox);
	}

	private MutableComponent ackMessage() {
		MutableComponent label = PackConfirmCopy.ackLabel();
		int seconds = (ticksRemaining + 19) / 20;
		if (seconds > 0) label = label.append(" (" + seconds + "s)");
		return label;
	}

	private void confirm() {
		if (finished || !acknowledged || ticksRemaining > 0) return;
		if (firstInstall) {
			if (updater.getConfirmationState() != ModpackUpdater.ConfirmationState.WAITING) {
				ScreenImpl.multiplayer();
				return;
			}
			finished = true;
			updater.setFirstInstallLocalModCleanup(!keepExistingMods);
			ScreenManager.waiting();
			updater.startConfirmedUpdate();
			return;
		}
		finished = true;
		ScreenImpl.setScreen(new PreparingScreen());
		laterContinue.run();
	}

	private void customize() {
		if (finished) return;
		Consumer<SelectionIntent> action = intent -> {
			try {
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
			ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.translatable("automodpack.browser.previewTitle"), VersionedText.translatable("automodpack.firstConnect.description"), PackConfirmCopy.catalogue(updater), PackConfirmCopy.featureNames(target.manifest())));
			return;
		}
		ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.translatable("automodpack.browser.previewTitle"), VersionedText.translatable("automodpack.update.reviewUpdate"), laterPreview.changeSet(), laterPreview.featureNames()));
	}

	private void openPatchNotes() {
		var history = firstInstall ? updater.getFirstInstallPatchNotes() : laterPreview.patchNotesHistory();
		String name = updater.getSelectedTarget().manifest().modpackName();
		ScreenImpl.setScreen(new PatchNotesHistoryScreen(this, history, name));
	}

	private void cancel() {
		if (finished) return;
		finished = true;
		if (firstInstall) {
			updater.cancelConfirmation();
			ScreenImpl.multiplayer();
			return;
		}
		ScreenImpl.setScreen(parent);
		laterCancel.run();
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
		if (ticksRemaining > 0) {
			ticksRemaining--;
			if (ackCheckbox != null) {
				ackCheckbox.setMessage(ackMessage());
				ackCheckbox.active = ticksRemaining <= 0;
			}
			if (primaryButton != null) primaryButton.active = ticksRemaining <= 0 && acknowledged;
		}
		if (firstInstall) {
			if (finished && updater.getConfirmationState() == ModpackUpdater.ConfirmationState.WAITING) {
				finished = false;
				return;
			}
			if (finished) return;
			if (updater.getConfirmationState() == ModpackUpdater.ConfirmationState.CANCELLED) {
				finished = true;
				ScreenImpl.multiplayer();
			}
		}
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		String name = updater.getSelectedTarget().manifest().modpackName().isBlank() ? "AutoModpack" : updater.getSelectedTarget().manifest().modpackName();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, name, panelWidth(BODY))).withStyle(ChatFormatting.WHITE), this.width / 2, 14, TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, PackConfirmCopy.packSummary(updater.getSelectedTarget()), panelWidth(BODY))).withStyle(ChatFormatting.GRAY), this.width / 2, 28, TextColors.WHITE);
	}

	@Override
	public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
		if (isEnterKey(keyCode)) return true;
		return super.onKeyPress(keyCode, scanCode, modifiers);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		return handleBackOnEscape(this::cancel);
	}
}
