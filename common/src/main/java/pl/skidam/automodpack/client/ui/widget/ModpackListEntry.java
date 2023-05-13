package pl.skidam.automodpack.client.ui.widget;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.util.math.MatrixStack;
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

    public ModpackListEntry(MutableText text, Modpack.ModpackObject modpack, Path modpackPath, MinecraftClient client) {
        this.text = text;
        this.modpack = modpack;
        this.modpackPath = modpackPath;
        this.client = client;
    }

    @Override
    public Text getNarration() {
        return text;
    }

    @Override
    public void render(MatrixStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
        int centeredX = x + entryWidth / 2;
        DrawableHelper.drawCenteredTextWithShadow(matrices, client.textRenderer, text, centeredX, y, 16777215);
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
        return true;
    }

}
