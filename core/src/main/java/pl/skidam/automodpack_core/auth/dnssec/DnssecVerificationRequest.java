package pl.skidam.automodpack_core.auth.dnssec;

import java.net.InetSocketAddress;

public record DnssecVerificationRequest(
    InetSocketAddress minecraftServerAddress,
    InetSocketAddress rawTcpAddress,
    String endpointId
) {
}
