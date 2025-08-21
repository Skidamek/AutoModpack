package pl.skidam.automodpack.client.ui;

import java.util.List;

import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack.client.audio.AudioManager;

import pl.skidam.automodpack.client.ui.versioned.VersionedMatrices;
import pl.skidam.automodpack.client.ui.versioned.VersionedScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.VersionedUtil;
import pl.skidam.automodpack_loader_core.utils.SelectionManager;

import static pl.skidam.automodpack_core.GlobalVariables.serverConfig;

public class DownloadSelectionScreen extends VersionedScreen {
    private final VersionedScreen parent;
    private final ModpackUpdater modpackUpdaterInstance;

    public DownloadSelectionScreen(VersionedScreen parent, ModpackUpdater modpackUpdaterInstance) {
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
        assert this.getClient() != null;

        /*? if >=1.19.3 {*/
        this.addDrawableChild(buttonWidget(this.width / 2, this.height / 2 + 150, 120, 20, VersionedText.translatable("automodpack.ds.cancel"), button -> {
            this.getClient().setScreen(this.parent);
        }));
        /*?} else {*/
        this.addButton(buttonWidget(this.width / 2, this.height / 2 + 150, 120, 20, VersionedText.translatable("automodpack.ds.cancel"), button -> {
            this.getClient().setScreen(this.parent);
        }));
        /*?}*/

        //buttons from Selectionmanager
        String currentSelected = SelectionManager.getSelectedPack();
        List<String> modpacks = SelectionManager.getModpackFolders();

        //dynamical how much buttons there
        int dynamicY = this.height / 2 - (modpacks.size() * 15);
        int i=0;

        for (String modpack : modpacks) {
            // between buttons
            int y = dynamicY + (i * 25);

            var displayText = modpack.equalsIgnoreCase(currentSelected)
                    ? VersionedText.green(modpack)
                    : VersionedText.bold(modpack);

            /*? if >=1.19.3 {*/
            this.addDrawableChild(buttonWidget(this.width / 2, y, 140, 20, displayText, button -> {
                //select and start
                SelectionManager.setSelectedPack(modpack);
                VersionedUtil.getMainWorkerExecutor().execute(modpackUpdaterInstance::startUpdate);
            }));
            /*?} else {*/
            this.addButton(buttonWidget(this.width / 2, y, 140, 20, displayText, button -> {
                //select and start
                SelectionManager.setSelectedPack(modpack);
                VersionedUtil.getMainWorkerExecutor().execute(modpackUpdaterInstance::startUpdate);
            }));
            /*?}*/
            i++;
        }

        //Full Serverpack Button if Modpack has permission from server
        if (serverConfig != null && serverConfig.enableFullServerPack) {
            /*? if >=1.19.3 {*/
            this.addDrawableChild(buttonWidget(this.width / 2, this.height / 2 + 175, 160, 20, VersionedText.red(VersionedText.translatable("automodpack.ds.fullserverpack").getString()), button -> {
                SelectionManager.setSelectedPack("fullserver");
                VersionedUtil.getMainWorkerExecutor().execute(modpackUpdaterInstance::startUpdate);
            }));
            /*?} else {*/
            this.addButton(buttonWidget(this.width / 2, this.height / 2 + 175, 160, 20, VersionedText.red(VersionedText.translatable("automodpack.ds.fullserverpack").getString()), button -> {
                SelectionManager.setSelectedPack("fullserver");
                VersionedUtil.getMainWorkerExecutor().execute(modpackUpdaterInstance::startUpdate);
            }));
            /*?}*/
        }
    }

    @Override
    public void versionedRender(VersionedMatrices matrices, int mouseX, int mouseY, float delta) {
        drawCenteredTextWithShadow(matrices, this.getTextRenderer(), VersionedText.translatable("automodpack.ds.selected", VersionedText.green(VersionedText.bold(SelectionManager.getSelectedPack()).getString())), this.width / 2, this.height / 2 - 15, 0xAAAAAA);
        drawCenteredTextWithShadow(matrices, this.getTextRenderer(), VersionedText.bold(VersionedText.translatable("automodpack.ds").getString()), this.width / 2, this.height / 2 - 60, 16777215);
        drawCenteredTextWithShadow(matrices, this.getTextRenderer(), VersionedText.translatable("automodpack.ds.description"), this.width / 2, this.height / 2 - 35, 16777215);
        drawCenteredTextWithShadow(matrices, this.getTextRenderer(), VersionedText.translatable("automodpack.ds.secDescription"), this.width / 2, this.height / 2 - 25, 16777215);
    }

    @Override
    public boolean shouldCloseOnEsc() {
        return false;
    }
}