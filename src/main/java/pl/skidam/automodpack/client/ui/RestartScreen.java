package pl.skidam.automodpack.client.ui;

import java.nio.file.Path;
import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public class RestartScreen extends VersionedScreen {

    private final Path modpackDir;
    private final UpdateType updateType;
    private final Changelogs changelogs;
    private static Button cancelButton;
    private static Button restartButton;
    private static Button changelogsButton;

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

        this.addRenderableWidget(cancelButton);
        this.addRenderableWidget(restartButton);
        this.addRenderableWidget(changelogsButton);

        if (changelogs == null || (changelogs.changesAddedList.isEmpty() && changelogs.changesDeletedList.isEmpty())) {
            changelogsButton.active = false;
        }
    }

    public void initWidgets() {
        assert this.minecraft != null;

        cancelButton = buttonWidget(
            this.width / 2 - 155,
            this.height / 2 + 50,
            150,
            20,
            VersionedText.translatable("automodpack.restart.cancel"),
            button -> {
                this.minecraft.setScreen(null);
            }
        );

        restartButton = buttonWidget(
            this.width / 2 + 5,
            this.height / 2 + 50,
            150,
            20,
            VersionedText.translatable("automodpack.restart.confirm").withStyle(
                ChatFormatting.BOLD
            ),
            button -> {
                minecraft.stop();
            }
        );

        changelogsButton = buttonWidget(
            this.width / 2 - 75,
            this.height / 2 + 75,
            150,
            20,
            VersionedText.translatable("automodpack.changelog.view"),
            button -> {
                new ScreenManager().changelog(this, modpackDir, changelogs);
            }
        );
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        int lineHeight = 12; // Consistent line spacing

        // Title
        drawCenteredTextWithShadow(matrices, this.font,
            VersionedText.translatable("automodpack.restart." + updateType.toString()).withStyle(ChatFormatting.BOLD),
            this.width / 2,
            this.height / 2 - 60,
            TextColors.WHITE
        );

        // Description line 1
        drawCenteredTextWithShadow(
            matrices,
            this.font,
            VersionedText.translatable("automodpack.restart.description"),
            this.width / 2,
            this.height / 2 - 60 + lineHeight * 3,
            TextColors.WHITE
        );

        // Description line 2
        drawCenteredTextWithShadow(
            matrices,
            this.font,
            VersionedText.translatable("automodpack.restart.secDescription"),
            this.width / 2,
            this.height / 2 - 60 + lineHeight * 4,
            TextColors.WHITE
        );
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}
