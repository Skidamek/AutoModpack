package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.util.Util;
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

public class ChangelogScreen extends VersionedScreen {
    private final Screen parent;
    private final Path modpackDir;
    private final Changelogs changelogs;
    private static Map<String, String> formattedChanges;
    private ListEntryWidget listEntryWidget;
    private TextFieldWidget searchField;
    private ButtonWidget backButton;
    private ButtonWidget openMainPageButton;

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

        this.addDrawableChild(this.listEntryWidget);
        this.addDrawableChild(this.searchField);
        this.addDrawableChild(this.backButton);
        this.addDrawableChild(this.openMainPageButton);
        this.setInitialFocus(this.searchField);
    }

    private void initWidgets() {
        this.listEntryWidget = new ListEntryWidget(formattedChanges, this.client, this.width, this.height, 48, this.height - 50, 20); // 38

        this.searchField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 20, 200, 20,
                VersionedText.literal("")
        );
        this.searchField.setChangedListener((textField) -> updateChangelogs()); // Update the changelogs display based on the search query

        this.backButton = buttonWidget(this.width / 2 - 140, this.height - 30, 140, 20,
                VersionedText.translatable("automodpack.back"),
                button -> this.client.setScreen(this.parent)
        );

        this.openMainPageButton = buttonWidget(this.width / 2 + 20, this.height - 30, 140, 20,
                VersionedText.translatable("automodpack.changelog.openPage"),
                button -> {
                    ListEntry selectedEntry = listEntryWidget.getSelectedOrNull();

                    if (selectedEntry == null) {
                        return;
                    }

                    String mainPageUrl = selectedEntry.getMainPageUrl();
                    Util.getOperatingSystem().open(mainPageUrl);
                }
        );

    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        this.listEntryWidget.render(matrices.getContext(), mouseX, mouseY, delta);

        ListEntry selectedEntry = listEntryWidget.getSelectedOrNull();
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

        drawCenteredTextWithShadow(matrices, textRenderer, VersionedText.literal(summary), this.width / 2, 5, TextColors.WHITE);
    }

    private void updateChangelogs() {
        // If the search field is empty, reset the changelogs to the original list
        if (this.searchField.getText().isEmpty()) {
            formattedChanges = reFormatChanges();
        } else {
            // Filter the changelogs based on the search query using a case-insensitive search
            Map<String, String> filteredChangelogs = new HashMap<>();
            for (Map.Entry<String, String> changelog : reFormatChanges().entrySet()) {
                if (changelog.getKey().toLowerCase().contains(this.searchField.getText().toLowerCase())) {
                    filteredChangelogs.put(changelog.getKey(), changelog.getValue());
                }
            }
            formattedChanges = filteredChangelogs;
        }

        // remove method is only available in 1.17+
/*? if >=1.17 {*/
        this.remove(this.listEntryWidget);
        this.remove(this.backButton);
        this.remove(this.openMainPageButton);
/*?}*/

        this.listEntryWidget = new ListEntryWidget(formattedChanges, this.client, this.width, this.height, 48, this.height - 50, 20); // 38

        this.addDrawableChild(this.listEntryWidget);
        this.addDrawableChild(this.searchField);
        this.addDrawableChild(this.backButton);
        this.addDrawableChild(this.openMainPageButton);
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
        assert this.client != null;
        this.client.setScreen(this.parent);
        return false;
    }
}


