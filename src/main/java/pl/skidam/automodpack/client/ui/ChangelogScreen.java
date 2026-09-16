package pl.skidam.automodpack.client.ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.widget.ListEntry;
import pl.skidam.automodpack.client.ui.widget.ListEntryWidget;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_loader_core.client.Changelogs;

public class ChangelogScreen extends VersionedScreen {

	private final Screen parent;
	private final Changelogs changelogs;
	private List<ListEntryWidget.Row> formattedChanges;
	private ListEntryWidget listEntryWidget;
	private EditBox searchField;
	private Button backButton;
	private Button patchNotesButton;
	private Button openMainPageButton;

	public ChangelogScreen(Screen parent, Changelogs changelogs) {
		super(VersionedText.translatable("automodpack.changelog.title"));
		this.parent = parent;
		this.changelogs = changelogs;

		if (AudioManager.isMusicPlaying()) {
			AudioManager.stopMusic();
		}
	}

	@Override
	protected void init() {
		super.init();

		formattedChanges = reFormatChanges();

		initWidgets();

		this.addRenderableWidget(this.listEntryWidget);
		this.addRenderableWidget(this.searchField);
		this.addRenderableWidget(this.backButton);
		this.addRenderableWidget(this.patchNotesButton);
		this.addRenderableWidget(this.openMainPageButton);
		this.setInitialFocus(this.searchField);
	}

	private void initWidgets() {
		this.listEntryWidget = new ListEntryWidget(
			formattedChanges,
			this.minecraft,
			this.width,
			this.height,
			68,
			this.height - 50,
			20
		);

		this.searchField = new EditBox(
			this.font,
			this.width / 2 - 100,
			20,
			200,
			20,
			VersionedText.literal("")
		);
		this.searchField.setResponder(textField -> updateChangelogs());

		this.backButton = buttonWidget(
			actionButtonX(310, 3, 0),
			this.height - 30,
			actionButtonWidth(310, 3),
			20,
			VersionedText.translatable("automodpack.back"),
			button -> ScreenImpl.setScreen(this.parent)
		);

		this.patchNotesButton = buttonWidget(
			actionButtonX(310, 3, 1),
			this.height - 30,
			actionButtonWidth(310, 3),
			20,
			VersionedText.translatable("automodpack.patchNotes.all"),
			button -> ScreenImpl.setScreen(new PatchNotesHistoryScreen(this, changelogs.patchNotesHistory(), ""))
		);
		this.patchNotesButton.active = GenerationPatchNoteHistory.containsNotes(changelogs.patchNotesHistory());

		this.openMainPageButton = buttonWidget(
			actionButtonX(310, 3, 2),
			this.height - 30,
			actionButtonWidth(310, 3),
			20,
			VersionedText.translatable("automodpack.changelog.openPage"),
			button -> {
				ListEntry selectedEntry = listEntryWidget.getSelected();

				if (selectedEntry == null) {
					return;
				}

				String mainPageUrl = selectedEntry.getMainPageUrl();
				Util.getPlatform().openUri(mainPageUrl);
			}
		);
	}

	@Override
	public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
		/*? if <26.1 {*/
		/*this.listEntryWidget.render(
			matrices.getContext(),
			mouseX,
			mouseY,
			delta
		);
		*//*?}*/

		ListEntry selectedEntry = listEntryWidget.getSelected();
		if (selectedEntry != null) {
			this.openMainPageButton.active =
				selectedEntry.getMainPageUrl() != null;
		} else {
			this.openMainPageButton.active = false;
		}

		// Draw summary of added/removed mods
		drawSummaryOfChanges(matrices);
	}

	private void drawSummaryOfChanges(VersionedMatrices matrices) {
		drawCenteredTextWithShadow(matrices, font,
				VersionedText.translatable("automodpack.summary.files", changelogs.changedFiles().size(), changelogs.removedFiles().size()).withStyle(ChatFormatting.GRAY), this.width / 2, 4, TextColors.WHITE);
		String notes = changelogs.latestPatchNotes();
		List<String> noteLines = notes.isBlank() ? List.of(VersionedText.translatable("automodpack.patchNotes.none").getString()) : wrapToWidth(this.font, notes, this.width - 20, 2);
		drawCenteredTextWithShadow(matrices, font, VersionedText.translatable("automodpack.patchNotes.latest").withStyle(ChatFormatting.YELLOW), this.width / 2, 42, TextColors.WHITE);
		for (int index = 0; index < noteLines.size(); index++)
			drawCenteredTextWithShadow(matrices, font, VersionedText.literal(noteLines.get(index)).withStyle(ChatFormatting.WHITE), this.width / 2, 54 + index * 12, TextColors.WHITE);
	}

	private void updateChangelogs() {
		if (this.searchField.getValue().isEmpty()) {
			formattedChanges = reFormatChanges();
		} else {
			List<ListEntryWidget.Row> filteredChangelogs = new ArrayList<>();
			for (ListEntryWidget.Row changelog : reFormatChanges())
				if (changelog.text().getString().toLowerCase(Locale.ROOT).contains(this.searchField.getValue().toLowerCase(Locale.ROOT))) filteredChangelogs.add(changelog);
			formattedChanges = filteredChangelogs;
		}

		this.removeWidget(this.listEntryWidget);
		this.listEntryWidget = new ListEntryWidget(formattedChanges, this.minecraft, this.width, this.height, 68, this.height - 50, 20);
		this.addRenderableWidget(this.listEntryWidget);
	}

	private List<ListEntryWidget.Row> reFormatChanges() {
		List<ListEntryWidget.Row> reFormattedChanges = new ArrayList<>();

		for (var changelog : changelogs.changedFiles().values()) {
			reFormattedChanges.add(new ListEntryWidget.Row(VersionedText.literal("+ " + UiFormat.changePath(changelog.file())).withStyle(ChatFormatting.GREEN), firstUrl(changelog.mainPageUrls())));
		}

		for (var changelog : changelogs.removedFiles().values()) {
			reFormattedChanges.add(new ListEntryWidget.Row(VersionedText.literal("- " + UiFormat.changePath(changelog.file())).withStyle(ChatFormatting.RED), firstUrl(changelog.mainPageUrls())));
		}

		return reFormattedChanges;
	}

	private static String firstUrl(List<String> urls) {
		return urls == null || urls.isEmpty() ? null : urls.get(0);
	}

	@Override
	public boolean shouldCloseOnEsc() {
		assert this.minecraft != null;
		ScreenImpl.setScreen(this.parent);
		return false;
	}
}
