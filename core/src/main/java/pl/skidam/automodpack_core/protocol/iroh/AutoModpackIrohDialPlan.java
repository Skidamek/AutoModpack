package pl.skidam.automodpack_core.protocol.iroh;

import dev.iroh.IrohRemoteAddressBook;

import java.net.InetSocketAddress;

public record AutoModpackIrohDialPlan(
    byte[] remoteId,
    IrohRemoteAddressBook addressBook,
    InetSocketAddress rawBootstrapAddress
) {
    public boolean hasRawBootstrapAddress() {
        return rawBootstrapAddress != null
            && !rawBootstrapAddress.getHostString().isBlank()
            && rawBootstrapAddress.getPort() > 0;
    }
}
