package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.ReLauncher;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.client.ModpackUpdater;

import java.io.File;

public class RestartScreen extends Screen {
    private final File gameDir;
    private static ButtonWidget cancelButton;
    private static ButtonWidget restartButton;
    private static ButtonWidget changelogsButton;
    private final Screen parent;

    public RestartScreen(Screen parent, File gameDir) {
        super(TextHelper.literal("RestartScreen"));
        this.gameDir = gameDir;
        this.parent = parent;
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(cancelButton);
        this.addDrawableChild(restartButton);
        this.addDrawableChild(changelogsButton);

        if (ModpackUpdater.changelogList.isEmpty()) {
            changelogsButton.active = false;
        }
    }
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, this.textRenderer, TextHelper.literal("Restart"), this.width / 2, 55, 16777215);
        drawCenteredText(matrices, this.textRenderer, TextHelper.translatable("gui.automodpack.screen.restart.description"), this.width / 2, 80, 16777215);
        drawCenteredText(matrices, this.textRenderer, TextHelper.translatable("gui.automodpack.screen.restart.secDescription"), this.width / 2, 90, 16777215);
        super.render(matrices, mouseX, mouseY, delta);
    }
    public void initWidgets() {
        assert this.client != null;
        cancelButton = new ButtonWidget(this.width / 2 - 150, this.height / 6 + 120, 150, 20, TextHelper.translatable("gui.automodpack.screen.restart.button.cancel").formatted(Formatting.RED), (button) -> {
            this.client.setScreen(null);
        });
        restartButton = new ButtonWidget(this.width / 2, this.height / 6 + 120, 150, 20, TextHelper.translatable("gui.automodpack.screen.restart.button.quit").formatted(Formatting.GREEN), (button) -> {
            new ReLauncher.Restart(gameDir);
        });
        changelogsButton = new ButtonWidget(this.width / 2 - 75, this.height / 6 + 145, 150, 20, TextHelper.translatable("gui.automodpack.screen.restart.button.changelogs").formatted(Formatting.DARK_AQUA), (button) -> {
            this.client.setScreen(new ChangelogScreen(this, gameDir));
        });
    }
    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}