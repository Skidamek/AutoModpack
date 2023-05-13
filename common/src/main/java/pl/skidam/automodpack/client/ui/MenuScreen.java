package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import pl.skidam.automodpack.StaticVariables;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.client.ui.widget.MenuWidgetWidget;
import pl.skidam.automodpack.modpack.Modpack;

import java.nio.file.Path;

public class MenuScreen extends Screen {
    private MenuWidgetWidget MenuWidgetWidget;
    private ButtonWidget backButton;
    private ButtonWidget removeButton;
    private ButtonWidget redownloadButton;

    public MenuScreen() {
        super(TextHelper.literal("MenuScreen"));
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(MenuWidgetWidget);

        this.addDrawableChild(backButton);
        this.addDrawableChild(removeButton);
        this.addDrawableChild(redownloadButton);

    }

    public void initWidgets() {
        this.MenuWidgetWidget = new MenuWidgetWidget(this.client, this.width, this.height, 48, this.height - 50, 20);


        int buttonWidth = this.width / 4;
        int centerX = this.width / 2 - buttonWidth / 2;

        this.backButton = ButtonWidget.builder(TextHelper.translatable("gui.automodpack.back"), button -> {
            assert this.client != null;
            this.client.setScreen(null);
        }).position(centerX - (buttonWidth + 20), this.height - 30).size(buttonWidth, 20).build();

        this.removeButton = ButtonWidget.builder(TextHelper.translatable("gui.automodpack.delete"), button -> {
            StaticVariables.LOGGER.info("Remove modpack {} from {}", getModpack().getName(), getModpackPath());
        }).position(centerX, this.height - 30).size(buttonWidth, 20).build();

        this.redownloadButton = ButtonWidget.builder(TextHelper.translatable("gui.automodpack.redownload"), button -> {
            StaticVariables.LOGGER.info("Redownload {} from {}", getModpack().getName(), getModpack().getLink());
        }).position(centerX + (buttonWidth + 20), this.height - 30).size(buttonWidth, 20).build();
    }

    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);

        this.MenuWidgetWidget.render(matrices, mouseX, mouseY, delta);
        activateOrDeactivateButtons();

        super.render(matrices, mouseX, mouseY, delta);
    }

    public void activateOrDeactivateButtons() {
        if (this.MenuWidgetWidget.getSelectedOrNull() == null || getModpack() == null || getModpackPath() == null) {
            this.removeButton.active = false;
            this.redownloadButton.active = false;
        } else {
            this.removeButton.active = true;
            this.redownloadButton.active = true;
        }
    }

    public Modpack.ModpackObject getModpack() {
        return this.MenuWidgetWidget.getSelectedOrNull().getModpack();
    }

    public Path getModpackPath() {
        return this.MenuWidgetWidget.getSelectedOrNull().getModpackPath();
    }
}