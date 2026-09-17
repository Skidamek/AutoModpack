package pl.skidam.automodpack.networking.packet;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.clientConfig;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.client.multiplayer.ClientHandshakePacketListenerImpl;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.networking.client.ClientLoginDisconnect;
import pl.skidam.automodpack.networking.content.LoginUpdateResponse;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.client.ManifestFetcher;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.client.ModpackUtils;
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
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.Throwables;

/** Coordinates the client-side work that follows the login data query. */
final class ClientLoginUpdateFlow {
	private ClientLoginUpdateFlow() {}

	/** The entry to the login work: an optional modpack first asks a client with nothing synced from this server whether it wants the pack at all. */
	static CompletableFuture<LoginUpdateResponse> reconcile(ClientHandshakePacketListenerImpl handler, ConnectionJsons.ConnectionInfo connectionInfo,
			Secrets.Secret secret, ClientStorage storage, boolean requireModpack) {
		if (!requireModpack && !syncedFromOrigin(storage, connectionInfo.origin)) return offerModpack(handler, connectionInfo, secret, storage);
		return fetchAndReconcile(handler, connectionInfo, secret, storage);
	}

	/** Whether any installed pack's connection record names this joining origin; unreadable storage reads as never synced here, so the offer still shows. */
	private static boolean syncedFromOrigin(ClientStorage storage, InetSocketAddress origin) {
		try {
			return ConnectionStore.hasOriginConnection(storage, origin);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Cannot read the installed packs to compare connection origins; offering the modpack", e);
			return false;
		}
	}

	/**
	 * The join offer for an optional modpack: the player picks before any transport opens, so nothing is fetched or
	 * persisted yet. Sync chains into the standard flow unchanged; joining without it resumes the login in-session and
	 * persists no connection record, trust entry or secret; backing out drops the join at the multiplayer hub.
	 */
	private static CompletableFuture<LoginUpdateResponse> offerModpack(ClientHandshakePacketListenerImpl handler, ConnectionJsons.ConnectionInfo connectionInfo,
			Secrets.Secret secret, ClientStorage storage) {
		if (!ScreenManager.hasScreen()) {
			LOGGER.info("No screen available, treating the offered modpack as required");
			return fetchAndReconcile(handler, connectionInfo, secret, storage);
		}
		LOGGER.info("The server offers its modpack; asking the player before any sync");
		CompletableFuture<LoginUpdateResponse> answered = new CompletableFuture<>();
		Runnable syncModpack = () -> ModpackUpdater.executor().execute(() -> fetchAndReconcile(handler, connectionInfo, secret, storage)
				.whenComplete((response, error) -> {
					if (error == null) answered.complete(response);
					else answered.completeExceptionally(error);
				}));
		Runnable joinWithout = () -> ModpackUpdater.executor().execute(() -> {
			LOGGER.info("Joining without the offered modpack; nothing is synced");
			answered.complete(LoginUpdateResponse.CONTINUE);
		});
		Runnable cancel = () -> ModpackUpdater.executor().execute(() -> {
			disconnectImmediately(handler);
			ScreenImpl.multiplayer();
			answered.complete(LoginUpdateResponse.JOIN_CANCELLED);
		});
		ScreenManager.modpackOffer(syncModpack, joinWithout, cancel);
		return answered;
	}

	private static CompletableFuture<LoginUpdateResponse> fetchAndReconcile(ClientHandshakePacketListenerImpl handler, ConnectionJsons.ConnectionInfo connectionInfo,
			Secrets.Secret secret, ClientStorage storage) {
		String selectedModpackId = clientConfig.selectedModpackId;
		return ManifestFetcher.requestServerModpackContentAsync(storage, connectionInfo, secret, true, selectedModpackId).thenComposeAsync(manifestResult -> {
			if (!manifestResult.successful()) {
				disconnectImmediately(handler);
				Throwable failure = manifestResult.failure() == null ? new IOException("Modpack manifest fetch returned no failure cause") : manifestResult.failure();
				return CompletableFuture.completedFuture(presentReconcileFailure(failure, manifestResult.state()));
			}

			PackTransport transport = manifestResult.transport();
			ClientSelectionStore selections = new ClientSelectionStore(storage.selectionFile());
			PackDocument record;
			SelectionIntent savedSelection;
			try {
				record = PackDocument.fromFields(manifestResult.content());
				savedSelection = selections.get(record.manifest().modpackId()).orElse(null);
			} catch (RuntimeException e) {
				transport.close();
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
							transport.close();
						});
						ModpackUpdater.executor().execute(() -> {
							if (repairCancelled.get()) return;
							try {
								SelectedModpackTarget repaired = SelectedModpackTarget.prepare(manifestResult.content(), savedSelection, intent, ClientPlatform.effective(intent));
								continueReconcile(handler, connectionInfo, secret, storage, transport, repaired, true, false);
							} catch (RuntimeException repairError) {
								if (repairCancelled.get()) return;
								transport.close();
								presentFailure(repairError, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
							}
						});
					}, transport::close);
					return CompletableFuture.completedFuture(LoginUpdateResponse.UPDATE_REQUIRED);
				}
				transport.close();
				presentFailure(e, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
				disconnectImmediately(handler);
				return CompletableFuture.completedFuture(LoginUpdateResponse.UPDATE_REQUIRED);
			} catch (RuntimeException e) {
				transport.close();
				presentFailure(e, "automodpack.error.corruptState", FailureCategory.CORRUPT_STATE);
				disconnectImmediately(handler);
				return CompletableFuture.completedFuture(LoginUpdateResponse.UPDATE_REQUIRED);
			}

			return continueReconcile(handler, connectionInfo, secret, storage, transport, selectedTarget, false, false);
		}, ModpackUpdater.executor()).exceptionally(e -> {
			disconnectImmediately(handler);
			return presentReconcileFailure(Throwables.unwrap(e), null);
		});
	}

	/**
	 * The failure tail shared by every path into the manifest fetch: shows the one screen that tells the player the truth
	 * and returns the response that tells the server the same story. A refused certificate or a dismissed verification is
	 * named on the wire so the server stops blaming its own config; everything else stays a host error.
	 */
	private static LoginUpdateResponse presentReconcileFailure(Throwable failure, ManifestFetcher.ManifestFetchState state) {
		if (Throwables.findCause(failure, CertificateTrustCancelledException.class) != null) return LoginUpdateResponse.CLIENT_DECLINED;
		CertificatePinMismatchException mismatch = Throwables.findCause(failure, CertificatePinMismatchException.class);
		if (mismatch != null) {
			FailureRequest request = FailureRequest.of(failure, "automodpack.pin.mismatch", FailureCategory.SECURITY, FailureDestination.MULTIPLAYER, null)
					.withDiagnosticDetails("Origin: " + mismatch.getOrigin(), "Expected fingerprint: " + mismatch.getExpectedFingerprint(),
							"Presented fingerprint: " + mismatch.getPresentedFingerprint());
			ScreenManager.failure(request);
			return LoginUpdateResponse.CLIENT_REJECTED;
		}
		if (state == ManifestFetcher.ManifestFetchState.OPERATION_FAILED) {
			presentFailure(failure, "automodpack.error.hostContent", FailureCategory.HOST);
		} else {
			presentFailure(failure, "automodpack.error.connection", FailureCategory.CONNECTION);
		}
		return LoginUpdateResponse.HOST_ERROR;
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
			ClientStorage storage, PackTransport transport, SelectedModpackTarget selectedTarget, boolean alreadyDisconnected, boolean originApproved) {
		ModpackJsons.ModpackContentFields serverModpackContent = selectedTarget.flatTarget();
		ConnectionJsons.ConnectionInfo stored = storedConnection(storage, serverModpackContent.modpackId);
		if (!originApproved && stored != null && stored.origin != null && !stored.isApprovedOrigin(connectionInfo.origin)) {
			return CompletableFuture.completedFuture(offerOriginChange(handler, connectionInfo, secret, storage, transport, selectedTarget, alreadyDisconnected, stored));
		}
		if (stored != null) stored.approvedOrigins().forEach(connectionInfo::approveOrigin);
		connectionInfo.approveOrigin(AddressHelpers.formatAddress(connectionInfo.origin));
		try {
			ConnectionStore.saveConnection(storage, serverModpackContent.modpackId, connectionInfo);
			// HTTP packs have no credential: the login handshake still issues a secret for the custom modes, but
			// absence persisted as absence is what keeps the auth machinery out of the HTTP mode entirely.
			if (connectionInfo.connectionMode != ModpackConnectionMode.HTTP) ConnectionStore.saveClientSecret(storage, serverModpackContent.modpackId, connectionInfo.origin, secret);
		} catch (Exception e) {
			transport.close();
			presentFailure(e, "automodpack.error.storage", FailureCategory.STORAGE);
			if (!alreadyDisconnected) disconnectImmediately(handler);
			return CompletableFuture.completedFuture(LoginUpdateResponse.UPDATE_REQUIRED);
		}

		ModpackUpdater updater = new ModpackUpdater(selectedTarget, connectionInfo, secret, storage, transport);
		try {
			ClientGenerationStore generations = new ClientGenerationStore(storage);
			if (generations.isDetached(serverModpackContent.modpackId)) {
				boolean headMatchesActive = generations.headMatchesActive(serverModpackContent.modpackId, serverModpackContent.contentToken);
				LOGGER.info("Modpack {} runs detached from the server head; asking the player before any sync", serverModpackContent.modpackId);
				return detachedJoin(handler, updater, selectedTarget, alreadyDisconnected, headMatchesActive);
			}
			return CompletableFuture.completedFuture(syncDuringLogin(handler, storage, updater, selectedTarget, alreadyDisconnected));
		} catch (Exception e) {
			updater.close();
			presentFailure(e, "automodpack.error.update", FailureCategory.UPDATE);
			if (!alreadyDisconnected) disconnectImmediately(handler);
			return CompletableFuture.completedFuture(LoginUpdateResponse.UPDATE_REQUIRED);
		}
	}

	/**
	 * The non-detached join: the advertised head is checked against the local generation, an already-current pack lets
	 * the login proceed, and everything else runs the reviewed update through the engine after the connecting screen is
	 * released. The failure tail stays with the caller, since the detached prompt shares it.
	 */
	private static LoginUpdateResponse syncDuringLogin(ClientHandshakePacketListenerImpl handler, ClientStorage storage, ModpackUpdater updater,
			SelectedModpackTarget selectedTarget, boolean alreadyDisconnected) throws Exception {
		ModpackJsons.ModpackContentFields serverModpackContent = selectedTarget.flatTarget();
		ModpackUtils.UpdateCheckResult updateCheckResult = ModpackUtils.isUpdate(serverModpackContent, storage);
		ModpackUtils.reprotectActiveFiles(serverModpackContent, storage);
		if (!updater.requiresUpdateBeforeLogin(updateCheckResult)) {
			updater.close();
			if (alreadyDisconnected) ScreenImpl.multiplayer();
			return alreadyDisconnected ? LoginUpdateResponse.UPDATE_REQUIRED : LoginUpdateResponse.CONTINUE;
		}
		if (!alreadyDisconnected) {
			LOGGER.info("Modpack update required; leaving the connecting screen");
			ScreenManager.waiting(updater::cancelFromPlayer);
			disconnectImmediately(handler);
		}
		updater.processModpackUpdate(true);
		return LoginUpdateResponse.UPDATE_REQUIRED;
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
		Runnable continueJoin = () -> ModpackUpdater.executor().execute(() -> {
			updater.close();
			answered.complete(alreadyDisconnected ? LoginUpdateResponse.UPDATE_REQUIRED : LoginUpdateResponse.CONTINUE);
		});
		Runnable syncNow = () -> ModpackUpdater.executor().execute(() -> {
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

	/** A new address serving an installed pack can be a sibling server, a migration or an impostor; the player decides once and the approval set makes it stick. */
	private static LoginUpdateResponse offerOriginChange(ClientHandshakePacketListenerImpl handler, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret,
			ClientStorage storage, PackTransport transport, SelectedModpackTarget selectedTarget, boolean alreadyDisconnected, ConnectionJsons.ConnectionInfo stored) {
		if (!alreadyDisconnected) disconnectImmediately(handler);
		if (!ScreenManager.hasScreen()) {
			LOGGER.warn("No screen available, refusing the changed origin for modpack {}", selectedTarget.flatTarget().modpackId);
			transport.close();
			return LoginUpdateResponse.UPDATE_REQUIRED;
		}
		String modpackName = selectedTarget.manifest().modpackName();
		if (modpackName.isBlank()) modpackName = selectedTarget.flatTarget().modpackId;
		List<String> approved = new ArrayList<>(stored.approvedOrigins());
		if (approved.isEmpty() && stored.origin != null) approved.add(AddressHelpers.formatAddress(stored.origin));
		ScreenManager.originChange(modpackName, String.join(", ", approved), AddressHelpers.formatAddress(connectionInfo.origin),
				() -> ModpackUpdater.executor().execute(() -> continueReconcile(handler, connectionInfo, secret, storage, transport, selectedTarget, true, true)),
				() -> {
					transport.close();
					ScreenImpl.multiplayer();
				});
		return LoginUpdateResponse.UPDATE_REQUIRED;
	}
}
