package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import pl.skidam.automodpack.StaticVariables;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.ModpackUtils;
import pl.skidam.automodpack.client.ui.widget.MenuWidgetWidget;
import pl.skidam.automodpack.client.ui.widget.ModpackListEntry;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.modpack.Modpack;
import pl.skidam.automodpack.utils.CustomFileUtils;

import java.io.File;
import java.nio.file.Path;

import static pl.skidam.automodpack.StaticVariables.clientConfig;
import static pl.skidam.automodpack.StaticVariables.clientConfigFile;

public class MenuScreen extends Screen {
    private MenuWidgetWidget MenuWidgetWidget;
    private ButtonWidget selectButton;
    private ButtonWidget redownloadButton;
    private ButtonWidget removeButton;
    private ButtonWidget backButton;


    public MenuScreen() {
        super(TextHelper.literal("MenuScreen"));
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(MenuWidgetWidget);

        this.addDrawableChild(selectButton);
        this.addDrawableChild(redownloadButton);
        this.addDrawableChild(removeButton);
        this.addDrawableChild(backButton);
    }

    public void initWidgets() {
        this.MenuWidgetWidget = new MenuWidgetWidget(this.client, this.width, this.height, 48, this.height - 50, 20);

        int numButtons = 4;
        int buttonWidth = this.width / (numButtons + 1); // Add 1 to account for the spacing between buttons
        int spacing = buttonWidth / numButtons;

        int centerX = this.width / 2 - (numButtons * buttonWidth + (numButtons - 1) * spacing) / 2;

        int button1X = centerX;
        int button2X = centerX + buttonWidth + spacing;
        int button3X = centerX + 2 * (buttonWidth + spacing);
        int button4X = centerX + 3 * (buttonWidth + spacing);


        this.backButton = ButtonWidget.builder(TextHelper.translatable("automodpack.back"), button -> {
            assert this.client != null;
            this.client.setScreen(null);
        }).position(button1X, this.height - 35).size(buttonWidth, 20).build();

        this.selectButton = ButtonWidget.builder(TextHelper.translatable("automodpack.select"), button -> {
            StaticVariables.LOGGER.info("Select modpack {} from {}", getModpack().getName(), getModpackPath());
            selectModpack(getModpackPath(), getModpack());
        }).position(button2X, this.height - 35).size(buttonWidth, 20).build();

        this.redownloadButton = ButtonWidget.builder(TextHelper.translatable("automodpack.redownload"), button -> {
            StaticVariables.LOGGER.info("Redownload {} from {}", getModpack().getName(), getModpack().getLink());
            reDownloadModpack(getModpackPath(), getModpack());
        }).position(button3X, this.height - 35).size(buttonWidth, 20).build();

        this.removeButton = ButtonWidget.builder(TextHelper.translatable("automodpack.delete"), button -> {
            StaticVariables.LOGGER.info("Remove modpack {} from {}", getModpack().getName(), getModpackPath());
            removeModpack(getModpackPath());
        }).position(button4X, this.height - 35).size(buttonWidth, 20).build();

    }

    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);

        this.MenuWidgetWidget.render(context, mouseX, mouseY, delta);
        activateOrDeactivateButtons();

        super.render(context, mouseX, mouseY, delta);
    }

    public void activateOrDeactivateButtons() {
        ModpackListEntry modpackEntry = this.MenuWidgetWidget.getSelectedOrNull();
        if (modpackEntry == null || getModpack() == null || getModpackPath() == null) {
            this.selectButton.active = false;
            this.redownloadButton.active = false;
            this.removeButton.active = false;
        } else {
            this.redownloadButton.active = true;
            this.removeButton.active = true;

            String currentModpack = clientConfig.selectedModpack;
            String selectedModpack = modpackEntry.getModpackPath().getFileName().toString();
            this.selectButton.active = currentModpack.equals(selectedModpack);
        }
    }

    public Modpack.ModpackObject getModpack() {
        return this.MenuWidgetWidget.getSelectedOrNull().getModpack();
    }

    public Path getModpackPath() {
        return this.MenuWidgetWidget.getSelectedOrNull().getModpackPath();
    }

    private void reDownloadModpack(Path modpackPath, Modpack.ModpackObject modpack) {
        String modpackLink = modpack.getLink();
        Jsons.ModpackContentFields serverModpackContent = ModpackUtils.getServerModpackContent(modpackLink);

        File modpackFile = modpackPath.toFile();
        CustomFileUtils.forceDelete(modpackFile, false);

        new ModpackUpdater(serverModpackContent, modpackLink, modpackFile);

        StaticVariables.LOGGER.info("Redownloaded modpack {} from {}", modpack.getName(), modpackLink);
    }

    private void removeModpack(Path modpackPath) {
        String currentModpack = clientConfig.selectedModpack;
        if (currentModpack.equals(modpackPath.getFileName().toString())) {
            clientConfig.selectedModpack = "";
            ConfigTools.saveConfig(clientConfigFile, clientConfig);
        }

        CustomFileUtils.forceDelete(modpackPath.toFile(), false);
        // TODO: remove modpack from minecraft files
    }

    private void selectModpack(Path modpackPath, Modpack.ModpackObject modpack) {
        String selectedModpack = modpackPath.getFileName().toString();
        if (clientConfig.selectedModpack.equals(selectedModpack)) {
            return;
        }
        clientConfig.selectedModpack = selectedModpack;
        ConfigTools.saveConfig(clientConfigFile, clientConfig);
    }
}