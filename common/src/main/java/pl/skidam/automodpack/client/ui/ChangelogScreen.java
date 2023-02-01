package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.client.util.math.MatrixStack;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.utils.ModpackContentTools;

import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class ChangelogScreen extends Screen {
    private List<String> changelogs;
    private TextFieldWidget searchField;
    private final Screen parent;
    private final File modpackDir;
//    private int scrollLevel = 0;
//    private static final int maxScroll = 1;
    public ChangelogScreen(Screen parent, File modpackDir) {
        super(TextHelper.literal("ChangelogScreen"));
        this.parent = parent;
        this.modpackDir = modpackDir;
    }

    @Override
    protected void init() {
        super.init();

        // Retrieve the changelogs from a file or remote server
        this.changelogs = getChangelogs();

        // Initialize the search field
        this.searchField = new TextFieldWidget(this.textRenderer, this.width / 2 - 100, 20, 200, 20, TextHelper.literal(""));
        this.searchField.setChangedListener((textField) -> {
            // Update the changelogs display based on the search query
            updateChangelogs();
        });
        this.addDrawableChild(this.searchField);
        this.setInitialFocus(this.searchField);

        // Add the back button
        this.addDrawableChild(ButtonWidget.builder(TextHelper.translatable("gui.automodpack.screen.changelog.button.back"), button -> {
            assert this.client != null;
            this.client.setScreen(this.parent);
        }).position(5, this.height - 20 - 5).size(50, 20).build());

    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        super.render(matrices, mouseX, mouseY, delta);

        // Draw summary of added/removed mods
        drawSummaryOfChanges(matrices);

        // Display the changelogs
        drawChangelogs(matrices);


//        //Draw scrollbar.
//        RenderSystem.setShader(GameRenderer::getPositionTexShader);
//        if (isScrollBarHovered(mouseX, mouseY)) {
//            this.drawTexture(matrices, (this.width / 2) -20, (int) ((this.height / 2) + 43 + ((double) scrollLevel / maxScroll * 138)), 33, 0, 12, 15);
//        } else {
//            this.drawTexture(matrices, (this.width / 2) -20, (int) ((this.height / 2) + 43 + ((double) scrollLevel / maxScroll * 138)), 21, 0, 12, 15);
//        }
    }

//    public boolean isScrollBarHovered(int mouseX, int mouseY) {
//        return mouseX >= (this.width) / 2 - 20 && mouseX <= this.width / 2 - 8 && mouseY >= (this.height) / 2 + 43 && mouseY <= this.height / 2 + 196;
//    }

//    @Override
//    public boolean mouseScrolled(double mouseX, double mouseY, double amount) {
//        int oldScroll = scrollLevel;
//        scrollLevel+=amount;
//        if (scrollLevel < maxScroll) {
//            scrollLevel = maxScroll;
//        } else if (scrollLevel > 0) {scrollLevel = 0;}
//        if (oldScroll != scrollLevel) {
//            updateChangelogs();
//            //May need to set visibility of all entries, if something breaks.
//            return true;
//        }
//        return false;
//    }


    private void drawSummaryOfChanges(MatrixStack matrices) {

        File modpackContentFile = ModpackContentTools.getModpackContentFile(modpackDir);
        Config.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(modpackContentFile);

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

    private void drawChangelogs(MatrixStack matrices) {

        float scale = 1.0F;

        matrices.push();
        matrices.scale(scale, scale, scale);

        int y = 50;
        for (String changelog : this.changelogs) {
            int color = 16777215;
            if (changelog.startsWith("+")) {
                color = 3706428;
            } else if (changelog.startsWith("-")) {
                color = 14687790;
            }
            drawCenteredText(matrices, this.textRenderer, TextHelper.literal(changelog), (int) (this.width / 2 * scale), y, color);
            y += 10;
        }


        matrices.pop();
    }

    private void updateChangelogs() {
        // If the search field is empty, reset the changelogs to the original list
        if (this.searchField.getText().isEmpty()) {
            this.changelogs = getChangelogs();
        } else {
            // Filter the changelogs based on the search query using a case-insensitive search
            List<String> filteredChangelogs = new ArrayList<>();
            for (String changelog : getChangelogs()) {
                if (changelog.toLowerCase().contains(this.searchField.getText().toLowerCase())) {
                    filteredChangelogs.add(changelog);
                }
            }
            this.changelogs = filteredChangelogs;
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
    public boolean shouldCloseOnEsc() { return false; }
}


