package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.TextHelper;

import static pl.skidam.automodpack.client.ModpackUpdater.totalFetchedFiles;


public class FetchScreen extends Screen {

    public FetchScreen() {
        super(TextHelper.literal("FetchScreen"));
    }

    @Override
    protected void init() {
        super.init();
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);

        // Fetching direct url's from Modrinth and CurseForge.
        drawCenteredTextWithShadow(matrices, this.textRenderer, TextHelper.translatable("gui.automodpack.fetching").formatted(Formatting.BOLD), this.width / 2, this.height / 2 - 60, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, TextHelper.translatable("gui.automodpack.wait"), this.width / 2, this.height / 2 - 48, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, TextHelper.translatable("gui.automodpack.foundFiles", totalFetchedFiles), this.width / 2, this.height / 2 - 30, 16777215);

        super.render(matrices, mouseX, mouseY, delta);
    }

    public boolean shouldCloseOnEsc() {
        return false;
    }
}
