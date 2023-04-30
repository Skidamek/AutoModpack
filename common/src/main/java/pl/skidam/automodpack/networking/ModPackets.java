package pl.skidam.automodpack.networking;

import dev.architectury.injectables.annotations.ExpectPlatform;
import net.minecraft.util.Identifier;

import static pl.skidam.automodpack.StaticVariables.*;

public class ModPackets {
    public static final Identifier HANDSHAKE = new Identifier(MOD_ID, "handshake");
    public static final Identifier LINK = new Identifier(MOD_ID, "link");

    @ExpectPlatform
    public static void registerC2SPackets() {
        throw new AssertionError();
    }

    @ExpectPlatform
    public static void registerS2CPackets() {
        throw new AssertionError();
    }

}
