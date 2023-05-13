package pl.skidam.automodpack.client.ui.widget;

import com.mojang.blaze3d.systems.RenderSystem;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.MathHelper;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.modpack.Modpack;

import java.nio.file.Path;
import java.util.Map;

import static pl.skidam.automodpack.StaticVariables.clientConfig;

public class MenuWidgetWidget extends AlwaysSelectedEntryListWidget<ModpackListEntry> {

    private boolean scrolling;

    public MenuWidgetWidget(MinecraftClient client, int width, int height, int top, int bottom, int itemHeight) {
        super(client, width, height, top, bottom, itemHeight);
        this.centerListVertically = true;

        Map<Path, Modpack.ModpackObject> modpacks = Modpack.getModpacksMap();
        Modpack.setModpackObject(modpacks);
        String selectedModpack = clientConfig.selectedModpack;


        this.clearEntries();

        if (modpacks == null || modpacks.isEmpty()) {
            ModpackListEntry entry = new ModpackListEntry(TextHelper.literal("No modpacks found").formatted(Formatting.BOLD), null, null, this.client);
            this.addEntry(entry);
            this.setSelected(entry);
            return;
        }

        for (Map.Entry<Path, Modpack.ModpackObject> modpack : modpacks.entrySet()) {

            Modpack.ModpackObject modpackObject = modpack.getValue();

            String modpackName = modpackObject.getName();
            Path modpackPath = modpack.getKey();

            MutableText text = TextHelper.literal(modpackName);
            if (modpackName.isBlank()) {
                text = TextHelper.literal(String.valueOf(modpackPath.getFileName()));
            }

            String folderName = modpack.getKey().getFileName().toString();
            if (folderName.equals(selectedModpack)) {
                text = text.formatted(Formatting.BOLD);
            }

            ModpackListEntry entry = new ModpackListEntry(text, modpackObject, modpackPath, this.client);

            this.addEntry(entry);

            if (folderName.equals(selectedModpack)) {
                this.setSelected(entry);
            }
        }
    }

    @Override
    public int getRowWidth() {
        return this.width;
    }

    @Override
    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        double scale = this.client.getWindow().getScaleFactor();

        RenderSystem.enableScissor((int) (this.left * scale), (int) (this.client.getWindow().getFramebufferHeight() - ((this.top + this.height) * scale)), (int) (this.width * scale), (int) (this.height * scale));
        super.render(matrices, mouseX, mouseY, delta);
        RenderSystem.disableScissor();
    }

    public final ModpackListEntry getEntryAtPos(double x, double y) {
        int int_5 = MathHelper.floor(y - (double) this.top) - this.headerHeight + (int) this.getScrollAmount() - 4;
        int index = int_5 / this.itemHeight;
        return x < (double) this.getScrollbarPositionX() && x >= (double) getRowLeft() && x <= (double) (getRowLeft() + getRowWidth()) && index >= 0 && int_5 >= 0 && index < this.getEntryCount() ? this.children().get(index) : null;
    }

    @Override
    protected void updateScrollingState(double mouseX, double mouseY, int button) {
        super.updateScrollingState(mouseX, mouseY, button);
        this.scrolling = button == 0 && mouseX >= (double) this.getScrollbarPositionX() && mouseX < (double) (this.getScrollbarPositionX() + 6);
    }

    @Override
    public boolean mouseClicked(double mouseX, double mouseY, int button) {
        this.updateScrollingState(mouseX, mouseY, button);
        if (!this.isMouseOver(mouseX, mouseY)) {
            return false;
        } else {
            ModpackListEntry entry = this.getEntryAtPos(mouseX, mouseY);
            if (entry != null) {
                if (entry.mouseClicked(mouseX, mouseY, button)) {
                    this.setFocused(entry);
                    this.setSelected(entry);
                    this.setDragging(true);
                    return true;
                }
            }

            return this.scrolling;
        }
    }

    @Override
    public void setSelected(ModpackListEntry entry) {
        super.setSelected(entry);
        if (entry != null) {
            this.centerScrollOn(entry);
        }
    }

    @Override
    protected int getScrollbarPositionX() {
        return this.width - 6;
    }
}
