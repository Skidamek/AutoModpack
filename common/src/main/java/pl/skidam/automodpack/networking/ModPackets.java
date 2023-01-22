package pl.skidam.automodpack.networking;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.util.Identifier;
import pl.skidam.automodpack.AutoModpack;

public class ModPackets {
    public static final Identifier HANDSHAKE = new Identifier(AutoModpack.MOD_ID, "handshake");
    public static final Identifier LINK = new Identifier(AutoModpack.MOD_ID, "link");
    public static final Identifier DISCONNECT = new Identifier(AutoModpack.MOD_ID, "disconnect");

    @ExpectPlatform
    public static void registerC2SPackets() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void registerS2CPackets() {
        throw new AssertionError();
    }

}
