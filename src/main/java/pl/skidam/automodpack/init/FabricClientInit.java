package pl.skidam.automodpack.init;

/*? if fabric {*/
import net.fabricmc.api.ClientModInitializer;
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public class FabricClientInit implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        ScreenManager.INSTANCE = new ScreenImpl();
        ModPackets.registerC2SPackets();
        new AudioManager();
    }
}
/*?}*/
