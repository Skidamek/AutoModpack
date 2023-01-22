package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.client.ModpackUpdater;

import java.io.File;
import java.util.concurrent.CompletableFuture;

public class DangerScreen extends Screen {
    private final Screen parent;
    private final String link;
    private final File modpackDir;
    private final boolean loadIfItsNotLoaded;
    private final File modpackContentFile;

    public DangerScreen(Screen parent, String link, File modpackDir, boolean loadIfItsNotLoaded, File modpackContentFile) {
        super(TextHelper.translatable("gui.automodpack.screen.danger.title").formatted(Formatting.BOLD));
        this.parent = parent;
        this.link = link;
        this.modpackDir = modpackDir;
        this.loadIfItsNotLoaded = loadIfItsNotLoaded;
        this.modpackContentFile = modpackContentFile;
    }

    @Override
    protected void init() {
        super.init();
        assert this.client != null;
        //                                                       - 210
        this.addDrawableChild(new ButtonWidget(this.width / 2 - 110, this.height / 6 + 96, 120, 20, TextHelper.translatable("gui.automodpack.screen.danger.button.cancel").formatted(Formatting.RED), (button) -> {
            AutoModpack.LOGGER.error("User canceled download, setting his to screen " + parent.getTitle().getString());
            this.client.setScreen(parent);
        }));

//        this.addDrawableChild(new ButtonWidget(this.width / 2 - 90, this.height / 6 + 96, 190, 20, TextHelper.translatable("gui.automodpack.screen.danger.button.dontshowagain").formatted(Formatting.GRAY), (button) -> {
//            AutoModpack.clientConfig.dangerScreen
//            CompletableFuture.runAsync(DownloadModpack::new);
//            this.client.setScreen(new LoadingScreen());
//        }));
        //                                                       + 100
        this.addDrawableChild(new ButtonWidget(this.width / 2 + 10, this.height / 6 + 96, 120, 20, TextHelper.translatable("gui.automodpack.screen.danger.button.accept").formatted(Formatting.GREEN), (button) -> {
            CompletableFuture.runAsync(() -> {
                ModpackUpdater.ModpackUpdaterMain(link, modpackDir, loadIfItsNotLoaded, modpackContentFile);
            });
        }));
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, 55, 16777215);
        drawCenteredText(matrices, this.textRenderer, TextHelper.translatable("gui.automodpack.screen.danger.description"), this.width / 2, 80, 16777215);
        drawCenteredText(matrices, this.textRenderer, TextHelper.translatable("gui.automodpack.screen.danger.secDescription"), this.width / 2, 90, 16777215);
        super.render(matrices, mouseX, mouseY, delta);
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
