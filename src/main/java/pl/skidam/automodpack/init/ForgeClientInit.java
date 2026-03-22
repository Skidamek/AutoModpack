package pl.skidam.automodpack.init;

/*? if forge {*/
/*import net.minecraftforge.eventbus.api.IEventBus;
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public final class ForgeClientInit {
    private ForgeClientInit() {
    }

    public static void init(IEventBus eventBus) {
        ScreenManager.INSTANCE = new ScreenImpl();
        ModPackets.registerC2SPackets();
        new AudioManager(eventBus);
    }
}
*//*?}*/
