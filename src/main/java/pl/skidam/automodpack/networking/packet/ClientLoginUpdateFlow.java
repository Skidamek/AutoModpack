package pl.skidam.automodpack.networking.packet;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.util.concurrent.CompletableFuture;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.networking.client.ClientLoginDisconnect;
import pl.skidam.automodpack.networking.content.LoginUpdateResponse;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.modpack.group.SelectionResolutionException;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Coordinates the client-side work that follows the login data query. */
final class ClientLoginUpdateFlow {
	private ClientLoginUpdateFlow() {}

	static CompletableFuture<LoginUpdateResponse> reconcile(ClientHandshakePacketListenerImpl handler, ConnectionJsons.ConnectionInfo connectionInfo,
			Secrets.Secret secret, ClientStorage storage) {
		return ModpackUtils.requestServerModpackContentAsync(storage, connectionInfo, secret, true).thenApplyAsync(manifestResult -> {
			if (manifestResult.state() == ModpackUtils.ManifestFetchState.OPERATION_FAILED) return LoginUpdateResponse.UPDATE_REQUIRED;
			if (!manifestResult.successful()) return LoginUpdateResponse.HOST_ERROR;

			DownloadClient downloadClient = manifestResult.client();
			ClientSelectionStore selections = new ClientSelectionStore(storage.selectionFile());
			GenerationRecord record;
			SelectionIntent savedSelection;
			try {
				record = GenerationRecord.fromFields(manifestResult.content());
				savedSelection = selections.get(record.manifest().modpackId()).orElse(null);
			} catch (RuntimeException e) {
				downloadClient.close();
				new ScreenManager().error(e, "automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
				disconnectImmediately(handler);
				return LoginUpdateResponse.UPDATE_REQUIRED;
			}
			SelectedModpackTarget selectedTarget;
			try {
				selectedTarget = savedSelection == null
						? SelectedModpackTarget.prepareDefault(manifestResult.content(), ClientPlatform.current())
						: SelectedModpackTarget.prepare(manifestResult.content(), savedSelection, savedSelection, ClientPlatform.current());
			} catch (SelectionResolutionException e) {
				if (savedSelection != null && canRepair(manifestResult.content())) {
					disconnectImmediately(handler);
					ScreenImpl.repairSelection(manifestResult.content(), savedSelection, intent -> {
						new ScreenManager().waiting();
						DownloadClient.NET_EXECUTOR.execute(() -> {
							try {
								SelectedModpackTarget repaired = SelectedModpackTarget.prepare(manifestResult.content(), savedSelection, intent, ClientPlatform.current());
								continueReconcile(handler, connectionInfo, secret, storage, downloadClient, repaired, true);
							} catch (RuntimeException repairError) {
								downloadClient.close();
								new ScreenManager().error(repairError, "automodpack.error.critical", String.valueOf(repairError.getMessage()), "automodpack.error.logs");
							}
						});
					}, downloadClient::close);
					return LoginUpdateResponse.UPDATE_REQUIRED;
				}
				downloadClient.close();
				new ScreenManager().error(e, "automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
				disconnectImmediately(handler);
				return LoginUpdateResponse.UPDATE_REQUIRED;
			} catch (RuntimeException e) {
				downloadClient.close();
				new ScreenManager().error(e, "automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
				disconnectImmediately(handler);
				return LoginUpdateResponse.UPDATE_REQUIRED;
			}

			return continueReconcile(handler, connectionInfo, secret, storage, downloadClient, selectedTarget, false);
		}, DownloadClient.NET_EXECUTOR).exceptionally(e -> {
			LOGGER.error("Error while handling data packet", e);
			return LoginUpdateResponse.HOST_ERROR;
		});
	}

	private static boolean canRepair(ModpackJsons.CompleteModpackContentFields fields) {
		try {
			GenerationRecord record = GenerationRecord.fromFields(fields);
			SelectedModpackTarget.prepareDefault(fields, ClientPlatform.current());
			return true;
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	private static LoginUpdateResponse continueReconcile(ClientHandshakePacketListenerImpl handler, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret,
			ClientStorage storage, DownloadClient downloadClient, SelectedModpackTarget selectedTarget, boolean alreadyDisconnected) {
		ModpackJsons.ModpackContentFields serverModpackContent = selectedTarget.flatTarget();
		try {
			ConnectionStore.saveConnection(storage, serverModpackContent.modpackId, connectionInfo);
			SecretsStore.saveClientSecret(storage, serverModpackContent.modpackId, connectionInfo.origin, secret);
		} catch (Exception e) {
			downloadClient.close();
			new ScreenManager().error(e, "automodpack.error.critical", "Failed to persist client secret", "automodpack.error.logs");
			if (!alreadyDisconnected) disconnectImmediately(handler);
			return LoginUpdateResponse.UPDATE_REQUIRED;
		}

		ModpackUpdater updater = new ModpackUpdater(selectedTarget, connectionInfo, secret, storage, downloadClient);
		try {
			ModpackUtils.UpdateCheckResult updateCheckResult = ModpackUtils.isUpdate(serverModpackContent, storage);
			if (!updater.requiresUpdateBeforeLogin(updateCheckResult)) {
				updater.close();
				if (alreadyDisconnected) ScreenImpl.multiplayer();
				return alreadyDisconnected ? LoginUpdateResponse.UPDATE_REQUIRED : LoginUpdateResponse.CONTINUE;
			}
			if (!alreadyDisconnected) disconnectImmediately(handler);
			updater.processModpackUpdate(updateCheckResult);
			return LoginUpdateResponse.UPDATE_REQUIRED;
		} catch (Exception e) {
			updater.close();
			new ScreenManager().error(e, "automodpack.error.critical", String.valueOf(e.getMessage()), "automodpack.error.logs");
			if (!alreadyDisconnected) disconnectImmediately(handler);
			return LoginUpdateResponse.UPDATE_REQUIRED;
		}
	}

	private static void disconnectImmediately(ClientHandshakePacketListenerImpl handler) {
		ClientLoginDisconnect.disconnect(handler);
	}
}
