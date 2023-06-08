package pl.skidam.automodpack.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.audio.AudioManager;

import java.io.File;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack.StaticVariables.LOGGER;

@Environment(EnvType.CLIENT)
public class DangerScreen extends Screen {
    private final Screen parent;
    private final String link;
    private final File modpackDir;
    private final File modpackContentFile;

    public DangerScreen(Screen parent, String link, File modpackDir, File modpackContentFile) {
        super(TextHelper.translatable("automodpack.danger").formatted(Formatting.BOLD));
        this.parent = parent;
        this.link = link;
        this.modpackDir = modpackDir;
        this.modpackContentFile = modpackContentFile;

        if (AudioManager.isMusicPlaying()) {
            AudioManager.stopMusic();
        }
    }

    @Override
    protected void init() {
        super.init();
        assert this.client != null;

        this.addDrawableChild(ButtonWidget.builder(TextHelper.translatable("automodpack.danger.cancel").formatted(Formatting.RED), button -> {
            LOGGER.error("User canceled download, setting his to screen " + parent.getTitle().getString());
            this.client.setScreen(parent);
        }).position(this.width / 2 - 115, this.height / 2 + 50).size(120, 20).build());

        this.addDrawableChild(ButtonWidget.builder(TextHelper.translatable("automodpack.danger.confirm").formatted(Formatting.GREEN), button -> {
            CompletableFuture.runAsync(() -> {
                ModpackUpdater.ModpackUpdaterMain(link, modpackDir, modpackContentFile);
            });
        }).position(this.width / 2 + 15, this.height / 2 + 50).size(120, 20).build());
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);
        context.drawCenteredTextWithShadow(this.textRenderer, this.title, this.width / 2, this.height / 2 - 60, 16777215);
        context.drawCenteredTextWithShadow(this.textRenderer, TextHelper.translatable("automodpack.danger.description"), this.width / 2, this.height / 2 - 35, 16777215);
        context.drawCenteredTextWithShadow(this.textRenderer, TextHelper.translatable("automodpack.danger.secDescription"), this.width / 2, this.height / 2 - 25, 16777215);
        super.render(context, mouseX, mouseY, delta);
    }

    @Override
    public Text getTitle() { // hehe
        return TextHelper.literal("DangerScreen");
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
