package pl.skidam.automodpack.client.ui;

import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.client.gui.screens.Screen;
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
        assert this.minecraft != null;

        this.addRenderableWidget(buttonWidget(this.width / 2 - 115, this.height / 2 + 50, 120, 20, VersionedText.translatable("automodpack.danger.cancel"), button -> {
            this.minecraft.setScreen(parent);
        }));

        this.addRenderableWidget(buttonWidget(this.width / 2 + 15, this.height / 2 + 50, 120, 20, VersionedText.translatable("automodpack.danger.confirm").withStyle(ChatFormatting.BOLD), button -> {
            Util.backgroundExecutor().execute(modpackUpdaterInstance::startUpdate);
        }));
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.danger").withStyle(ChatFormatting.BOLD), this.width / 2, this.height / 2 - 60, TextColors.WHITE);
        drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.danger.description"), this.width / 2, this.height / 2 - 35, TextColors.WHITE);
        drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.danger.secDescription"), this.width / 2, this.height / 2 - 25, TextColors.WHITE);
        drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.danger.thiDescription"), this.width / 2, this.height / 2 - 15, TextColors.WHITE);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
