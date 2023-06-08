package pl.skidam.automodpack.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.TextHelper;

import static pl.skidam.automodpack.client.ModpackUpdater.totalFetchedFiles;

@Environment(EnvType.CLIENT)
public class FetchScreen extends Screen {

    public FetchScreen() {
        super(TextHelper.literal("FetchScreen"));
    }

    @Override
    protected void init() {
        super.init();
    }

    @Override
    public void render(DrawContext context, int mouseX, int mouseY, float delta) {
        this.renderBackground(context);

        // Fetching direct url's from Modrinth and CurseForge.
        context.drawCenteredTextWithShadow(this.textRenderer, TextHelper.translatable("automodpack.fetch").formatted(Formatting.BOLD), this.width / 2, this.height / 2 - 60, 16777215);
        context.drawCenteredTextWithShadow(this.textRenderer, TextHelper.translatable("automodpack.wait"), this.width / 2, this.height / 2 - 48, 16777215);
        context.drawCenteredTextWithShadow(this.textRenderer, TextHelper.translatable("automodpack.fetch.found", totalFetchedFiles), this.width / 2, this.height / 2 - 30, 16777215);

        super.render(context, mouseX, mouseY, delta);
    }

    public boolean shouldCloseOnEsc() {
        return false;
    }
}
