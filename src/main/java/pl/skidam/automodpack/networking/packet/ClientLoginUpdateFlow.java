package pl.skidam.automodpack.networking.packet;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.util.concurrent.CompletableFuture;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;

import pl.skidam.automodpack.networking.client.ClientLoginDisconnect;
import pl.skidam.automodpack.networking.content.LoginUpdateResponse;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Coordinates the client-side work that follows the login data query. */
final class ClientLoginUpdateFlow {
	private ClientLoginUpdateFlow() {}

	static CompletableFuture<LoginUpdateResponse> reconcile(ClientHandshakePacketListenerImpl handler, Jsons.ConnectionInfo connectionInfo,
			Secrets.Secret secret, ClientStorage storage) {
		return ModpackUtils.requestServerModpackContentAsync(storage, connectionInfo, secret, true).thenApplyAsync(manifestResult -> {
			if (manifestResult.state() == ModpackUtils.ManifestFetchState.OPERATION_FAILED) return LoginUpdateResponse.UPDATE_REQUIRED;
			if (!manifestResult.successful()) return LoginUpdateResponse.HOST_ERROR;

			DownloadClient downloadClient = manifestResult.client();
			SelectedModpackTarget selectedTarget;
			try {
				selectedTarget = SelectedModpackTarget.prepare(manifestResult.content(), new ClientSelectionStore(storage.selectionFile()), ClientPlatform.current());
			} catch (RuntimeException e) {
				downloadClient.close();
				LOGGER.error("Failed to resolve the server modpack catalogue and group selection", e);
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
				disconnectImmediately(handler);
				return LoginUpdateResponse.UPDATE_REQUIRED;
			}

			Jsons.ModpackContentFields serverModpackContent = selectedTarget.flatTarget();
			try {
				ConnectionStore.saveConnection(storage, serverModpackContent.modpackId, connectionInfo);
				SecretsStore.saveClientSecret(storage, serverModpackContent.modpackId, connectionInfo.origin, secret);
			} catch (Exception e) {
				downloadClient.close();
				LOGGER.error("Failed to persist client secret", e);
				new ScreenManager().error("automodpack.error.critical", "Failed to persist client secret", "automodpack.error.logs");
				disconnectImmediately(handler);
				return LoginUpdateResponse.UPDATE_REQUIRED;
			}

			ModpackUpdater updater = new ModpackUpdater(selectedTarget, connectionInfo, secret, storage, downloadClient);
			try {
				ModpackUtils.UpdateCheckResult updateCheckResult = ModpackUtils.isUpdate(serverModpackContent, storage);
				if (!updater.requiresUpdateBeforeLogin(updateCheckResult)) {
					updater.close();
					return LoginUpdateResponse.CONTINUE;
				}
				disconnectImmediately(handler);
				updater.processModpackUpdate(updateCheckResult);
				return LoginUpdateResponse.UPDATE_REQUIRED;
			} catch (Exception e) {
				updater.close();
				LOGGER.error("Failed to reconcile stable modpack installation", e);
				new ScreenManager().error("automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
				disconnectImmediately(handler);
				return LoginUpdateResponse.UPDATE_REQUIRED;
			}
		}, DownloadClient.NET_EXECUTOR).exceptionally(e -> {
			LOGGER.error("Error while handling data packet", e);
			return LoginUpdateResponse.HOST_ERROR;
		});
	}

	private static void disconnectImmediately(ClientHandshakePacketListenerImpl handler) {
		ClientLoginDisconnect.disconnect(handler);
	}
}
