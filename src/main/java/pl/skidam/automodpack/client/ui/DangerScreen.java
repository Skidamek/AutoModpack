package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack.client.audio.AudioManager;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;

public class DangerScreen extends VersionedScreen {
    private final Screen parent;
    private final ModpackUpdater modpackUpdaterInstance;

    public DangerScreen(Screen parent, ModpackUpdater modpackUpdaterInstance) {
        super(VersionedText.literal("DangerScreen"));
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

        this.addDrawableChild(buttonWidget(this.width / 2 - 115, this.height / 2 + 50, 120, 20, VersionedText.translatable("automodpack.danger.cancel"), button -> {
            this.client.setScreen(parent);
        }));

        this.addDrawableChild(buttonWidget(this.width / 2 + 15, this.height / 2 + 50, 120, 20, VersionedText.translatable("automodpack.danger.highendconfirm").formatted(Formatting.BOLD), button -> {
            Util.getMainWorkerExecutor().execute(modpackUpdaterInstance::startHighUpdate);
        }));

        this.addDrawableChild(buttonWidget(this.width / 2 + 15, this.height / 2 + 50, 120, 20, VersionedText.translatable("automodpack.danger.lowendconfirm").formatted(Formatting.BOLD), button -> {
            Util.getMainWorkerExecutor().execute(modpackUpdaterInstance::startLowUpdate);
        }));
        
        this.addDrawableChild(buttonWidget(this.width / 2 + 15, this.height / 2 + 50, 120, 20, VersionedText.translatable("automodpack.danger.completeconfirm").formatted(Formatting.BOLD), button -> {
            Util.getMainWorkerExecutor().execute(modpackUpdaterInstance::startServerUpdate);
        }));
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
