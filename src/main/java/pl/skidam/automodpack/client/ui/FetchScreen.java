package pl.skidam.automodpack.client.ui;

import net.minecraft.ChatFormatting;
import net.minecraft.client.gui.components.Button;
import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;
import pl.skidam.automodpack_loader_core.utils.FetchManager;

public class FetchScreen extends VersionedScreen {

    private Button cancelButton;
    private final FetchManager fetchManager;

    public FetchScreen(FetchManager fetchManager) {
        super(VersionedText.literal("FetchScreen"));
        this.fetchManager = fetchManager;
    }

    @Override
    protected void init() {
        super.init();

        initWidgets();

        this.addRenderableWidget(cancelButton);
    }

    private void initWidgets() {
        cancelButton = buttonWidget(this.width / 2 - 60, this.height / 2 + 80, 120, 20, VersionedText.translatable("automodpack.cancel"),
                button -> {
                    cancelButton.active = false;
                    cancelFetch();
                }
        );
    }

    private int getFetchesDone() {
        if (fetchManager == null) {
            return -1;
        }
        return fetchManager.fetchesDone;
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        if (fetchManager == null) {
            cancelButton.active = false;
        }

        // Fetching direct url's from Modrinth and CurseForge.
        drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.fetch").withStyle(ChatFormatting.BOLD), this.width / 2, this.height / 2 - 60, TextColors.WHITE);
        drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.wait"), this.width / 2, this.height / 2 - 48, TextColors.WHITE);
        drawCenteredTextWithShadow(matrices, this.font, VersionedText.translatable("automodpack.fetch.found", getFetchesDone()), this.width / 2, this.height / 2 - 30, TextColors.WHITE);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }

    public void cancelFetch() {
        try {
            if (fetchManager != null) {
                fetchManager.cancel();
            }

            new ScreenManager().title();

        } catch (Exception e) {
            e.printStackTrace();
        }
    }
}
