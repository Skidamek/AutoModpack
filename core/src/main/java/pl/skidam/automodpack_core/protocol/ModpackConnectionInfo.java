package pl.skidam.automodpack_core.protocol;

import pl.skidam.automodpack_core.config.Jsons;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

public record ModpackConnectionInfo(
    InetSocketAddress minecraftServerAddress,
    String endpointId,
    List<InetSocketAddress> directIpAddresses,
    InetSocketAddress rawTcpAddress,
    boolean shareMinecraftConnection
) {
    public ModpackConnectionInfo {
        directIpAddresses = directIpAddresses == null ? List.of() : List.copyOf(directIpAddresses);
    }

    public static ModpackConnectionInfo fromPersisted(Jsons.PersistedModpackConnection connection) {
        if (connection == null || connection.lastSuccessfulAddressBook == null) {
            return null;
        }

        Jsons.PersistedIrohAddressBook addressBook = connection.lastSuccessfulAddressBook;
        return new ModpackConnectionInfo(
            connection.minecraftServerAddress,
            addressBook.endpointId,
            List.of(),
            addressBook.rawTcp,
            false
        );
    }

    public Jsons.PersistedModpackConnection toPersistedModpackConnection() {
        if (minecraftServerAddress == null || !hasEndpointId()) {
            return null;
        }

        return new Jsons.PersistedModpackConnection(
            minecraftServerAddress,
            new Jsons.PersistedIrohAddressBook(
                endpointId,
                rawTcpAddress,
                List.of(),
                System.currentTimeMillis()
            )
        );
    }

    public boolean hasEndpointId() {
        return endpointId != null && !endpointId.isBlank();
    }

    public boolean hasRawTcpAddress() {
        return rawTcpAddress != null
            && !rawTcpAddress.getHostString().isBlank()
            && rawTcpAddress.getPort() > 0;
    }

    public boolean hasDirectIpAddresses() {
        return directIpAddresses != null && !directIpAddresses.isEmpty();
    }

    public boolean isUsable() {
        return minecraftServerAddress != null
            && !minecraftServerAddress.getHostString().isBlank()
            && hasEndpointId()
            && (hasRawTcpAddress() || hasDirectIpAddresses() || shareMinecraftConnection);
    }

    public String describeTarget() {
        if (hasRawTcpAddress()) {
            return rawTcpAddress.toString();
        }
        if (minecraftServerAddress != null) {
            return minecraftServerAddress.toString();
        }
        if (hasEndpointId()) {
            return "iroh:" + endpointId;
        }
        return "<unknown>";
    }
}
