package pl.skidam.automodpack.client.ui;

import java.util.List;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack.client.audio.AudioManager;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.utils.SelectionManager;

public class DownloadSelectionScreen extends VersionedScreen {
    private final Screen parent;
    private final ModpackUpdater modpackUpdaterInstance;

    public DownloadSelectionScreen(Screen parent, ModpackUpdater modpackUpdaterInstance) {
        super(VersionedText.literal("DownloadSelectionScreen"));
        this.parent = parent;
        this.modpackUpdaterInstance = modpackUpdaterInstance;

        if (AudioManager.isMusicPlaying()) {
            AudioManager.stopMusic();
        }
    }
    @Override
    protected void init() {
        super.init();
        assert this.client != null;

        this.addDrawableChild(buttonWidget(this.width / 2, this.height / 2 + 150, 120, 20, VersionedText.translatable("automodpack.ds.cancel"), button -> {
            this.client.setScreen(parent);
        }));

        //buttons from Selectionmanager

        List<String> modpacks = SelectionManager.getModpackFolders();

        //dynamisch wieviele buttons vorhanden sind
        int dynamicY = this.height / 2 - (modpacks.size() * 15);
        int i=0;

        for (String modpack : modpacks) {
            // abstand einfügen
            int y = dynamicY + (i * 25);

            this.addDrawableChild(buttonWidget(this.width / 2, y, 140, 20, Text.literal(modpack).formatted(Formatting.BOLD), button -> {
                //Auswählen und dann starten
                SelectionManager.setSelectedPack(modpack);
                Util.getMainWorkerExecutor().execute(modpackUpdaterInstance::startUpdate);
            }));
            i++;
        }

        //Full Serverpack Button
        this.addDrawableChild(buttonWidget(this.width / 2, this.height / 2 + 175, 160, 20, Text.literal("Full Server Pack").formatted(Formatting.RED), button -> {
            SelectionManager.setSelectedPack("fullserver");
            Util.getMainWorkerExecutor().execute(modpackUpdaterInstance::startUpdate);
        }));

        /* Old Buttons
        this.addDrawableChild(buttonWidget(this.width / 2, this.height / 2 + 50, 120, 20, VersionedText.translatable("automodpack.ds.standard").formatted(Formatting.BOLD), button -> {
            Util.getMainWorkerExecutor().execute(modpackUpdaterInstance::startUpdate);
        }));

        this.addDrawableChild(buttonWidget(this.width / 2, this.height / 2 + 75, 120, 20, VersionedText.translatable("automodpack.ds.highendconfirm").formatted(Formatting.BOLD), button -> {
            Util.getMainWorkerExecutor().execute(modpackUpdaterInstance::startHighUpdate);
        }));

        this.addDrawableChild(buttonWidget(this.width / 2, this.height / 2 + 100, 120, 20, VersionedText.translatable("automodpack.ds.lowendconfirm").formatted(Formatting.BOLD), button -> {
            Util.getMainWorkerExecutor().execute(modpackUpdaterInstance::startLowUpdate);
        }));

        this.addDrawableChild(buttonWidget(this.width / 2, this.height / 2 + 125, 120, 20, VersionedText.translatable("automodpack.ds.completeconfirm").formatted(Formatting.BOLD), button -> {
            Util.getMainWorkerExecutor().execute(modpackUpdaterInstance::startServerUpdate);
        }));
         */
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.ds").formatted(Formatting.BOLD), this.width / 2, this.height / 2 - 60, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.ds.description"), this.width / 2, this.height / 2 - 35, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.ds.secDescription"), this.width / 2, this.height / 2 - 25, 16777215);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}