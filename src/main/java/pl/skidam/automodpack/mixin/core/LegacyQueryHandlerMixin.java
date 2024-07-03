package pl.skidam.automodpack.mixin.core;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import net.minecraft.network.handler.LegacyQueryHandler;
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
        if (httpServer.isRunning()) {
            HttpServerHandler handler = new HttpServerHandler();

            ByteBuf byteBuf = (ByteBuf) msg;
            byteBuf.markReaderIndex();

            if (handler.isAutoModpackRequest(byteBuf)) {
                // Cancel the legacy query request as it is http request
                // Pass to our http handler
                ci.cancel();
                handler.channelRead(context, byteBuf);
                byteBuf.release();
            }

            byteBuf.resetReaderIndex();
        }
    }

}
