package pl.skidam.automodpack.networking.packet;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.net.InetSocketAddress;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;

import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;

/** Join-time fallback for AutoModpack server queries a proxy swallowed: the client self-checks against its stored connection to this origin. */
public final class LoginSelfCheck {
	private LoginSelfCheck() {}

	public static void maybeRun(ClientHandshakePacketListenerImpl handler) {
		ModPackets.ConnectionAttempt attempt = ModPackets.getConnectionAttempt();
		if (attempt == null) return;
		ModpackUpdater.executor().execute(() -> run(handler, attempt));
	}

	private static void run(ClientHandshakePacketListenerImpl handler, ModPackets.ConnectionAttempt attempt) {
		try {
			ClientStorage storage = ClientStorage.open(GameDirectory.current());
			if (!ConnectionStore.hasOriginConnection(storage, attempt.origin())) return;
			String modpackId = modpackIdForOrigin(storage, attempt.origin());
			if (modpackId == null) return;
			if (new ClientGenerationStore(storage).isDetached(modpackId)) {
				LOGGER.info("Modpack {} is detached; the self-check leaves it alone", modpackId);
				return;
			}
			ConnectionJsons.ConnectionInfo stored = ConnectionStore.getConnection(storage, modpackId);
			ConnectionJsons.ConnectionInfo connectionInfo = new ConnectionJsons.ConnectionInfo(attempt.origin(), stored.endpoint, stored.connectionMode, attempt.expectedFingerprint());
			Secrets.Secret secret = ConnectionStore.getClientSecret(storage, modpackId, attempt.origin());
			LOGGER.info("AutoModpack server queries did not arrive this join; self-checking modpack {} against the stored connection", modpackId);
			ClientLoginUpdateFlow.reconcile(handler, connectionInfo, secret, storage, false, true).whenComplete((response, error) -> {
				if (error != null) LOGGER.warn("AutoModpack self-check for modpack {} failed", modpackId, error);
				else LOGGER.info("AutoModpack self-check for modpack {} finished: {}", modpackId, response);
			});
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("AutoModpack self-check skipped; cannot read the stored connection state", e);
		}
	}

	private static String modpackIdForOrigin(ClientStorage storage, InetSocketAddress origin) throws IOException {
		String selected = storage.selectedModpackId();
		if (!selected.isBlank() && namesOrigin(storage, selected, origin)) return selected;
		for (String modpackId : new ClientGenerationStore(storage).installedPackIds()) {
			if (namesOrigin(storage, modpackId, origin)) return modpackId;
		}
		return null;
	}

	private static boolean namesOrigin(ClientStorage storage, String modpackId, InetSocketAddress origin) {
		try {
			ConnectionJsons.ConnectionInfo connection = ConnectionStore.getConnection(storage, modpackId);
			return connection != null && connection.origin != null && AddressHelpers.formatAddress(connection.origin).equals(AddressHelpers.formatAddress(origin));
		} catch (IOException | RuntimeException e) {
			LOGGER.debug("Cannot read the connection record of modpack {}; it does not count as synced here", modpackId, e);
			return false;
		}
	}
}
