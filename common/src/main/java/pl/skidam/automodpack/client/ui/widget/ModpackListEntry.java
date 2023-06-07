package pl.skidam.automodpack.client.ui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.text.MutableText;
import net.minecraft.text.Text;
import org.jetbrains.annotations.Nullable;
import pl.skidam.automodpack.modpack.Modpack;

import java.nio.file.Path;


public class ModpackListEntry extends AlwaysSelectedEntryListWidget.Entry<ModpackListEntry> {

    protected final MinecraftClient client;
    public final Modpack.ModpackObject modpack;
    public final Path modpackPath;
    private final MutableText text;
    private boolean nullEntry = false;

    public ModpackListEntry(MutableText text, Modpack.ModpackObject modpack, Path modpackPath, MinecraftClient client) {
        this.text = text;
        this.modpack = modpack;
        this.modpackPath = modpackPath;
        this.client = client;

        if (modpack == null && modpackPath == null) {
            nullEntry = true;
        }
    }

    @Override
    public Text getNarration() {
        return text;
    }

    @Override
    public void render(DrawContext context, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {

        context.getMatrices().push();

        int centeredX = x + entryWidth / 2;
        if (nullEntry) {
            float scale = 1.5f;
            context.getMatrices().scale(scale, scale, scale);
            centeredX = (int) (x + entryWidth / (2 * scale));
        }

        context.drawCenteredTextWithShadow(client.textRenderer, text, centeredX, y, 16777215);

        context.getMatrices().pop();
    }

    @Nullable
    public Modpack.ModpackObject getModpack() {
        return modpack;
    }

    @Nullable
    public Path getModpackPath() {
        return modpackPath;
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int delta) {
        return !nullEntry;
    }

    @Override
    public boolean mouseReleased(double mouseX, double mouseY, int button) {
        return false;
    }
}
