package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.CertificateTrustCancelledException;
import pl.skidam.automodpack_core.protocol.DocumentFetch;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.protocol.http.HttpContractClient;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.HeadMirror;
import pl.skidam.automodpack_core.update.JournalMirror;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.Throwables;

/** Fetches the server's head modpack document over one transfer session, conditionally on the locally installed hashes. */
public final class ManifestFetcher {
	private ManifestFetcher() {}

	public enum ManifestFetchState {
		SUCCESS, OPERATION_FAILED, CONNECTION_FAILED
	}

	/** {@code unchanged} is true iff the head came from the local mirror via a conditional match and the journal was not refetched: the installed generation is the server head. */
	public record ManifestFetchResult(ManifestFetchState state, GenerationJsons.HeadDocumentFields content, PackTransport transport, Throwable failure, boolean unchanged) {
		public boolean successful() {
			return state == ManifestFetchState.SUCCESS;
		}
	}

	public static ManifestFetchResult requestServerModpackContent(ClientStorage storage, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret, boolean allowAskingUser,
			String selectedModpackId) {
		try {
			CompletableFuture<ManifestFetchResult> future = requestServerModpackContentAsync(storage, connectionInfo, secret, allowAskingUser, selectedModpackId);
			if (allowAskingUser) return future.get();
			// Non-interactive fetch: the protocol's per-stage timeouts sum to at most 5 * NETWORK_TIMEOUT, so anything past 6 gave up somewhere.
			return future.get(NetUtils.NETWORK_TIMEOUT.multipliedBy(6).toSeconds(), TimeUnit.SECONDS);
		} catch (Exception e) {
			Throwable cause = Throwables.unwrap(e);
			return new ManifestFetchResult(ManifestFetchState.CONNECTION_FAILED, null, null, cause, false);
		}
	}

	// ---- Async versions (non-blocking, used by login packet flow) ----

	public static CompletableFuture<ManifestFetchResult> requestServerModpackContentAsync(ClientStorage storage, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret,
			boolean allowAskingUser, String selectedModpackId) {
		ManifestFetchState connectionFailedState = ManifestFetchState.CONNECTION_FAILED;
		if (!connectionInfo.isComplete()) {
			return CompletableFuture.completedFuture(new ManifestFetchResult(connectionFailedState, null, null, new IllegalArgumentException("Connection origin or endpoint is missing"),
					false));
		}

		return createTransport(connectionInfo, secret == null ? null : secret.secretBytes(), manualValidationCallbackAsync(connectionInfo, allowAskingUser))
				.thenCompose(transport -> fetchModpackContentAsync(storage, transport, ModpackId.isValid(selectedModpackId) ? selectedModpackId : null).handle((fetched, error) -> {
					if (error != null || fetched == null || fetched.content() == null) {
						transport.close();
						Throwable cause = error == null ? new IOException("Server returned no usable modpack content") : Throwables.unwrap(error);
						return new ManifestFetchResult(ManifestFetchState.OPERATION_FAILED, null, null, cause, false);
					}
					return new ManifestFetchResult(ManifestFetchState.SUCCESS, fetched.content(), transport, null, fetched.unchanged());
				}))
				.exceptionally(error -> {
					Throwable cause = Throwables.unwrap(error);
					return new ManifestFetchResult(connectionFailedState, null, null, cause, false);
				});
	}

	/** The head document plus whether it arrived from the local mirror rather than the wire. */
	private record HeadAndJournal(GenerationJsons.HeadDocumentFields content, boolean unchanged) {}

	/**
	 * One conditional fetch of the head document and its journal vouch. When the selected pack's head mirror parses and
	 * names the active generation, its sha1 rides along as the validator; a match short-circuits the fetch or is
	 * confirmed by the body hash. The journal mirror follows the same discipline against its own file hash.
	 */
	private static CompletableFuture<HeadAndJournal> fetchModpackContentAsync(ClientStorage storage, PackTransport transport, String selectedModpackId) {
		final String headExpected;
		final String journalExpected;
		if (selectedModpackId != null) {
			String head = null;
			String journal = null;
			try {
				head = new HeadMirror(storage).installedSha1(new ClientGenerationStore(storage), selectedModpackId);
				Path journalMirror = storage.historyJournalFile(selectedModpackId);
				journal = Files.exists(journalMirror, LinkOption.NOFOLLOW_LINKS) ? HashUtils.getHash(journalMirror) : null;
			} catch (IOException | RuntimeException e) {
				// A mirror that cannot be read costs only the optimization: the fetch proceeds unconditionally.
				LOGGER.warn("Cannot read the installed hashes of modpack {}; fetching without conditionals", selectedModpackId, e);
			}
			headExpected = head;
			journalExpected = journal;
		} else {
			headExpected = null;
			journalExpected = null;
		}

		return transport.downloadDocument(GenerationHosting.HEAD_DOCUMENT_KEY.getBytes(StandardCharsets.UTF_8), storage.modpackContentTempFile(), headExpected, null)
				.thenComposeAsync(fetch -> applyFetchedHead(storage, transport, selectedModpackId, headExpected, journalExpected, fetch), DownloadClient.NET_EXECUTOR).whenComplete((ignored, error) -> {
					try {
						Files.deleteIfExists(storage.modpackContentTempFile());
					} catch (IOException e) {
						LOGGER.warn("Failed to remove temporary modpack content", e);
					}
				});
	}

	/** Applies one head fetch answer: parses the served or mirrored document, writes a fresh fetch through to the mirror, then syncs the journal vouch. */
	private static CompletableFuture<HeadAndJournal> applyFetchedHead(ClientStorage storage, PackTransport transport, String selectedModpackId, String headExpected, String journalExpected,
			DocumentFetch fetch) {
		GenerationJsons.HeadDocumentFields content;
		boolean headFromMirror;
		if (fetch.unchanged()) {
			// The validator is the only thing that can make UNCHANGED a truthful answer; anything else is a broken or
			// hostile server, and no mirror may be trusted on its word.
			if (headExpected == null) return CompletableFuture.failedFuture(new IOException("Server answered UNCHANGED to an unconditional head request"));
			// A conditional match: the server sent nothing, or it ignored the validator and the body hashed to the
			// expectation - either way the mirror holds exactly those bytes, so the content reads from there.
			headFromMirror = true;
			Path source = fetch.path() != null ? fetch.path() : storage.historyHeadFile(selectedModpackId);
			content = ModpackContentTools.readHeadDocument(source);
		} else {
			headFromMirror = false;
			content = ModpackContentTools.readHeadDocument(storage.modpackContentTempFile());
			if (content != null) {
				try {
					// Write-through before the caller's temp deletion; the swap moves the temp into the mirror.
					new HeadMirror(storage).replaceFrom(storage.modpackContentTempFile());
				} catch (IOException e) {
					return CompletableFuture.failedFuture(e);
				}
			}
		}
		if (content == null) return CompletableFuture.completedFuture(new HeadAndJournal(null, false));
		return syncJournalMirror(storage, transport, content, journalExpected).thenApply(journalRefetched -> new HeadAndJournal(content, headFromMirror && !journalRefetched));
	}

	/**
	 * After a successful head fetch the pack's durable history artifacts must vouch for it: the head policy document
	 * is stored in the client CAS under its own hash (the mirror's entries name it), and the journal mirror must
	 * vouch for the head content token; when it is stale, missing, or unreadable, the whole journal file is fetched
	 * under the reserved key - conditionally when the mirror's own hash can serve as the validator - and swapped in
	 * whole. Returns whether the journal was actually refetched.
	 */
	private static CompletableFuture<Boolean> syncJournalMirror(ClientStorage storage, PackTransport transport, GenerationJsons.HeadDocumentFields content, String journalExpected) {
		if (content == null) return CompletableFuture.completedFuture(false);
		String modpackId;
		JournalMirror mirror = new JournalMirror(storage);
		try {
			modpackId = ModpackId.requireValid(content.policy.modpackId);
			byte[] policyBytes = ConfigTools.GSON.toJson(content.policy).getBytes(StandardCharsets.UTF_8);
			ClientObjectStore.storeObject(storage, content.policySha1, policyBytes);
		} catch (RuntimeException | IOException e) {
			return CompletableFuture.failedFuture(e);
		}
		if (!mirror.isStale(modpackId, content.contentToken)) return CompletableFuture.completedFuture(false);
		LOGGER.info("Journal mirror is stale for modpack {}; fetching the full journal from the server", modpackId);
		return transport.downloadDocument(GenerationHosting.JOURNAL_KEY.getBytes(StandardCharsets.UTF_8), storage.journalTempFile(), journalExpected, null)
				.thenComposeAsync(fetch -> {
					try {
						if (fetch.unchanged() && journalExpected == null) throw new IOException("Server answered UNCHANGED to an unconditional journal request");
						// A conditional match means the served journal equals the mirror's own bytes: nothing to replace.
						if (!fetch.unchanged()) mirror.replaceFrom(modpackId, fetch.path());
						return CompletableFuture.completedFuture(!fetch.unchanged());
					} catch (IOException e) {
						return CompletableFuture.failedFuture(e);
					}
				}, DownloadClient.NET_EXECUTOR).whenComplete((ignored, error) -> {
					try {
						Files.deleteIfExists(storage.journalTempFile());
					} catch (IOException e) {
						LOGGER.warn("Failed to remove the temporary journal download", e);
					}
				});
	}

	private static CompletableFuture<PackTransport> createTransport(ConnectionJsons.ConnectionInfo connectionInfo, byte[] secretBytes,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) {
		CompletableFuture<? extends PackTransport> transport = connectionInfo.connectionMode == ModpackConnectionMode.HTTP
				? HttpContractClient.createAsync(connectionInfo, trustCallback)
				: DownloadClient.createAsync(connectionInfo, secretBytes, trustCallback);
		return transport.thenApply(created -> {
			if (connectionInfo.trustReason != null) {
				CertificateTrustStore.save(connectionInfo.origin, connectionInfo.expectedFingerprint, CertificateTrustStore.Reason.valueOf(connectionInfo.trustReason));
			}
			return created;
		});
	}

	private static Function<X509Certificate, CompletableFuture<Boolean>> manualValidationCallbackAsync(ConnectionJsons.ConnectionInfo connectionInfo, boolean allowAskingUser) {
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
