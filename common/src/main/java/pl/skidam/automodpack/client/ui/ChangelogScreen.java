package pl.skidam.automodpack.client.ui;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.EntryListWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.ui.widget.ScrollingListWidget;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.utils.ModpackContentTools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChangelogScreen extends Screen {
    private static List<String> changelogs;
    private TextFieldWidget searchField;
    private final Screen parent;
    private final File modpackDir;
    private ChangelogsList changelogsList;

    public ChangelogScreen(Screen parent, File modpackDir) {
        super(TextHelper.literal("ChangelogScreen"));
        this.parent = parent;
        this.modpackDir = modpackDir;
    }

    @Override
    protected void init() {
        super.init();
        assert this.client != null;

        this.client.keyboard.setRepeatEvents(true);

        // Retrieve the changelogs
        changelogs = getChangelogs();

        // Initialize the changelogs list
        this.changelogsList = new ChangelogsList(client, this.width, this.height, 48, this.height - 64, 20);
        this.addDrawableChild(this.changelogsList);

        // Initialize the search field
        this.searchField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 20, 200, 20, TextHelper.literal(""));
        this.searchField.setChangedListener((textField) -> updateChangelogs()); // Update the changelogs display based on the search query
        this.addDrawableChild(this.searchField);

        // Add the back button
        this.addDrawableChild(new ButtonWidget(5, this.height - 20, 72, 20, TextHelper.translatable("gui.automodpack.screen.changelog.button.back"), button -> this.client.setScreen(this.parent)));

        this.setInitialFocus(this.searchField);
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        super.render(matrices, mouseX, mouseY, delta);

        // Draw summary of added/removed mods
        drawSummaryOfChanges(matrices);

        // Update and display the changelogs based on the search query
        this.changelogsList = new ChangelogsList(client, this.width, this.height, 48, this.height - 64, 20);
        this.changelogsList.render(matrices, mouseX, mouseY, delta);
    }

    private void drawSummaryOfChanges(MatrixStack matrices) {

        File modpackContentFile = ModpackContentTools.getModpackContentFile(modpackDir);
        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);

        int modsAdded = 0;
        int modsRemoved = 0;
        if (modpackContent == null) return;
        for (Map.Entry<String, Boolean> changelog : ModpackUpdater.changelogList.entrySet()) {
            String fileType = ModpackContentTools.getFileType(changelog.getKey(), modpackContent);
            if (fileType.equals("mod")) {
                if (changelog.getValue()) {
                    modsAdded++;
                } else {
                    modsRemoved++;
                }
            }
        }

        if (modsAdded == 0 && modsRemoved == 0) return;

        String summary = "Mods + " + modsAdded + " | - " + modsRemoved;

        drawCenteredText(matrices, textRenderer, TextHelper.literal(summary), this.width / 2, 5, 16777215);
    }

    private void updateChangelogs() {
        // If the search field is empty, reset the changelogs to the original list
        if (this.searchField.getText().isEmpty()) {
            changelogs = getChangelogs();
        } else {
            // Filter the changelogs based on the search query using a case-insensitive search
            List<String> filteredChangelogs = new ArrayList<>();
            for (String changelog : getChangelogs()) {
                if (changelog.toLowerCase().contains(this.searchField.getText().toLowerCase())) {
                    filteredChangelogs.add(changelog);
                }
            }
            changelogs = filteredChangelogs;
        }
    }

    private List<String> getChangelogs() {
        List<String> changelogs = new ArrayList<>();

        for (Map.Entry<String, Boolean> changelog : ModpackUpdater.changelogList.entrySet()) {
            if (changelog.getValue()) {
                changelogs.add("+ " + changelog.getKey());
            } else {
                changelogs.add("- " + changelog.getKey());
            }
        }

        return changelogs;
    }

    @Override
    public boolean shouldCloseOnEsc() {
        assert this.client != null;
        this.client.setScreen(this.parent);
        return false;
    }

    public static class ChangelogsList extends ScrollingListWidget<ChangelogsList.Entry> {
        ChangelogsList(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
            super(client, width, height, top, bottom, itemHeight);

            for (String changelog : ChangelogScreen.changelogs) {
                int color = 16777215;
                if (changelog.startsWith("+")) {
                    color = 3706428;
                } else if (changelog.startsWith("-")) {
                    color = 14687790;
                }

                this.children().add(new Entry(changelog, color));
            }
        }

        public class Entry extends EntryListWidget.Entry<ChangelogsList.Entry> {
            private final String text;
            private final int color;
            public Entry(String text, int color) {
                this.text = text;
                this.color = color;
            }

            @Override
            public void render(MatrixStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
                drawStringWithShadow(matrices, ChangelogsList.this.client.textRenderer, text, x + 10, y, color);
            }
        }
    }
}


