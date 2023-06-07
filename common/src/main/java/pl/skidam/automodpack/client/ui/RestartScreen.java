package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.ReLauncher;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.audio.AudioManager;

import java.io.File;

public class RestartScreen extends Screen {
    private final File gameDir;
    private final boolean fullDownload;
    private static ButtonWidget cancelButton;
    private static ButtonWidget restartButton;
    private static ButtonWidget changelogsButton;

    public RestartScreen(File gameDir, boolean fullDownload) {
        super(TextHelper.literal("RestartScreen"));
        this.gameDir = gameDir;
        this.fullDownload = fullDownload;

        if (AudioManager.isMusicPlaying()) {
            AudioManager.stopMusic();
        }
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
        drawCenteredTextWithShadow(matrices, this.textRenderer, TextHelper.translatable("automodpack.restart." + (fullDownload ? "full" : "update")).formatted(Formatting.BOLD), this.width / 2, this.height / 2 - 60, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, TextHelper.translatable("automodpack.restart.description"), this.width / 2, this.height / 2 - 35, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, TextHelper.translatable("automodpack.restart.secDescription"), this.width / 2, this.height / 2 - 25, 16777215);
        super.render(matrices, mouseX, mouseY, delta);
    }

    public void initWidgets() {
        assert this.client != null;
        cancelButton = ButtonWidget.builder(TextHelper.translatable("automodpack.restart.cancel").formatted(Formatting.RED), button -> {
            this.client.setScreen(null);
        }).position(this.width / 2 - 155, this.height / 2 + 50).size(150, 20).build();

        restartButton = ButtonWidget.builder(TextHelper.translatable("automodpack.restart.confirm").formatted(Formatting.GREEN), button -> {
            new ReLauncher.Restart(gameDir, fullDownload);
        }).position(this.width / 2 + 5, this.height / 2 + 50).size(150, 20).build();

        changelogsButton = ButtonWidget.builder(TextHelper.translatable("automodpack.changelog.view").formatted(Formatting.DARK_AQUA), button -> {
            this.client.setScreen(new ChangelogScreen(this, gameDir));
        }).position(this.width / 2 - 75, this.height / 2 + 75).size(150, 20).build();
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}