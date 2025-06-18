package pl.skidam.automodpack.client.ui;

import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.client.audio.AudioManager;

import java.nio.file.Path;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.utils.UpdateType;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public class RestartScreen extends VersionedScreen {
    private final Path modpackDir;
    private final UpdateType updateType;
    private final Changelogs changelogs;
    private static ButtonWidget cancelButton;
    private static ButtonWidget restartButton;
    private static ButtonWidget changelogsButton;

    public RestartScreen(Path modpackDir, UpdateType updateType, Changelogs changelogs) {
        super(VersionedText.literal("RestartScreen"));
        this.modpackDir = modpackDir;
        this.updateType = updateType;
        this.changelogs = changelogs;

        if (AudioManager.isMusicPlaying()) {
            AudioManager.stopMusic();
        }
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addDrawableChild(cancelButton);
        this.addDrawableChild(restartButton);
        this.addDrawableChild(changelogsButton);

        if (changelogs == null || changelogs.changesAddedList.isEmpty() && changelogs.changesDeletedList.isEmpty()) {
            changelogsButton.active = false;
        }
    }

    public void initWidgets() {
        assert this.client != null;
        cancelButton = buttonWidget(this.width / 2 - 155, this.height / 2 + 50, 150, 20, VersionedText.translatable("automodpack.restart.cancel"), button -> {
            this.client.setScreen(null);
        });

        restartButton = buttonWidget(this.width / 2 + 5, this.height / 2 + 50, 150, 20, VersionedText.translatable("automodpack.restart.confirm").formatted(Formatting.BOLD), button -> {
            System.exit(0);
        });

        changelogsButton = buttonWidget(this.width / 2 - 75, this.height / 2 + 75, 150, 20, VersionedText.translatable("automodpack.changelog.view"), button -> {
            new ScreenManager().changelog(this, modpackDir, changelogs);
        });
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.restart." + updateType.toString()).formatted(Formatting.BOLD), this.width / 2, this.height / 2 - 60, TextColors.WHITE);
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.restart.description"), this.width / 2, this.height / 2 - 35, TextColors.WHITE);
        drawCenteredTextWithShadow(matrices, this.textRenderer, VersionedText.translatable("automodpack.restart.secDescription"), this.width / 2, this.height / 2 - 25, TextColors.WHITE);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}