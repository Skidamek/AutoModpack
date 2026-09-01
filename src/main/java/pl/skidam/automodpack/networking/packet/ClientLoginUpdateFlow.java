package pl.skidam.automodpack.networking.packet;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

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
import pl.skidam.automodpack_core.protocol.CertificatePinMismatchException;
import pl.skidam.automodpack_core.protocol.CertificateTrustCancelledException;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.screen.FailureCategory;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

/** Coordinates the client-side work that follows the login data query. */
final class ClientLoginUpdateFlow {
	private ClientLoginUpdateFlow() {}

	static CompletableFuture<LoginUpdateResponse> reconcile(ClientHandshakePacketListenerImpl handler, ConnectionJsons.ConnectionInfo connectionInfo,
			Secrets.Secret secret, ClientStorage storage) {
		return ModpackUtils.requestServerModpackContentAsync(storage, connectionInfo, secret, true).thenApplyAsync(manifestResult -> {
			if (!manifestResult.successful()) {
				disconnectImmediately(handler);
				presentManifestFailure(manifestResult);
				return LoginUpdateResponse.HOST_ERROR;
			}

			DownloadClient downloadClient = manifestResult.client();
			ClientSelectionStore selections = new ClientSelectionStore(storage.selectionFile());
			GenerationRecord record;
			SelectionIntent savedSelection;
			try {
				record = GenerationRecord.fromFields(manifestResult.content());
				savedSelection = selections.get(record.manifest().modpackId()).orElse(null);
			} catch (RuntimeException e) {
				downloadClient.close();
				presentFailure(e, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
				disconnectImmediately(handler);
				return LoginUpdateResponse.UPDATE_REQUIRED;
			}
			SelectedModpackTarget selectedTarget;
			try {
				selectedTarget = savedSelection == null
						? SelectedModpackTarget.prepareDefault(manifestResult.content(), ClientPlatform.effective(savedSelection))
						: SelectedModpackTarget.prepare(manifestResult.content(), savedSelection, savedSelection, ClientPlatform.effective(savedSelection));
			} catch (SelectionResolutionException e) {
				if (savedSelection != null && canRepair(manifestResult.content(), savedSelection)) {
					disconnectImmediately(handler);
					AtomicBoolean repairCancelled = new AtomicBoolean();
					ScreenImpl.repairSelection(manifestResult.content(), savedSelection, intent -> {
						ScreenManager.waiting(() -> {
							repairCancelled.set(true);
							downloadClient.close();
						});
						DownloadClient.NET_EXECUTOR.execute(() -> {
							if (repairCancelled.get()) return;
							try {
								SelectedModpackTarget repaired = SelectedModpackTarget.prepare(manifestResult.content(), savedSelection, intent, ClientPlatform.effective(intent));
								continueReconcile(handler, connectionInfo, secret, storage, downloadClient, repaired, true);
							} catch (RuntimeException repairError) {
								if (repairCancelled.get()) return;
								downloadClient.close();
								presentFailure(repairError, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
							}
						});
					}, downloadClient::close);
					return LoginUpdateResponse.UPDATE_REQUIRED;
				}
				downloadClient.close();
				presentFailure(e, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
				disconnectImmediately(handler);
				return LoginUpdateResponse.UPDATE_REQUIRED;
			} catch (RuntimeException e) {
				downloadClient.close();
				presentFailure(e, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
				disconnectImmediately(handler);
				return LoginUpdateResponse.UPDATE_REQUIRED;
			}

			return continueReconcile(handler, connectionInfo, secret, storage, downloadClient, selectedTarget, false);
		}, DownloadClient.NET_EXECUTOR).exceptionally(e -> {
			disconnectImmediately(handler);
			Throwable failure = DownloadClient.unwrap(e);
			if (DownloadClient.findCause(failure, CertificateTrustCancelledException.class) == null) {
				presentFailure(failure, "automodpack.error.connection", FailureCategory.CONNECTION);
			}
			return LoginUpdateResponse.HOST_ERROR;
		});
	}

	private static void presentManifestFailure(ModpackUtils.ManifestFetchResult result) {
		Throwable failure = result.failure() == null ? new IOException("Modpack manifest fetch returned no failure cause") : result.failure();
		if (DownloadClient.findCause(failure, CertificateTrustCancelledException.class) != null) return;
		CertificatePinMismatchException mismatch = DownloadClient.findCause(failure, CertificatePinMismatchException.class);
		if (mismatch != null) {
			FailureRequest request = FailureRequest.of(failure, "automodpack.pin.mismatch", FailureCategory.SECURITY, FailureDestination.MULTIPLAYER, null)
					.withDiagnosticDetails("Origin: " + mismatch.getOrigin(), "Expected fingerprint: " + mismatch.getExpectedFingerprint(),
							"Presented fingerprint: " + mismatch.getPresentedFingerprint());
			ScreenManager.failure(request);
		} else if (result.state() == ModpackUtils.ManifestFetchState.OPERATION_FAILED) {
			presentFailure(failure, "automodpack.error.hostContent", FailureCategory.HOST);
		} else {
			presentFailure(failure, "automodpack.error.connection", FailureCategory.CONNECTION);
		}
	}

	private static void presentFailure(Throwable failure, String messageKey, FailureCategory category) {
		ScreenManager.failure(FailureRequest.of(failure, messageKey, category, FailureDestination.MULTIPLAYER, null));
	}

	private static boolean canRepair(ModpackJsons.CompleteModpackContentFields fields, SelectionIntent savedSelection) {
		try {
			GenerationRecord record = GenerationRecord.fromFields(fields);
			SelectedModpackTarget.prepareDefault(fields, ClientPlatform.effective(savedSelection));
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
			presentFailure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			if (!alreadyDisconnected) disconnectImmediately(handler);
			return LoginUpdateResponse.UPDATE_REQUIRED;
		}

		ModpackUpdater updater = new ModpackUpdater(selectedTarget, connectionInfo, secret, storage, downloadClient);
		try {
			ModpackUtils.UpdateCheckResult updateCheckResult = ModpackUtils.isUpdate(serverModpackContent, storage);
			if (!updater.requiresUpdateBeforeLogin(updateCheckResult)) {
				updater.close();
				if (alreadyDisconnected) ScreenImpl.multiplayer();
				ScreenImpl.updatePendingRestartToast();
				return alreadyDisconnected ? LoginUpdateResponse.UPDATE_REQUIRED : LoginUpdateResponse.CONTINUE;
			}
			if (!alreadyDisconnected) {
				LOGGER.info("Modpack update required; leaving the connecting screen");
				ScreenManager.waiting(updater::cancelFromPlayer);
				disconnectImmediately(handler);
			}
			updater.processModpackUpdate(updateCheckResult, true);
			return LoginUpdateResponse.UPDATE_REQUIRED;
		} catch (Exception e) {
			updater.close();
			presentFailure(e, "automodpack.error.update", FailureCategory.UPDATE);
			if (!alreadyDisconnected) disconnectImmediately(handler);
			return LoginUpdateResponse.UPDATE_REQUIRED;
		}
	}

	private static void disconnectImmediately(ClientHandshakePacketListenerImpl handler) {
		ClientLoginDisconnect.disconnect(handler);
	}
}
