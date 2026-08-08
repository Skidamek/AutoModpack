package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.util.Util;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.ListEntry;
import pl.skidam.automodpack.client.ui.widget.ListEntryWidget;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;

public final class UpdatePreviewScreen extends VersionedScreen {
	private static final int PANEL_WIDTH = 600;
	private static final int LIST_TOP = 68;
	private final Screen parent;
	private final UpdatePreview preview;
	private final String modpackName;
	private final boolean removal;
	private final boolean returnToSelection;
	private final Runnable continueAction;
	private final Runnable cancelAction;
	private final Map<UpdatePlan.FileKey, List<String>> mainPageUrls;
	private ListEntryWidget changesList;
	private Button openMainPageButton;
	private boolean finished;

	public UpdatePreviewScreen(Screen parent, UpdatePreview preview, String modpackName, boolean removal, boolean returnToSelection, Runnable continueAction,
			Runnable cancelAction, Map<UpdatePlan.FileKey, List<String>> mainPageUrls) {
		super(VersionedText.literal(removal ? "ModpackRemovalScreen" : "UpdatePreviewScreen"));
		this.parent = parent;
		this.preview = preview;
		this.modpackName = modpackName == null ? "" : modpackName;
		this.removal = removal;
		this.returnToSelection = returnToSelection;
		this.continueAction = continueAction;
		this.cancelAction = cancelAction;
		this.mainPageUrls = Map.copyOf(mainPageUrls);
	}

	@Override
	protected void init() {
		super.init();
		int actionWidth = actionButtonWidth(310, 3);
		this.changesList = new ListEntryWidget(rows(), this.minecraft, this.width, this.height, LIST_TOP, listBottom(), 18);
		this.addRenderableWidget(this.changesList);

		boolean hasPatchNotes = GenerationPatchNoteHistory.containsNotes(preview.patchNotesHistory());
		if (hasPatchNotes) this.addRenderableWidget(buttonWidget(this.width / 2 - 75, this.height - 52, 150, 20, VersionedText.literal("Patch notes..."), button -> openPatchNotes()));
		this.openMainPageButton = buttonWidget(actionButtonX(310, 3, 1), this.height - 28, actionWidth, 20,
				VersionedText.literal("Project page"), button -> openSelectedPage());
		this.openMainPageButton.active = false;
		this.addRenderableWidget(this.openMainPageButton);
		this.addRenderableWidget(buttonWidget(actionButtonX(310, 3, 0), this.height - 28, actionWidth, 20,
				returnToSelection ? VersionedText.translatable("automodpack.back") : VersionedText.translatable("automodpack.cancel"), button -> cancel()));
		this.addRenderableWidget(buttonWidget(actionButtonX(310, 3, 2), this.height - 28, actionWidth, 20,
				VersionedText.literal(removal ? "Remove" : "Update"), button -> continueUpdate()));
	}

	private int listBottom() {
		return Math.max(LIST_TOP + 18, this.height - 86);
	}

	private List<ListEntryWidget.Row> rows() {
		List<ListEntryWidget.Row> rows = new ArrayList<>();
		for (UpdatePreview.Entry entry : preview.entries()) {
			UpdatePlan.FileKey file = new UpdatePlan.FileKey(entry.root(), entry.relativePath());
			String text = symbol(entry.kind()) + UiFormat.changePath(file) + "  (" + UiFormat.formatSize(entry.size()) + ")";
			ChatFormatting color = switch (entry.kind()) {
				case PRESERVED_CAS, PRESERVED_CHANGED, PRESERVED_UNAVAILABLE, PRESERVED_OUTSIDE -> ChatFormatting.GRAY;
				case UNSAFE -> ChatFormatting.RED;
				default -> ChatFormatting.WHITE;
			};
			rows.add(new ListEntryWidget.Row(VersionedText.literal(text).withStyle(color), firstUrl(mainPageUrls.get(file))));
		}
		if (rows.isEmpty()) rows.add(new ListEntryWidget.Row(VersionedText.literal("No file changes are required.").withStyle(ChatFormatting.GRAY), null));
		return rows;
	}

	private static String symbol(UpdatePreview.Kind kind) {
		return switch (kind) {
			case ADDED, RESTORED_BASELINE -> "+ ";
			case CHANGED -> "~ ";
			case REMOVED -> "- ";
			case UNSAFE -> "! ";
			default -> "  ";
		};
	}

	private static String firstUrl(List<String> urls) {
		return urls == null || urls.isEmpty() ? null : urls.get(0);
	}

	private void continueUpdate() {
		if (finished) return;
		finished = true;
		ScreenImpl.setScreen(new PreparingScreen());
		continueAction.run();
	}

	private void cancel() {
		if (finished) return;
		finished = true;
		ScreenImpl.setScreen(parent);
		cancelAction.run();
	}

	private void openSelectedPage() {
		ListEntry selected = changesList.getSelected();
		if (selected != null && selected.getMainPageUrl() != null) Util.getPlatform().openUri(selected.getMainPageUrl());
	}

	private void openPatchNotes() {
		ScreenImpl.setScreen(new PatchNotesHistoryScreen(this, preview.patchNotesHistory(), modpackName));
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		/*? if <26.1 {*/
		/*this.changesList.render(matrices.getContext(), mouseX, mouseY, delta);
		*//*?}*/
		ListEntry selected = changesList.getSelected();
		this.openMainPageButton.active = selected != null && selected.getMainPageUrl() != null;

		String title = removal
				? (modpackName.isBlank() ? "Remove modpack" : "Remove " + modpackName)
				: (modpackName.isBlank() ? "Review update" : "Update " + modpackName);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(truncateToWidth(this.font, title, panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.BOLD), this.width / 2, 14,
				TextColors.WHITE);
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(removal ? "Review the files that will be removed." : "Review the changes before updating.").withStyle(ChatFormatting.GRAY),
				this.width / 2, 29, TextColors.WHITE);
		String patchNotes = preview.patchNotes().isBlank() ? "No patch notes published." : preview.patchNotes();
		drawCenteredTextWithShadow(matrices, this.font,
				VersionedText.literal(truncateToWidth(this.font, "Patch notes: " + patchNotes, panelWidth(PANEL_WIDTH))).withStyle(ChatFormatting.GRAY), this.width / 2, 44, TextColors.WHITE);

		UpdatePreview.GroupConsequences groups = preview.groupConsequences();
		boolean staleSelection = !groups.staleGroups().isEmpty();
		String selection = staleSelection ? "Some previously selected content is no longer available." : groups.resolvedGroups().size() + " content groups selected";
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(selection).withStyle(staleSelection ? ChatFormatting.RED : ChatFormatting.GRAY), this.width / 2, 56,
				TextColors.WHITE);

		long updated = preview.entries().stream().filter(UpdatePreviewScreen::isUpdated).count();
		long removed = preview.entries().stream().filter(entry -> entry.kind() == UpdatePreview.Kind.REMOVED).count();
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(updated + " updated  " + removed + " removed").withStyle(ChatFormatting.GRAY), this.width / 2,
				this.height - 80, TextColors.WHITE);
		String restart = preview.restartReasons().isEmpty() ? "No restart expected" : "Restart required";
		drawCenteredTextWithShadow(matrices, this.font, VersionedText.literal(restart).withStyle(ChatFormatting.GRAY), this.width / 2, this.height - 68, TextColors.WHITE);
	}

	private static boolean isUpdated(UpdatePreview.Entry entry) {
		return entry.kind() == UpdatePreview.Kind.ADDED || entry.kind() == UpdatePreview.Kind.CHANGED || entry.kind() == UpdatePreview.Kind.RESTORED_BASELINE;
	}

	@Override
	public boolean shouldCloseOnEsc() {
		cancel();
		return false;
	}
}
