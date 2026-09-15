package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.CertificateTrustCancelledException;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.JournalMirror;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.Throwables;

/** Fetches the server's head modpack manifest over one authenticated, certificate-checked transfer session. */
public final class ManifestFetcher {
	private ManifestFetcher() {}

	public enum ManifestFetchState {
		SUCCESS, OPERATION_FAILED, CONNECTION_FAILED
	}

	public record ManifestFetchResult(ManifestFetchState state, GenerationJsons.HeadDocumentFields content, DownloadClient client, Throwable failure) {
		public boolean successful() {
			return state == ManifestFetchState.SUCCESS;
		}
	}

	public static ManifestFetchResult requestServerModpackContent(ClientStorage storage, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret, boolean allowAskingUser) {
		try {
			CompletableFuture<ManifestFetchResult> future = requestServerModpackContentAsync(storage, connectionInfo, secret, allowAskingUser);
			if (allowAskingUser) return future.get();
			// Non-interactive fetch: the protocol's per-stage timeouts sum to at most 5 * NETWORK_TIMEOUT, so anything past 6 gave up somewhere.
			return future.get(NetUtils.NETWORK_TIMEOUT.multipliedBy(6).toSeconds(), TimeUnit.SECONDS);
		} catch (Exception e) {
			Throwable cause = Throwables.unwrap(e);
			return new ManifestFetchResult(ManifestFetchState.CONNECTION_FAILED, null, null, cause);
		}
	}

	// ---- Async versions (non-blocking, used by login packet flow) ----

	public static CompletableFuture<ManifestFetchResult> requestServerModpackContentAsync(ClientStorage storage, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret,
			boolean allowAskingUser) {
		ManifestFetchState connectionFailedState = ManifestFetchState.CONNECTION_FAILED;
		if (secret == null) {
			return CompletableFuture.completedFuture(
					new ManifestFetchResult(connectionFailedState, null, null, new IllegalArgumentException("Secret is missing")));
		}
		if (!connectionInfo.isComplete()) {
			return CompletableFuture.completedFuture(new ManifestFetchResult(connectionFailedState, null, null,
					new IllegalArgumentException("Connection origin or endpoint is missing")));
		}

		return createDownloadClient(connectionInfo, secret.secretBytes(), manualValidationCallbackAsync(connectionInfo, allowAskingUser))
				.thenCompose(
						client -> fetchModpackContentAsync(storage, client, current -> current.downloadFile(GenerationHosting.HEAD_DOCUMENT_KEY.getBytes(StandardCharsets.UTF_8), storage.modpackContentTempFile(), null))
								.thenCompose(content -> syncJournalMirror(storage, client, content.orElse(null))).handle((content, error) -> {
									if (error != null || content.isEmpty()) {
										client.close();
										Throwable cause = error == null ? new IOException("Server returned no usable modpack content") : Throwables.unwrap(error);
										return new ManifestFetchResult(ManifestFetchState.OPERATION_FAILED, null, null, cause);
									}
									return new ManifestFetchResult(ManifestFetchState.SUCCESS, content.get(), client, null);
								}))
				.exceptionally(error -> {
					Throwable cause = Throwables.unwrap(error);
					return new ManifestFetchResult(connectionFailedState, null, null, cause);
				});
	}

	/**
	 * After a successful head fetch the pack's durable history artifacts must vouch for it: the head policy document
	 * is stored in the client CAS under its own hash (the mirror's entries name it), and the journal mirror must
	 * vouch for the head content token; when it is stale, missing, or unreadable, the whole journal file is fetched
	 * under the reserved key and swapped in whole.
	 */
	private static CompletableFuture<Optional<GenerationJsons.HeadDocumentFields>> syncJournalMirror(ClientStorage storage, DownloadClient client,
			GenerationJsons.HeadDocumentFields content) {
		if (content == null) return CompletableFuture.completedFuture(Optional.empty());
		String modpackId;
		JournalMirror mirror = new JournalMirror(storage);
		try {
			modpackId = ModpackId.requireValid(content.policy.modpackId);
			byte[] policyBytes = ConfigTools.GSON.toJson(content.policy).getBytes(StandardCharsets.UTF_8);
			ClientObjectStore.storeObject(storage, content.policySha1, policyBytes);
		} catch (RuntimeException | IOException e) {
			return CompletableFuture.failedFuture(e);
		}
		if (!mirror.isStale(modpackId, content.contentToken)) return CompletableFuture.completedFuture(Optional.of(content));
		LOGGER.info("Journal mirror is stale for modpack {}; fetching the full journal from the server", modpackId);
		return client.downloadFile(GenerationHosting.JOURNAL_KEY.getBytes(StandardCharsets.UTF_8), storage.journalTempFile(), null)
				.thenApplyAsync(path -> {
					try {
						mirror.replaceFrom(modpackId, path);
					} catch (IOException e) {
						throw new CompletionException(e);
					}
					return Optional.of(content);
				}, DownloadClient.NET_EXECUTOR).whenComplete((ignored, error) -> {
					try {
						Files.deleteIfExists(storage.journalTempFile());
					} catch (IOException e) {
						LOGGER.warn("Failed to remove the temporary journal download", e);
					}
				});
	}

	private static CompletableFuture<Optional<GenerationJsons.HeadDocumentFields>> fetchModpackContentAsync(ClientStorage storage, DownloadClient client,
			Function<DownloadClient, CompletableFuture<Path>> operation) {
		CompletableFuture<Path> operationFuture;
		try {
			operationFuture = operation.apply(client);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}

		return operationFuture.thenApplyAsync(path -> {
			GenerationJsons.HeadDocumentFields content = ModpackContentTools.readHeadDocument(path);
			return Optional.ofNullable(content);
		}, DownloadClient.NET_EXECUTOR).whenComplete((content, error) -> {
			try {
				Files.deleteIfExists(storage.modpackContentTempFile());
			} catch (IOException e) {
				LOGGER.warn("Failed to remove temporary modpack content", e);
			}
		});
	}

	private static CompletableFuture<DownloadClient> createDownloadClient(ConnectionJsons.ConnectionInfo connectionInfo, byte[] secret,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) {
		return DownloadClient.createAsync(connectionInfo, secret, trustCallback).thenApply(client -> {
			if (connectionInfo.trustReason != null) {
				CertificateTrustStore.save(connectionInfo.origin, connectionInfo.expectedFingerprint,
						CertificateTrustStore.Reason.valueOf(connectionInfo.trustReason));
			}
			return client;
		});
	}

	private static Function<X509Certificate, CompletableFuture<Boolean>> manualValidationCallbackAsync(ConnectionJsons.ConnectionInfo connectionInfo,
			boolean allowAskingUser) {
		String originHost = connectionInfo.origin.getHostString();
		return certificate -> {
			String fingerprint;
			try {
				fingerprint = NetUtils.getFingerprint(certificate);
			} catch (CertificateEncodingException e) {
				return CompletableFuture.completedFuture(false);
			}
			if (CertificateTrustStore.matches(connectionInfo.origin, fingerprint)) return CompletableFuture.completedFuture(true);

			LOGGER.warn("Received untrusted certificate for Minecraft server {} from AutoModpack endpoint {}:{}!", originHost, connectionInfo.endpoint.getHostString(),
					connectionInfo.endpoint.getPort());
			if (allowAskingUser) return askUserAboutCertificateAsync(connectionInfo, fingerprint);

			return CompletableFuture.completedFuture(false);
		};
	}

	private static CompletableFuture<Boolean> askUserAboutCertificateAsync(ConnectionJsons.ConnectionInfo connectionInfo, String fingerprint) {
		String originHost = connectionInfo.origin.getHostString();
		LOGGER.info("Asking user to verify certificate for Minecraft server {} from AutoModpack endpoint {}:{}", originHost, connectionInfo.endpoint.getHostString(),
				connectionInfo.endpoint.getPort());

		if (!ScreenManager.hasScreen()) {
			LOGGER.warn("No screen available, cannot ask user");
			return CompletableFuture.completedFuture(false);
		}

		CompletableFuture<Boolean> result = new CompletableFuture<>();
		Runnable trustAction = () -> {
			LOGGER.info("Certificate trust accepted by the player for {}", originHost);
			CertificateTrustStore.save(connectionInfo.origin, fingerprint, CertificateTrustStore.Reason.TOFU);
			result.complete(true);
		};
		Runnable cancelAction = () -> {
			LOGGER.info("Certificate trust cancelled by the player for {}", originHost);
			result.completeExceptionally(new CertificateTrustCancelledException());
		};
		ScreenManager.validation(fingerprint, AddressHelpers.formatAddress(connectionInfo.origin), trustAction, cancelAction);
		return result;
	}
}
