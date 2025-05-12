package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack.client.audio.AudioManager;
import java.nio.file.Files;
import java.nio.file.Path;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;

import pl.skidam.automodpack_loader_core.utils.SelectionManager;

public class DangerScreen extends VersionedScreen {
    private final Screen parent;
    private final ModpackUpdater modpackUpdaterInstance;
    private final SelectionManager selectionManagerInstance;

    public DangerScreen(Screen parent, ModpackUpdater modpackUpdaterInstance, SelectionManager selectionManagerInstance) {
        super(VersionedText.literal("DangerScreen"));
        this.parent = parent;
        this.modpackUpdaterInstance = modpackUpdaterInstance;
        this.selectionManagerInstance = selectionManagerInstance;

        if (AudioManager.isMusicPlaying()) {
            AudioManager.stopMusic();
        }
    }

    @Override
    protected void init() {
        super.init();
        assert this.client != null;

        this.addDrawableChild(buttonWidget(this.width / 2 - 115, this.height / 2 + 50, 120, 20, VersionedText.translatable("automodpack.danger.cancel"), button -> {
            this.client.setScreen(parent);
        }));

        if (selectionManagerInstance != null) {
            this.addDrawableChild(buttonWidget(this.width / 2 + 15, this.height / 2 + 50, 120, 20, VersionedText.translatable("automodpack.danger.selection").formatted(Formatting.BOLD), button -> {
                this.client.setScreen(new DownloadSelectionScreen(parent, modpackUpdaterInstance));
            }));
        } else {
            this.addDrawableChild(buttonWidget(this.width / 2 + 15, this.height / 2 + 50, 120, 20, VersionedText.translatable("automodpack.danger.confirm").formatted(Formatting.BOLD), button -> {
                Util.getMainWorkerExecutor().execute(modpackUpdaterInstance::startUpdate);
            }));
        }
        Path serverConfigPath = ModpackUtils.getMinecraftPath().resolve("mods/automodpack/automodpack-server.json");
        if (Files.exists(serverConfigPath)) {
            Jsons.ServerConfigFields serverConfig = ConfigTools.load(serverConfigPath, Jsons.ServerConfigFields.class);

            if (serverConfig != null && serverConfig.enableFullServerPack) {
                this.addDrawableChild(buttonWidget(this.width / 2, this.height / 2 + 100, 160, 20, VersionedText.literal("automodpack.danger.fullserverpack").formatted(Formatting.RED), button -> {
                            SelectionManager.setSelectedPack("fullserver");
                            Util.getMainWorkerExecutor().execute(modpackUpdaterInstance::startUpdate);
                        }));
            }
        }
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.danger").formatted(Formatting.BOLD), this.width / 2, this.height / 2 - 60, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.danger.description"), this.width / 2, this.height / 2 - 35, 16777215);
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.danger.secDescription"), this.width / 2, this.height / 2 - 25, 16777215);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
