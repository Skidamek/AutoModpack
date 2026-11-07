package pl.skidam.automodpack_core.protocol;

import java.net.InetSocketAddress;
import java.util.Objects;

import pl.skidam.mcholepunch.HolepunchRoute;

/**
 * One resolved way onto an endpoint. The identity host and port are what the peer sees in the Minecraft handshake,
 * TLS SNI, and the HTTP Host header. The transport address is the socket this client dials. A login that already
 * reached a concrete IP supplies that address as the transport, while a stored or hostname-routed connection resolves
 * it normally.
 */
record EndpointRoute(String identityHost, int identityPort, InetSocketAddress transportAddress) {
	EndpointRoute {
		Objects.requireNonNull(identityHost, "identityHost");
		if (identityHost.isBlank()) throw new IllegalArgumentException("identityHost must not be blank");
		if (identityPort < 1 || identityPort > 65535) throw new IllegalArgumentException("identityPort out of range");
		Objects.requireNonNull(transportAddress, "transportAddress");
		if (transportAddress.isUnresolved()) throw new IllegalArgumentException("transportAddress must be resolved");
	}

	static EndpointRoute from(HolepunchRoute route) {
		return new EndpointRoute(route.handshakeHost(), route.handshakePort(), route.transportAddress());
	}

	HolepunchRoute holepunchRoute() {
		return HolepunchRoute.at(identityHost, identityPort, transportAddress);
	}
}
