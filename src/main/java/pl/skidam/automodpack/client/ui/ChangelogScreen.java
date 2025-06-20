package pl.skidam.automodpack.client.ui;

import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.widget.ListEntry;
import pl.skidam.automodpack.client.ui.widget.ListEntryWidget;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Optional;
import net.minecraft.Util;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;

public class ChangelogScreen extends VersionedScreen {
    private final Screen parent;
    private final Path modpackDir;
    private final Changelogs changelogs;
    private static Map<String, String> formattedChanges;
    private ListEntryWidget listEntryWidget;
    private EditBox searchField;
    private Button backButton;
    private Button openMainPageButton;

    public ChangelogScreen(Screen parent, Path modpackDir, Changelogs changelogs) {
        super(VersionedText.literal("ChangelogScreen"));
        this.parent = parent;
        this.modpackDir = modpackDir;
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
        this.listEntryWidget = new ListEntryWidget(formattedChanges, this.minecraft, this.width, this.height, 48, this.height - 50, 20); // 38

        this.searchField = new EditBox(this.font, this.width / 2 - 100, 20, 200, 20,
                VersionedText.literal("")
        );
        this.searchField.setResponder((textField) -> updateChangelogs()); // Update the changelogs display based on the search query

        this.backButton = buttonWidget(this.width / 2 - 140, this.height - 30, 140, 20,
                VersionedText.translatable("automodpack.back"),
                button -> this.minecraft.setScreen(this.parent)
        );

        this.openMainPageButton = buttonWidget(this.width / 2 + 20, this.height - 30, 140, 20,
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
        this.listEntryWidget.render(matrices.getContext(), mouseX, mouseY, delta);

        ListEntry selectedEntry = listEntryWidget.getSelected();
        if (selectedEntry != null) {
            this.openMainPageButton.active = selectedEntry.getMainPageUrl() != null;
        } else {
            this.openMainPageButton.active = false;
        }

        // Draw summary of added/removed mods
        drawSummaryOfChanges(matrices);
    }

    private Jsons.ModpackContentFields modpackContent = null;

    private void drawSummaryOfChanges(VersionedMatrices matrices) {

        if (modpackContent == null) {
            var optionalModpackContentFile = ModpackContentTools.getModpackContentFile(modpackDir);
            if (optionalModpackContentFile.isEmpty()) return;
            modpackContent = ConfigTools.loadModpackContent(optionalModpackContentFile.get());
        }

        if (modpackContent == null) return;
        int filesAdded = changelogs.changesAddedList.size();
        int filesRemoved = changelogs.changesDeletedList.size();

        String summary = "+ " + filesAdded + " | - " + filesRemoved;

        drawCenteredTextWithShadow(matrices, font, VersionedText.literal(summary), this.width / 2, 5, TextColors.WHITE);
    }

    private void updateChangelogs() {
        // If the search field is empty, reset the changelogs to the original list
        if (this.searchField.getValue().isEmpty()) {
            formattedChanges = reFormatChanges();
        } else {
            // Filter the changelogs based on the search query using a case-insensitive search
            Map<String, String> filteredChangelogs = new HashMap<>();
            for (Map.Entry<String, String> changelog : reFormatChanges().entrySet()) {
                if (changelog.getKey().toLowerCase().contains(this.searchField.getValue().toLowerCase())) {
                    filteredChangelogs.put(changelog.getKey(), changelog.getValue());
                }
            }
            formattedChanges = filteredChangelogs;
        }

        // remove method is only available in 1.17+
/*? if >=1.17 {*/
        this.removeWidget(this.listEntryWidget);
        this.removeWidget(this.backButton);
        this.removeWidget(this.openMainPageButton);
/*?}*/

        this.listEntryWidget = new ListEntryWidget(formattedChanges, this.minecraft, this.width, this.height, 48, this.height - 50, 20); // 38

        this.addRenderableWidget(this.listEntryWidget);
        this.addRenderableWidget(this.searchField);
        this.addRenderableWidget(this.backButton);
        this.addRenderableWidget(this.openMainPageButton);
    }

    private Map<String, String> reFormatChanges() {
        Map<String, String> reFormattedChanges = new HashMap<>();

        for (var changelog : changelogs.changesAddedList.entrySet()) {
            String modPageUrl = null;
            if (changelog.getValue() != null && !changelog.getValue().isEmpty()) modPageUrl = changelog.getValue().get(0);
            reFormattedChanges.put("+ " + changelog.getKey(), modPageUrl);
        }

        for (var changelog : changelogs.changesDeletedList.entrySet()) {
            String modPageUrl = null;
            if (changelog.getValue() != null && !changelog.getValue().isEmpty()) modPageUrl = changelog.getValue().get(0);
            reFormattedChanges.put("- " + changelog.getKey(), modPageUrl);
        }

        return reFormattedChanges;
    }


    @Override
    public boolean shouldCloseOnEsc() {
        assert this.minecraft != null;
        this.minecraft.setScreen(this.parent);
        return false;
    }
}


