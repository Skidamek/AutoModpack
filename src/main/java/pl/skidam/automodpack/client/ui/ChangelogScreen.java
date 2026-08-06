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
import pl.skidam.automodpack_loader_core.client.Changelogs;

public class ChangelogScreen extends VersionedScreen {

	private final Screen parent;
	private final Changelogs changelogs;
	private List<ListEntryWidget.Row> formattedChanges;
	private ListEntryWidget listEntryWidget;
	private EditBox searchField;
	private Button backButton;
	private Button openMainPageButton;

	public ChangelogScreen(Screen parent, Changelogs changelogs) {
		super(VersionedText.literal("ChangelogScreen"));
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
		this.addRenderableWidget(this.openMainPageButton);
		this.setInitialFocus(this.searchField);
	}

	private void initWidgets() {
		this.listEntryWidget = new ListEntryWidget(
			formattedChanges,
			this.minecraft,
			this.width,
			this.height,
			48,
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

		this.openMainPageButton = buttonWidget(
			actionButtonX(310, 3, 1),
			this.height - 30,
			actionButtonWidth(310, 3),
			20,
			VersionedText.literal("Project page"),
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
		int filesUpdated = changelogs.updatedFiles().size();
		int filesRemoved = changelogs.removedFiles().size();

		String summary = "+ " + filesUpdated + " | - " + filesRemoved;

		drawCenteredTextWithShadow(
			matrices,
			font,
			VersionedText.literal(summary),
			this.width / 2,
			5,
			TextColors.WHITE
		);
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
		this.listEntryWidget = new ListEntryWidget(formattedChanges, this.minecraft, this.width, this.height, 48, this.height - 50, 20);
		this.addRenderableWidget(this.listEntryWidget);
	}

	private List<ListEntryWidget.Row> reFormatChanges() {
		List<ListEntryWidget.Row> reFormattedChanges = new ArrayList<>();

		for (var changelog : changelogs.updatedFiles().entrySet()) {
			reFormattedChanges.add(new ListEntryWidget.Row(VersionedText.literal("+ " + UiFormat.changePath(changelog.getKey())).withStyle(ChatFormatting.GREEN), firstUrl(changelog.getValue())));
		}

		for (var changelog : changelogs.removedFiles().entrySet()) {
			reFormattedChanges.add(new ListEntryWidget.Row(VersionedText.literal("- " + UiFormat.changePath(changelog.getKey())).withStyle(ChatFormatting.RED), firstUrl(changelog.getValue())));
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
