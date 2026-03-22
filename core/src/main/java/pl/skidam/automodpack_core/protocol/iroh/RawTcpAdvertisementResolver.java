package pl.skidam.automodpack_core.protocol.iroh;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.AddressHelpers;

import java.net.InetSocketAddress;

public final class RawTcpAdvertisementResolver {
    private RawTcpAdvertisementResolver() {
    }

    public static Resolution resolve(
        Jsons.ServerConfigFieldsV2 serverConfig,
        String requestedServerHost,
        Integer requestedServerPort,
        boolean rawTcpHostingAvailable
    ) {
        if (serverConfig == null || !rawTcpHostingAvailable) {
            return null;
        }

        String host = normalizeHost(serverConfig.addressToSend);
        String source = "explicit-config";
        if (host == null) {
            host = normalizeHost(requestedServerHost);
            source = host == null ? null : "client-handshake-host";
        }

        int port = serverConfig.portToSend;
        if (port <= 0) {
            int bindPort = serverConfig.bindPort;
            if (bindPort > 0) {
                port = bindPort;
                source = source == null ? null : source + "+bind-port";
            }
        }
        if (port <= 0 && requestedServerPort != null && requestedServerPort > 0) {
            port = requestedServerPort;
            source = source == null ? null : source + "+client-handshake-port";
        }

        if (host == null || port <= 0) {
            return null;
        }

        return new Resolution(AddressHelpers.format(host, port), source == null ? "derived" : source);
    }

    private static String normalizeHost(String host) {
        if (host == null) {
            return null;
        }
        String trimmed = host.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    public record Resolution(InetSocketAddress address, String source) {
    }
}
