package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.mcholepunch.HolepunchRoute;

class EndpointRouteTest {

	@Test
	void directRouteKeepsIdentityAndResolvesTheTransportAddress() throws Exception {
		ConnectionJsons.ConnectionInfo connection = new ConnectionJsons.ConnectionInfo(AddressHelpers.parseOrigin("play.example.com:25565"),
				AddressHelpers.parseEndpoint("127.0.0.1:25567"), ModpackConnectionMode.MAGIC, null);

		EndpointRoute route = LaneConnector.resolveRouteAsync(connection).get(5, TimeUnit.SECONDS);

		assertEquals("127.0.0.1", route.identityHost());
		assertEquals(25567, route.identityPort());
		assertEquals(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 25567), route.transportAddress());
	}

	@Test
	void holepunchRouteKeepsIdentityAndTransportSeparate() throws Exception {
		HolepunchRoute holepunch = HolepunchRoute.at("play.example.com", 25565, new InetSocketAddress(InetAddress.getByName("203.0.113.8"), 25565));

		EndpointRoute route = EndpointRoute.from(holepunch);

		assertEquals("play.example.com", route.identityHost());
		assertEquals(25565, route.identityPort());
		assertEquals("203.0.113.8", route.transportAddress().getAddress().getHostAddress());
		assertEquals(holepunch, route.holepunchRoute());
	}
}
