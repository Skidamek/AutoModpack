package pl.skidam.automodpack.networking.packet;

import java.util.Set;
import java.util.concurrent.CompletableFuture;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import io.netty.buffer.Unpooled;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;
import net.minecraft.network.FriendlyByteBuf;

import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack.networking.client.ClientLoginDisconnect;
import pl.skidam.automodpack.networking.content.HandshakePacket;
import pl.skidam.automodpack_core.Environment;
import pl.skidam.automodpack_core.client.SelfUpdater;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.SemanticVersion;

public class HandshakeC2SPacket {
	private static final Logger LOGGER = LogManager.getLogger(HandshakeC2SPacket.class);

	public static CompletableFuture<FriendlyByteBuf> receive(Minecraft client, ClientHandshakePacketListenerImpl handler, FriendlyByteBuf buf) {
		ModPackets.markLoginQueryArrived();
		FriendlyByteBuf outBuf = new FriendlyByteBuf(Unpooled.buffer());

		try {
			String serverResponse = buf.readUtf(Short.MAX_VALUE);
			HandshakePacket serverHandshakePacket = HandshakePacket.fromJson(serverResponse);

			String loader = Environment.current().loader();
			if (!serverHandshakePacket.loaders.contains(loader)) {
				LOGGER.warn("Loader mismatch. Server accepts: {}: Client: {}", serverHandshakePacket.loaders, loader);
				return CompletableFuture.completedFuture(outBuf);
			}

			updateIfNeededMod(handler, serverHandshakePacket.amVersion, serverHandshakePacket.mcVersion);

			HandshakePacket clientHandshakePacket = new HandshakePacket(Set.of(loader), Environment.current().modVersion(), Environment.current().mcVersion());
			outBuf.writeUtf(clientHandshakePacket.toJson(), Short.MAX_VALUE);

			return CompletableFuture.completedFuture(outBuf);
		} catch (Exception e) {
			LOGGER.error("The server handshake is unreadable; disconnecting instead of joining unenforced", e);
			ClientLoginDisconnect.disconnect(handler, VersionedText.literal("[AutoModpack] The server sent an unreadable handshake. Ask the server administrator to check the server and AutoModpack versions."));
			return CompletableFuture.completedFuture(outBuf);
		}
	}

	private static void updateIfNeededMod(ClientHandshakePacketListenerImpl handler, String serverAMVersion, String serverMCVersion) {
		ClientConfigJsons.ClientConfigFieldsV3 clientConfig = ClientStorage.current().clientConfig().current();
		if (clientConfig != null && !clientConfig.syncAutoModpackVersion) {
			LOGGER.warn("AutoModpack version syncing is disabled in client config. Cannot sync to server version: {}", serverAMVersion);
			return;
		}

		if (!serverMCVersion.equals(Environment.current().mcVersion())) return;

		if (Environment.current().modVersion().equals(serverAMVersion)) {
			LOGGER.debug("Versions match {}", Environment.current().modVersion());
			return;
		}
		LOGGER.debug("Versions mismatch. Server: {}: Client: {}", serverAMVersion, Environment.current().modVersion());

		ModrinthAPI automodpack = ModrinthAPI.getModSpecificVersion(SelfUpdater.AUTOMODPACK_ID, serverAMVersion, Environment.current().mcVersion());

		if (automodpack == null) {
			LOGGER.warn("Couldn't find {} version of automodpack for minecraft {} required by server", serverAMVersion, serverMCVersion);
			return;
		}

		SemanticVersion semver = SemanticVersion.parse(automodpack.fileVersion());

		if (SelfUpdater.validUpdate(semver, SemanticVersion.parse(Environment.current().modVersion()))) {
			LOGGER.info("Updating AutoModpack to {}", serverAMVersion);
			ScreenManager.waiting();
			ClientLoginDisconnect.disconnect(handler);
			SelfUpdater.installModVersion(automodpack);
		}
	}
}
