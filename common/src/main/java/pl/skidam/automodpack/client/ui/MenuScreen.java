package pl.skidam.automodpack.client.ui;

import net.fabricmc.api.EnvType;
import net.fabricmc.api.Environment;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.widget.AlwaysSelectedEntryListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.util.math.MatrixStack;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.TextHelper;
import pl.skidam.automodpack.client.ModpackUpdater;
import pl.skidam.automodpack.client.ModpackUtils;
import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.utils.ModpackContentTools;

import java.io.File;
import java.util.Map;

import static pl.skidam.automodpack.StaticVariables.*;

@Environment(EnvType.CLIENT)
public class MenuScreen extends Screen {
    private MenuScreen.ModpackSelectionListWidget modpackSelectionList;
    public String modpack;
    private final Screen parent;

    public MenuScreen(Screen parent) {
        super(TextHelper.literal("Auto").formatted(Formatting.GOLD).append(TextHelper.literal("Modpack").formatted(Formatting.WHITE).append(TextHelper.literal(" Menu").formatted(Formatting.GRAY)).formatted(Formatting.BOLD)));
        assert client != null;
        this.parent = parent;
    }

    public static String GetSelectedModpack() {
        return clientConfig.selectedModpack;
    }

    @Override
    protected void init() {
        this.modpackSelectionList = new ModpackSelectionListWidget(this.client);
        this.addSelectableChild(this.modpackSelectionList);

        super.init();
        assert this.client != null;

        this.addDrawableChild(new ButtonWidget(this.width / 2 - 210, this.height - 38, 115, 20, TextHelper.translatable("gui.automodpack.button.update"), (button) -> {
            AutoModpackToast.add(0);
            String modpack = clientConfig.selectedModpack;
            Jsons.ModpackContentFields serverModpackContent = ModpackUtils.getServerModpackContent(selectedModpackLink);
            new ModpackUpdater(serverModpackContent, ModpackContentTools.getModpackLink(modpack), ModpackContentTools.getModpackDir(modpack));
        }));

        this.addDrawableChild(new ButtonWidget(this.width / 2 - 90, this.height - 38, 115, 20, TextHelper.translatable("gui.automodpack.button.delete"), (button) -> {
//            this.client.setScreen(new ConfirmScreen());
        }));

        // make back to the main menu button
        this.addDrawableChild(new ButtonWidget(this.width / 2 + 100, this.height - 38, 115, 20, TextHelper.translatable("gui.automodpack.button.back"), (button) -> {
            this.client.setScreen(new TitleScreen());
        }));
    }


    public void render(MatrixStack matrices, int mouseX, int mouseY, float delta) {
        this.renderBackground(matrices);
        this.modpackSelectionList.render(matrices, mouseX, mouseY, delta);
//        String selectedServerIP = this.getSelectedServerIP();
//        String selectedModpack = this.getSelectedModpack();
        //drawCenteredText(matrices, this.textRenderer, selectedServerIP, this.width / 2, 60, 16777215);
        //drawCenteredText(matrices, this.textRenderer, selectedModpack, this.width / 2, 70, 16777215);

        drawCenteredText(matrices, this.textRenderer, this.title, this.width / 2, 15, 16777215);
        //drawCenteredText(matrices, this.textRenderer, new TranslatableText("gui.automodpack.screen.menu.description"), this.width / 2, 20, 16777215);
        super.render(matrices, mouseX, mouseY, delta);
    }

    @Environment(EnvType.CLIENT)
    class ModpackSelectionListWidget extends AlwaysSelectedEntryListWidget<MenuScreen.ModpackSelectionListWidget.ModpackEntry> {
        public ModpackSelectionListWidget(MinecraftClient client) {
            super(client, MenuScreen.this.width, MenuScreen.this.height, 32, MenuScreen.this.height - 65 + 4, 18);

            Map<String, File> map = ModpackContentTools.getListOfModpacks();

            for (Map.Entry<String, File> entry : map.entrySet()) {
                this.addEntry(new ModpackSelectionListWidget.ModpackEntry(entry.getKey()));
            }

            // for every entry in the list, check if it's the selected modpack and select it
            for (int i = 0; i < this.children().size(); i++) {
                if (this.children().get(i).toString().equals(clientConfig.selectedModpack)) {
                    this.setSelected(this.children().get(i));
                }
            }
        }

        protected int getScrollbarPositionX() {
            return super.getScrollbarPositionX() + 20;
        }

        public int getRowWidth() {
            return super.getRowWidth() + 50;
        }

        protected void renderBackground(MatrixStack matrices) {
            MenuScreen.this.renderBackground(matrices);
        }

        protected boolean isFocused() {
            return MenuScreen.this.getFocused() == this;
        }

        @Environment(EnvType.CLIENT)
        public class ModpackEntry extends Entry<MenuScreen.ModpackSelectionListWidget.ModpackEntry> {
            final String modpackDefinition;

            public ModpackEntry(String modpackDefinition) {
                this.modpackDefinition = modpackDefinition;
            }

            public void render(MatrixStack matrices, int index, int y, int x, int entryWidth, int entryHeight, int mouseX, int mouseY, boolean hovered, float tickDelta) {
                String string = this.modpackDefinition;
                MenuScreen.this.textRenderer
                        .drawWithShadow(
                                matrices,
                                string,
                                (float)(MenuScreen.ModpackSelectionListWidget.this.width / 2 - MenuScreen.this.textRenderer.getWidth(string) / 2),
                                (float)(y + 1),
                                16777215,
                                true
                        );
            }

            public boolean mouseClicked(double mouseX, double mouseY, int button) {
                if (button == 0) {
                    this.onPressed();
                    return true;
                } else {
                    return false;
                }
            }

            private void onPressed() {
                ModpackSelectionListWidget.this.setSelected(this);
                clientConfig.selectedModpack = this.modpackDefinition;
                ConfigTools.saveConfig(clientConfigFile, clientConfig);
            }

            public Text getNarration() {
                return TextHelper.translatable("narrator.select", this.modpackDefinition);
            }
        }
    }
}