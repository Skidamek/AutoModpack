package pl.skidam.automodpack.networking.client;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.chat.Component;

import pl.skidam.automodpack.mixin.core.ClientConnectionAccessor;
import pl.skidam.automodpack.mixin.core.ClientLoginNetworkHandlerAccessor;
import pl.skidam.automodpack_core.screen.ScreenManager;

public final class ClientLoginDisconnect {
	private ClientLoginDisconnect() {}

	public static void disconnect(ClientHandshakePacketListenerImpl handler) {
		((IntentionalDisconnectControl) handler).automodpack$markIntentionalDisconnect();
		var connection = ((ClientLoginNetworkHandlerAccessor) handler).getConnection();
		((ClientConnectionAccessor) connection).getChannel().disconnect();
		// The connect screen and any login prompt above it died with the login; nothing may return to them.
		ScreenManager.discardReturnTarget();
	}

	/** A failure disconnect that shows its reason on the disconnect screen: the standard protocol path, without the intentional-disconnect screen suppression. */
	public static void disconnect(ClientHandshakePacketListenerImpl handler, Component reason) {
		((ClientLoginNetworkHandlerAccessor) handler).getConnection().disconnect(reason);
	}
}
