package pl.skidam.automodpack.init;

/*? if neoforge {*/
/*import net.neoforged.bus.api.IEventBus;
import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public final class NeoForgeClientInit {
    private NeoForgeClientInit() {
    }

    public static void init(IEventBus eventBus) {
        ScreenManager.INSTANCE = new ScreenImpl();
        ModPackets.registerC2SPackets();
        new AudioManager(eventBus);
    }
}
*//*?}*/
