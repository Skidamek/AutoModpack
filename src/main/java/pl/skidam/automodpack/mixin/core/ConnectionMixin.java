package pl.skidam.automodpack.mixin.core;

import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelPipeline;
import net.minecraft.network.Connection;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import pl.skidam.automodpack.networking.ConnectionIrohTunnelRegistry;
import pl.skidam.automodpack.networking.connection.AutoModpackConnectionInboundHandler;
import pl.skidam.automodpack.networking.connection.AutoModpackConnectionManager;
import pl.skidam.automodpack.networking.connection.AutoModpackConnectionOutboundBridgeHandler;
import pl.skidam.automodpack.networking.connection.AutoModpackConnectionTransportHolder;

import static pl.skidam.automodpack_core.Constants.LOGGER;

@Mixin(Connection.class)
public abstract class ConnectionMixin implements AutoModpackConnectionTransportHolder {
    @Unique
    private static final String[] automodpack$INBOUND_ANCHORS = {"decompress", "decoder", "inbound_config"};
    @Unique
    private static final String automodpack$OUTBOUND_ANCHOR = "prepender";

    @Unique
    private AutoModpackConnectionManager automodpack$connectionManager;

    /*? if >1.20.1 {*/
    @Inject(method = "configurePacketHandler", at = @At("TAIL"))
    private void automodpack$installConnectionTransport(ChannelPipeline pipeline, CallbackInfo ci) {
        automodpack$reinstallConnectionTransportHandlers(pipeline);
    }
    /*?} else {*/
    /*@Inject(method = "channelActive", at = @At("TAIL"))
    private void automodpack$installConnectionTransport(ChannelHandlerContext context, CallbackInfo ci) {
        automodpack$reinstallConnectionTransportHandlers(context.pipeline());
    }
    *//*?}*/

    @Inject(method = "setupCompression", at = @At("TAIL"))
    private void automodpack$reinstallConnectionTransportAfterCompression(int threshold, boolean rejectsBadPackets, CallbackInfo ci) {
        ChannelPipeline pipeline = ((ConnectionAccessor) (Object) this).automodpack$getChannel() == null
            ? null
            : ((ConnectionAccessor) (Object) this).automodpack$getChannel().pipeline();
        if (pipeline != null) {
            automodpack$reinstallConnectionTransportHandlers(pipeline);
        }
    }

    @Inject(method = "channelInactive", at = @At("HEAD"))
    private void automodpack$cleanupConnectionTransport(ChannelHandlerContext context, CallbackInfo ci) {
        automodpack$cleanup();
    }

    @Inject(method = "handleDisconnection", at = @At("HEAD"))
    private void automodpack$cleanupConnectionTransportOnDisconnect(CallbackInfo ci) {
        automodpack$cleanup();
    }

    @Override
    public AutoModpackConnectionManager automodpack$getConnectionManager() {
        return automodpack$connectionManager;
    }

    @Unique
    private void automodpack$cleanup() {
        Connection connection = (Connection) (Object) this;
        ConnectionIrohTunnelRegistry.removeClient(connection);
        ConnectionIrohTunnelRegistry.removeServer(connection);
        if (automodpack$connectionManager != null) {
            automodpack$connectionManager.close();
        }
    }

    @Unique
    private void automodpack$reinstallConnectionTransportHandlers(ChannelPipeline pipeline) {
        if (automodpack$connectionManager == null) {
            automodpack$connectionManager = new AutoModpackConnectionManager((Connection) (Object) this);
        }

        automodpack$reinstallInboundHandler(pipeline);
        automodpack$reinstallOutboundHandler(pipeline);
    }

    @Unique
    private void automodpack$reinstallInboundHandler(ChannelPipeline pipeline) {
        String inboundAnchor = automodpack$findExistingHandler(pipeline, automodpack$INBOUND_ANCHORS);
        if (inboundAnchor == null) {
            LOGGER.debug("Skipping AutoModpack inbound transport install because no inbound anchor is present");
            return;
        }

        if (pipeline.get(AutoModpackConnectionInboundHandler.NAME) != null) {
            pipeline.remove(AutoModpackConnectionInboundHandler.NAME);
        }
        pipeline.addBefore(inboundAnchor, AutoModpackConnectionInboundHandler.NAME, new AutoModpackConnectionInboundHandler(automodpack$connectionManager));
    }

    @Unique
    private void automodpack$reinstallOutboundHandler(ChannelPipeline pipeline) {
        if (pipeline.get(automodpack$OUTBOUND_ANCHOR) == null) {
            LOGGER.debug("Skipping AutoModpack outbound transport install because '{}' is missing", automodpack$OUTBOUND_ANCHOR);
            return;
        }

        if (pipeline.get(AutoModpackConnectionOutboundBridgeHandler.NAME) != null) {
            pipeline.remove(AutoModpackConnectionOutboundBridgeHandler.NAME);
        }
        pipeline.addAfter(
            automodpack$OUTBOUND_ANCHOR,
            AutoModpackConnectionOutboundBridgeHandler.NAME,
            new AutoModpackConnectionOutboundBridgeHandler(automodpack$connectionManager)
        );
    }

    @Unique
    private static String automodpack$findExistingHandler(ChannelPipeline pipeline, String[] candidates) {
        for (String candidate : candidates) {
            if (pipeline.get(candidate) != null) {
                return candidate;
            }
        }
        return null;
    }
}
