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

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.TextColors;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
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
	private static final int PANEL = ActionAreaLayout.FOOTER_RAIL;
	private static final int LINE = 11;
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
		boolean customize = PackConfirmCopy.hasOptionalGroups(updater.getSelectedTarget().manifest());
		boolean notes = firstInstall ? GenerationPatchNoteHistory.containsNotes(updater.getFirstInstallPatchNotes())
				: laterPreview != null && GenerationPatchNoteHistory.containsNotes(laterPreview.patchNotesHistory());
		boolean showAck = ticksRemaining <= 0;

		List<ActionRow> rows = new ArrayList<>();
		if (notes) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.patchNotes.all"), button -> openPatchNotes())));
		if (leftover) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.literal(" "), button -> {})));
		if (customize) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.translatable("automodpack.firstConnect.customize"), button -> customize())));
		if (showAck) rows.add(actionRow(ActionAreaLayout.RowKind.AUXILIARY, optionalAction(VersionedText.literal(" "), button -> {})));
		Component cancelLabel = VersionedText.translatable(firstInstall ? "automodpack.firstConnect.cancel" : "automodpack.cancel");
		Component primaryLabel = VersionedText.translatable(firstInstall ? "automodpack.firstConnect.download" : "automodpack.update.apply");
		rows.add(actionRow(ActionAreaLayout.RowKind.FOOTER,
				secondaryAction(cancelLabel, button -> cancel()),
				optionalAction(VersionedText.translatable("automodpack.browser.reviewFiles"), button -> openFiles()),
				primaryAction(primaryLabel, button -> confirm())));
		ActionRow[] rowArray = rows.toArray(ActionRow[]::new);
		List<Button> buttons = this.addActionArea(PANEL, this.height - 28, rowArray);
		int buttonIndex = 0;
		if (notes) buttonIndex++;
		if (leftover) {
			replacePlaceholderWithLeftover(buttons.get(buttonIndex));
			buttonIndex++;
		}
		if (customize) buttonIndex++;
		if (showAck) {
			replacePlaceholderWithAck(buttons.get(buttonIndex));
			buttonIndex++;
		}
		cancelButton = buttons.get(buttonIndex);
		primaryButton = buttons.get(buttonIndex + 2);
		primaryButton.active = showAck && acknowledged;
		this.setInitialFocus(cancelButton);
		addOriginHitbox();

		int bottomY = actionAreaTop(PANEL, this.height - 28, rowArray) - 4;
		layoutBody(bottomY);
	}

	private void addOriginHitbox() {
		String label = truncateToWidth(this.font, originFull, panelWidth(PANEL));
		int width = Math.max(1, this.font.width(label));
		if (label.equals(originFull)) return;
		Button hit = buttonWidget(this.width / 2 - width / 2, 27, width, 12, VersionedText.literal(""), button -> {});
		this.addRenderableWidget(hit);
		setTooltip(hit, VersionedText.literal(originFull));
	}

	private void layoutBody(int bottomY) {
		int wrapWidth = Math.max(1, panelWidth(PANEL) - 20);
		List<String> topLines = new ArrayList<>();
		if (firstInstall) {
			topLines.addAll(wrapToWidth(this.font, PackConfirmCopy.intro(originFull), wrapWidth));
			topLines.add("");
		}
		topLines.add(PackConfirmCopy.selectedSummary(updater.getSelectedTarget()));
		topLines.add("");
		int jars = PackConfirmCopy.selectedJarCount(updater.getSelectedTarget());
		topLines.add(PackConfirmCopy.unverifiedCount(unverifiedPaths.size(), jars));
		List<String> bottomLines = new ArrayList<>();
		bottomLines.addAll(wrapToWidth(this.font, PackConfirmCopy.unverifiedExplain(), wrapWidth));
		bottomLines.add("");
		bottomLines.addAll(wrapToWidth(this.font, PackConfirmCopy.computerRisk(), wrapWidth));
		bottomLines.add("");
		bottomLines.addAll(wrapToWidth(this.font, PackConfirmCopy.sharedCommands(), wrapWidth));

		int topHeight = topLines.size() * LINE;
		int bottomHeight = bottomLines.size() * LINE;
		int available = Math.max(LINE, bottomY - 42);
		int listRows = preferredListRows(available - topHeight - bottomHeight - 8);
		int listHeight = listRows * UnverifiedJarList.ROW_HEIGHT;
		int needed = topHeight + 4 + listHeight + 4 + bottomHeight;

		if (needed <= available) {
			int y = 42;
			this.addScrollBody(PANEL, y, y + topHeight + 2, topLines);
			y += topHeight + 4;
			this.addRenderableWidget(new UnverifiedJarList(this.minecraft, this.width, this.height, panelWidth(PANEL), y, y + listHeight, unverifiedPaths));
			y += listHeight + 4;
			this.addScrollBody(PANEL, y, bottomY, bottomLines);
			return;
		}

		listRows = Math.max(3, listRows - 1);
		listHeight = listRows * UnverifiedJarList.ROW_HEIGHT;
		needed = topHeight + 4 + listHeight + 4 + bottomHeight;
		if (needed <= available) {
			int y = 42;
			this.addScrollBody(PANEL, y, y + topHeight + 2, topLines);
			y += topHeight + 4;
			this.addRenderableWidget(new UnverifiedJarList(this.minecraft, this.width, this.height, panelWidth(PANEL), y, y + listHeight, unverifiedPaths));
			y += listHeight + 4;
			this.addScrollBody(PANEL, y, bottomY, bottomLines);
			return;
		}

		List<String> all = new ArrayList<>(topLines);
		all.addAll(unverifiedPaths);
		all.add("");
		all.addAll(bottomLines);
		this.addScrollBody(PANEL, 42, bottomY, all);
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
		ackCheckbox = checkboxWidget(this.font, x, y, placeholder.getWidth(), ActionAreaLayout.BUTTON_HEIGHT, PackConfirmCopy.ackLabel(), acknowledged, value -> {
			acknowledged = value;
			if (primaryButton != null) primaryButton.active = acknowledged && ticksRemaining <= 0;
		});
		this.addRenderableWidget(ackCheckbox);
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
			ScreenImpl.setScreen(new ChangeBrowserScreen(this, VersionedText.translatable("automodpack.browser.previewTitle"), VersionedText.translatable("automodpack.firstConnect.description"), PackConfirmCopy.catalogue(target), PackConfirmCopy.featureNames(target.manifest())));
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
			if (ticksRemaining == 0) rebuild();
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
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, name, panelWidth(PANEL))).withStyle(ChatFormatting.WHITE), this.width / 2, 14, TextColors.WHITE);
		String originLabel = truncateToWidth(this.font, originFull, panelWidth(PANEL));
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(originLabel).withStyle(ChatFormatting.GRAY, ChatFormatting.BOLD), this.width / 2, 29, TextColors.WHITE);
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
