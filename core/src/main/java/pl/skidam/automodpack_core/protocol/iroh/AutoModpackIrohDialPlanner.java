package pl.skidam.automodpack_core.protocol.iroh;

import dev.iroh.IrohNode;
import dev.iroh.IrohRemoteAddressBook;
import pl.skidam.automodpack_core.protocol.ModpackConnectionInfo;

import java.util.LinkedHashSet;

public final class AutoModpackIrohDialPlanner {
    private AutoModpackIrohDialPlanner() {
    }

    public static AutoModpackIrohDialPlan plan(ModpackConnectionInfo connectionInfo) {
        if (connectionInfo == null || !connectionInfo.hasEndpointId()) {
            throw new IllegalArgumentException("No iroh endpoint advertised by server");
        }

        byte[] remoteId = IrohIdentity.fromHex(connectionInfo.endpointId());
        LinkedHashSet<Long> customTransportIds = new LinkedHashSet<>();
        if (connectionInfo.hasRawTcpAddress()) {
            customTransportIds.add(IrohNode.RAW_TCP_TRANSPORT_ID);
        }

        return new AutoModpackIrohDialPlan(
            remoteId,
            IrohRemoteAddressBook.of(
                remoteId,
                connectionInfo.directIpAddresses() == null ? java.util.List.of() : connectionInfo.directIpAddresses(),
                customTransportIds
            ),
            connectionInfo.hasRawTcpAddress() ? connectionInfo.rawTcpAddress() : null
        );
    }
}
