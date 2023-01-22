package pl.skidam.automodpack.mixin;

import net.minecraftforge.network.HandshakeHandler;
import org.spongepowered.asm.mixin.Mixin;

@Mixin(HandshakeHandler.class)
public class HandshakeHandlerMixin {



//    @Overwrite
//    void handleServerModListOnClient(HandshakeMessages.S2CModList serverModList, Supplier<NetworkEvent.Context> c) {
//        c.get().getNetworkManager().channel().attr(NetworkConstantsMixin.FML_NETVERSION).set(NetworkConstants.NETVERSION);
//
//    }


    // NOT NEEDED
//    @Overwrite
//    static void registerHandshake(ClientConnection manager, NetworkDirection direction) {
//        AutoModpack.LOGGER.error("registerHandshake");
//
//        manager.channel().attr(AttributeKey.valueOf("fml:handshake")).compareAndSet(null, new LoginS2CPacket(AutoModpack.VERSION));
//    }
}
