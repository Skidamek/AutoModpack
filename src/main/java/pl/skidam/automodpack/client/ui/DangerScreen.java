package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.client.gui.screens.Screen;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

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

        this.addRenderableWidget(
            buttonWidget(
                this.width / 2 - 115,
                this.height / 2 + 50,
                120,
                20,
                VersionedText.translatable("automodpack.danger.cancel"),
                button -> this.minecraft.setScreen(parent)
            )
        );

        this.addRenderableWidget(
            buttonWidget(
                this.width / 2 + 15,
                this.height / 2 + 50,
                120,
                20,
                VersionedText.translatable("automodpack.danger.confirm").withStyle(ChatFormatting.BOLD),
                button -> procced()
            )
        );
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        int lineHeight = 12; // Consistent line spacing

        // Title
        drawCenteredText(
            matrices,
            this.font,
            VersionedText.translatable("automodpack.danger").withStyle(ChatFormatting.BOLD),
            this.width / 2,
            this.height / 2 - 60,
            TextColors.WHITE
        );

        // Description line 1
        drawCenteredText(
            matrices,
            this.font,
            VersionedText.translatable("automodpack.danger.description"),
            this.width / 2,
            this.height / 2 - 60 + lineHeight * 3,
            TextColors.WHITE
        );

        // Description line 2
        drawCenteredText(
            matrices,
            this.font,
            VersionedText.translatable("automodpack.danger.secDescription"),
            this.width / 2,
            this.height / 2 - 60 + lineHeight * 4,
            TextColors.WHITE
        );

        // Description line 3
        drawCenteredText(
            matrices,
            this.font,
            VersionedText.translatable("automodpack.danger.thiDescription"),
            this.width / 2,
            this.height / 2 - 60 + lineHeight * 5,
            TextColors.WHITE
        );
    }

    public void procced() {
        try {
            Jsons.ModpackContent content = modpackUpdaterInstance.getServerModpackContent();

            if (content != null && content.groups != null && !content.groups.isEmpty()) {
                // Modpack is valid and has groups, pass to selection UI
                Util.backgroundExecutor().execute(() -> new ScreenManager().modpackSelection(this.parent, modpackUpdaterInstance, content));
                return;
            }
        } catch (Exception e) {
            Constants.LOGGER.error("Failed to load modpack content for selection screen", e);
        }

        Constants.LOGGER.error("Fallback?? Something went very wrong");

        // Fallback or empty pack - just start raw update
        Util.backgroundExecutor().execute(() -> modpackUpdaterInstance.startUpdate(modpackUpdaterInstance.getWholeModpackFileList()));
    }

    @Override
    public boolean onKeyPress(int keyCode, int scanCode, int modifiers) {
        if (keyCode == 257) { // Enter key (GLFW_KEY_ENTER = 257)
            procced();
            return true;
        }
        return super.onKeyPress(keyCode, scanCode, modifiers);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return true;
    }
}