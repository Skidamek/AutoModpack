package pl.skidam.automodpack.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.client.modpack.DownloadModpack;
import pl.skidam.automodpack.config.Config;

import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.AutoModpackMain.ModpackUpdated;
import static pl.skidam.automodpack.client.modpack.DownloadModpack.prepare.DangerScreenWasShown;

@Environment(EnvType.CLIENT)
public class DangerScreen extends Screen {
    private Screen parent;

    public DangerScreen() {
        super(Text.translatable("gui.automodpack.screen.danger.title").formatted(Formatting.BOLD));
    }

    @Override
    protected void init() {
        super.init();
        assert this.client != null;
        this.addDrawableChild(new ButtonWidget(this.width / 2 - 210, this.height / 6 + 96, 120, 20, Text.translatable("gui.automodpack.screen.danger.button.cancel").formatted(Formatting.GREEN), (button) -> {
            ModpackUpdated = "false";
            this.client.setScreen(parent);
        }));
        this.addDrawableChild(new ButtonWidget(this.width / 2 - 90, this.height / 6 + 96, 190, 20, Text.translatable("gui.automodpack.screen.danger.button.dontshowagain").formatted(Formatting.GRAY), (button) -> {
            Config.DANGER_SCREEN = false;
            DangerScreenWasShown = true;
            CompletableFuture.runAsync(DownloadModpack::new);
            this.client.setScreen(new LoadingScreen());
        }));
        this.addDrawableChild(new ButtonWidget(this.width / 2 + 100, this.height / 6 + 96, 120, 20, Text.translatable("gui.automodpack.screen.danger.button.accept").formatted(Formatting.RED), (button) -> {
            DangerScreenWasShown = true;
            CompletableFuture.runAsync(DownloadModpack::new);
            this.client.setScreen(new LoadingScreen());
        }));
    }

    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, 55, 16777215);
        drawCenteredText(matrices, this.textRenderer, Text.translatable("gui.automodpack.screen.danger.description"), this.width / 2, 80, 16777215);
        drawCenteredText(matrices, this.textRenderer, Text.translatable("gui.automodpack.screen.danger.secDescription"), this.width / 2, 90, 16777215);
        super.render(matrices, mouseX, mouseY, delta);
    }

    public boolean shouldCloseOnEsc() { return false; }
}