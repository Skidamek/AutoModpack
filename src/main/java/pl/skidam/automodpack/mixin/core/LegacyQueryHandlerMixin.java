package pl.skidam.automodpack.mixin.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
/*? if <1.20.2 {*/
/*import net.minecraft.network.LegacyQueryHandler;
*//*?} else {*/
import net.minecraft.network.handler.LegacyQueryHandler;
/*?}*/
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack_core.netty.HttpServerHandler;

import static pl.skidam.automodpack_core.GlobalVariables.*;

@Mixin(value = LegacyQueryHandler.class, priority = 300)
public class LegacyQueryHandlerMixin {

    // Injects http handler
    @Inject(
            method = "channelRead",
            at = @At("HEAD"),
            cancellable = true
    )
    public void handle(ChannelHandlerContext context, Object msg, CallbackInfo ci) {
        if (!httpServer.isRunning()) return;

        HttpServerHandler handler = new HttpServerHandler();
        ByteBuf byteBuf = (ByteBuf) msg;
        if (handler.isAutoModpackRequest(byteBuf)) {
            // Cancel the legacy query request as it is http request
            // Pass to our http handler
            handler.channelRead(context, byteBuf);
            ci.cancel();
        }
    }
}
