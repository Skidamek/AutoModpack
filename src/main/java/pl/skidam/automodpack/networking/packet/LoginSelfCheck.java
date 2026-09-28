package pl.skidam.automodpack.networking.packet;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;

import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;

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
			ConnectionStore.OriginConnection originConnection = ConnectionStore.connectionForOrigin(storage, attempt.origin());
			if (originConnection == null) return;
			String modpackId = originConnection.modpackId();
			if (new ClientGenerationStore(storage).isDetached(modpackId)) {
				LOGGER.info("Modpack {} is detached; the self-check leaves it alone", modpackId);
				return;
			}
			ConnectionJsons.ConnectionInfo stored = originConnection.connection();
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
}
