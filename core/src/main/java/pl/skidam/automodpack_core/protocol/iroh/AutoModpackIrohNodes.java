package pl.skidam.automodpack_core.protocol.iroh;

import dev.iroh.IrohNode;
import dev.iroh.JavaTransportDefinition;

import java.io.IOException;

public final class AutoModpackIrohNodes {
    private static final long MINECRAFT_TUNNEL_RTT_BIAS_MS = 750L;

    private AutoModpackIrohNodes() {
    }

    public static IrohNode createDownloadNode() throws IOException {
        return baseBuilder().build();
    }

    public static IrohNode createTunnelNode() throws IOException {
        return baseBuilder()
            .transport(JavaTransportDefinition.of(IrohNode.MINECRAFT_CONNECTION_TRANSPORT_ID, MINECRAFT_TUNNEL_RTT_BIAS_MS))
            .build();
    }

    private static IrohNode.Builder baseBuilder() throws IOException {
        return IrohNode.builder()
            .secretKey(IrohIdentity.loadOrCreateSecret(IrohTransportSupport.IROH_KEY_FILE))
            .relayMode(IrohNode.RelayMode.DISABLED)
            .ipTransports(true)
            .rttBiasMs(IrohTransportSupport.RAW_TCP_RTT_BIAS_MS)
            .alpn(IrohTransportSupport.ALPN);
    }
}
