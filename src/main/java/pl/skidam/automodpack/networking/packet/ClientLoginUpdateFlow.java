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
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.modpack.group.SelectionResolutionException;
import pl.skidam.automodpack_core.protocol.CertificatePinMismatchException;
import pl.skidam.automodpack_core.protocol.CertificateTrustCancelledException;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;
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
		return ModpackUtils.requestServerModpackContentAsync(storage, connectionInfo, secret, true).thenComposeAsync(manifestResult -> {
			if (!manifestResult.successful()) {
				disconnectImmediately(handler);
				presentManifestFailure(manifestResult);
				return CompletableFuture.completedFuture(LoginUpdateResponse.HOST_ERROR);
			}

			DownloadClient downloadClient = manifestResult.client();
			ClientSelectionStore selections = new ClientSelectionStore(storage.selectionFile());
			PackDocument record;
			SelectionIntent savedSelection;
			try {
				record = PackDocument.fromFields(manifestResult.content());
				savedSelection = selections.get(record.manifest().modpackId()).orElse(null);
			} catch (RuntimeException e) {
				downloadClient.close();
				presentFailure(e, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
				disconnectImmediately(handler);
				return CompletableFuture.completedFuture(LoginUpdateResponse.UPDATE_REQUIRED);
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
								continueReconcile(handler, connectionInfo, secret, storage, downloadClient, repaired, true, false);
							} catch (RuntimeException repairError) {
								if (repairCancelled.get()) return;
								downloadClient.close();
								presentFailure(repairError, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
							}
						});
					}, downloadClient::close);
					return CompletableFuture.completedFuture(LoginUpdateResponse.UPDATE_REQUIRED);
				}
				downloadClient.close();
				presentFailure(e, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
				disconnectImmediately(handler);
				return CompletableFuture.completedFuture(LoginUpdateResponse.UPDATE_REQUIRED);
			} catch (RuntimeException e) {
				downloadClient.close();
				presentFailure(e, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
				disconnectImmediately(handler);
				return CompletableFuture.completedFuture(LoginUpdateResponse.UPDATE_REQUIRED);
			}

			return continueReconcile(handler, connectionInfo, secret, storage, downloadClient, selectedTarget, false, false);
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

	private static boolean canRepair(GenerationJsons.HeadDocumentFields fields, SelectionIntent savedSelection) {
		try {
			PackDocument record = PackDocument.fromFields(fields);
			SelectedModpackTarget.prepareDefault(fields, ClientPlatform.effective(savedSelection));
			return true;
		} catch (RuntimeException ignored) {
			return false;
		}
	}

	private static CompletableFuture<LoginUpdateResponse> continueReconcile(ClientHandshakePacketListenerImpl handler, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret,
			ClientStorage storage, DownloadClient downloadClient, SelectedModpackTarget selectedTarget, boolean alreadyDisconnected, boolean originApproved) {
		ModpackJsons.ModpackContentFields serverModpackContent = selectedTarget.flatTarget();
		ConnectionJsons.ConnectionInfo stored = originApproved ? null : storedConnection(storage, serverModpackContent.modpackId);
		if (stored != null && stored.origin != null && !AddressHelpers.formatAddress(stored.origin).equals(AddressHelpers.formatAddress(connectionInfo.origin))) {
			return CompletableFuture.completedFuture(offerOriginChange(handler, connectionInfo, secret, storage, downloadClient, selectedTarget, alreadyDisconnected, stored));
		}
		try {
			ConnectionStore.saveConnection(storage, serverModpackContent.modpackId, connectionInfo);
			SecretsStore.saveClientSecret(storage, serverModpackContent.modpackId, connectionInfo.origin, secret);
		} catch (Exception e) {
			downloadClient.close();
			presentFailure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			if (!alreadyDisconnected) disconnectImmediately(handler);
			return CompletableFuture.completedFuture(LoginUpdateResponse.UPDATE_REQUIRED);
		}

		ModpackUpdater updater = new ModpackUpdater(selectedTarget, connectionInfo, secret, storage, downloadClient);
		try {
			ClientGenerationStore generations = new ClientGenerationStore(storage);
			if (generations.isDetached(serverModpackContent.modpackId)) {
				boolean headMatchesActive = generations.headMatchesActive(serverModpackContent.modpackId, serverModpackContent.contentToken);
				LOGGER.info("Modpack {} runs detached from the server head; asking the player before any sync", serverModpackContent.modpackId);
				return detachedJoin(handler, updater, selectedTarget, alreadyDisconnected, headMatchesActive);
			}
			ModpackUtils.UpdateCheckResult updateCheckResult = ModpackUtils.isUpdate(serverModpackContent, storage);
			ModpackUtils.reprotectActiveFiles(serverModpackContent, storage);
			if (!updater.requiresUpdateBeforeLogin(updateCheckResult)) {
				updater.close();
				if (alreadyDisconnected) ScreenImpl.multiplayer();
				ScreenImpl.updatePendingRestartToast();
				return CompletableFuture.completedFuture(alreadyDisconnected ? LoginUpdateResponse.UPDATE_REQUIRED : LoginUpdateResponse.CONTINUE);
			}
			if (!alreadyDisconnected) {
				LOGGER.info("Modpack update required; leaving the connecting screen");
				ScreenManager.waiting(updater::cancelFromPlayer);
				disconnectImmediately(handler);
			}
			updater.processModpackUpdate(true);
			return CompletableFuture.completedFuture(LoginUpdateResponse.UPDATE_REQUIRED);
		} catch (Exception e) {
			updater.close();
			presentFailure(e, "automodpack.error.update", FailureCategory.UPDATE);
			if (!alreadyDisconnected) disconnectImmediately(handler);
			return CompletableFuture.completedFuture(LoginUpdateResponse.UPDATE_REQUIRED);
		}
	}

	/**
	 * The detached join prompt: warn but allow, shown during the login phase before any disconnect. Nothing syncs here.
	 * Continue completes the login query so the vanilla join proceeds in-session with the local pack untouched; sync now
	 * disconnects and runs the reviewed update whose commit attaches the pack. The prompt shows on every detached join;
	 * {@code headMatchesActive} only picks the body, because equal tokens say nothing about locally changed files.
	 */
	private static CompletableFuture<LoginUpdateResponse> detachedJoin(ClientHandshakePacketListenerImpl handler, ModpackUpdater updater, SelectedModpackTarget selectedTarget,
			boolean alreadyDisconnected, boolean headMatchesActive) {
		CompletableFuture<LoginUpdateResponse> answered = new CompletableFuture<>();
		String modpackName = selectedTarget.manifest().modpackName();
		if (modpackName.isBlank()) modpackName = selectedTarget.flatTarget().modpackId;
		Runnable continueJoin = () -> DownloadClient.NET_EXECUTOR.execute(() -> {
			updater.close();
			answered.complete(alreadyDisconnected ? LoginUpdateResponse.UPDATE_REQUIRED : LoginUpdateResponse.CONTINUE);
		});
		Runnable syncNow = () -> DownloadClient.NET_EXECUTOR.execute(() -> {
			LOGGER.info("Attaching the detached modpack {} to the server head", selectedTarget.manifest().modpackId());
			if (!alreadyDisconnected) {
				ScreenManager.waiting(updater::cancelFromPlayer);
				disconnectImmediately(handler);
			}
			updater.attachAndSync();
			answered.complete(LoginUpdateResponse.UPDATE_REQUIRED);
		});
		ScreenManager.detachedJoin(modpackName, headMatchesActive, continueJoin, syncNow);
		return answered;
	}

	private static void disconnectImmediately(ClientHandshakePacketListenerImpl handler) {
		ClientLoginDisconnect.disconnect(handler);
	}

	private static ConnectionJsons.ConnectionInfo storedConnection(ClientStorage storage, String modpackId) {
		try {
			return ConnectionStore.getConnection(storage, modpackId);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Cannot read the stored connection for modpack {}", modpackId, e);
			return null;
		}
	}

	/** A different address serving an installed pack can be a migration or an impostor; the player decides once and the saved connection makes it stick. */
	private static LoginUpdateResponse offerOriginChange(ClientHandshakePacketListenerImpl handler, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret,
			ClientStorage storage, DownloadClient downloadClient, SelectedModpackTarget selectedTarget, boolean alreadyDisconnected, ConnectionJsons.ConnectionInfo stored) {
		if (!alreadyDisconnected) disconnectImmediately(handler);
		if (ScreenManager.getScreen().isEmpty()) {
			LOGGER.warn("No screen available, refusing the changed origin for modpack {}", selectedTarget.flatTarget().modpackId);
			downloadClient.close();
			return LoginUpdateResponse.UPDATE_REQUIRED;
		}
		String modpackName = selectedTarget.manifest().modpackName();
		if (modpackName.isBlank()) modpackName = selectedTarget.flatTarget().modpackId;
		ScreenManager.originChange(modpackName, AddressHelpers.formatAddress(stored.origin), AddressHelpers.formatAddress(connectionInfo.origin),
				() -> DownloadClient.NET_EXECUTOR.execute(() -> continueReconcile(handler, connectionInfo, secret, storage, downloadClient, selectedTarget, true, true)),
				() -> {
					downloadClient.close();
					ScreenImpl.multiplayer();
				});
		return LoginUpdateResponse.UPDATE_REQUIRED;
	}
}
