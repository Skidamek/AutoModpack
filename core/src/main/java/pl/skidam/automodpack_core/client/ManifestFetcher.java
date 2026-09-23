package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import java.util.function.IntConsumer;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationHosting;
import pl.skidam.automodpack_core.protocol.CertificateTrustCancelledException;
import pl.skidam.automodpack_core.protocol.DocumentFetch;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.MissingObjectException;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.protocol.PackTransport.DocumentConditional;
import pl.skidam.automodpack_core.protocol.UnauthorizedException;
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

	public record ManifestFetchResult(ManifestFetchState state, GenerationJsons.HeadDocumentFields content, PackTransport transport, Throwable failure) {
		public boolean successful() {
			return state == ManifestFetchState.SUCCESS;
		}
	}

	public static ManifestFetchResult requestServerModpackContent(ClientStorage storage, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret, boolean allowAskingUser,
			String selectedModpackId) {
		if (allowAskingUser) {
			try {
				return requestServerModpackContentAsync(storage, connectionInfo, secret, allowAskingUser, selectedModpackId).get();
			} catch (Exception e) {
				return new ManifestFetchResult(ManifestFetchState.CONNECTION_FAILED, null, null, Throwables.unwrap(e));
			}
		}
		TransportAbandonment abandonment = new TransportAbandonment();
		try {
			// Non-interactive fetch: the budget is a best-effort surrender, not a bound - DNS resolution is unbounded and
			// document bodies carry no stall fuse, so the chain can settle long after the budget is gone. The surrender
			// is honest because of the abandonment: any chain completion that lands after it closes the transport, so
			// neither a wedged lookup nor a late success leaks one.
			return requestServerModpackContentAsync(storage, connectionInfo, secret, allowAskingUser, selectedModpackId, abandonment)
					.get(NetUtils.NETWORK_TIMEOUT.multipliedBy(6).toSeconds(), TimeUnit.SECONDS);
		} catch (TimeoutException e) {
			abandonment.abandon();
			return new ManifestFetchResult(ManifestFetchState.CONNECTION_FAILED, null, null, Throwables.unwrap(e));
		} catch (Exception e) {
			return new ManifestFetchResult(ManifestFetchState.CONNECTION_FAILED, null, null, Throwables.unwrap(e));
		}
	}

	// ---- Async versions (non-blocking, used by login packet flow) ----

	public static CompletableFuture<ManifestFetchResult> requestServerModpackContentAsync(ClientStorage storage, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret,
			boolean allowAskingUser, String selectedModpackId) {
		return requestServerModpackContentAsync(storage, connectionInfo, secret, allowAskingUser, selectedModpackId, new TransportAbandonment());
	}

	private static CompletableFuture<ManifestFetchResult> requestServerModpackContentAsync(ClientStorage storage, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret,
			boolean allowAskingUser, String selectedModpackId, TransportAbandonment abandonment) {
		ManifestFetchState connectionFailedState = ManifestFetchState.CONNECTION_FAILED;
		if (!connectionInfo.isComplete()) {
			return CompletableFuture.completedFuture(new ManifestFetchResult(connectionFailedState, null, null, new IllegalArgumentException("Connection origin or endpoint is missing")));
		}

		CompletableFuture<ManifestFetchResult> fetch = createTransport(connectionInfo, secret == null ? null : secret.secret(), manualValidationCallbackAsync(connectionInfo, allowAskingUser))
				.thenCompose(transport -> {
					abandonment.opened.set(transport);
					return fetchModpackContentAsync(storage, connectionInfo, transport, ModpackId.isValid(selectedModpackId) ? selectedModpackId : null).handle((fetched, error) -> {
						if (error != null || fetched == null) {
							transport.close();
							Throwable cause = error == null ? new IOException("Server returned no usable modpack content") : Throwables.unwrap(error);
							return new ManifestFetchResult(ManifestFetchState.OPERATION_FAILED, null, null, cause);
						}
						return new ManifestFetchResult(ManifestFetchState.SUCCESS, fetched, transport, null);
					});
				})
				.exceptionally(error -> {
					Throwable cause = Throwables.unwrap(error);
					return new ManifestFetchResult(connectionFailedState, null, null, cause);
				});
		// The last word on abandonment: a completion that lands after the surrender (an unbounded DNS lookup, a body
		// that outlived the budget) closes the transport nobody consumed, instead of leaking it.
		return fetch.whenComplete((result, error) -> abandonment.closeIfAbandoned());
	}

	/**
	 * The non-interactive fetch's surrender handle: the opened transport reference plus the flag saying the budget gave
	 * up. On timeout the caller abandons, which closes whatever the reference holds; any chain completion after that
	 * closes the transport too, covering both a late client creation and a late successful result.
	 */
	private static final class TransportAbandonment {
		private final AtomicReference<PackTransport> opened = new AtomicReference<>();
		private final AtomicBoolean abandoned = new AtomicBoolean();

		void abandon() {
			abandoned.set(true);
			closeOpened();
		}

		/** The chain's last word: closes the transport when the surrender landed first; a normal completion was consumed by the caller. */
		void closeIfAbandoned() {
			if (abandoned.get()) closeOpened();
		}

		private void closeOpened() {
			PackTransport transport = opened.get();
			if (transport != null) transport.close();
		}
	}

	/**
	 * One conditional fetch of the head document and its journal vouch. When the selected pack's head mirror parses and
	 * names the active generation, its sha1 rides along as the validator; a match short-circuits the transfer or is
	 * confirmed by the body hash. The journal mirror follows the same discipline against its own file hash. The
	 * short-circuit covers the transfer only: every caller still verifies the local projection against the content, so
	 * a corrupted or removed file is repaired even when the server head never moved.
	 */
	private static CompletableFuture<GenerationJsons.HeadDocumentFields> fetchModpackContentAsync(ClientStorage storage, ConnectionJsons.ConnectionInfo connectionInfo, PackTransport transport, String selectedModpackId) {
		final HostValidatorCache validators = HostValidatorCache.load(storage);
		final InetSocketAddress endpoint = connectionInfo.endpoint;
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
			if (head != null) LOGGER.info("Installed head mirror vouches for {}; fetching head and journal conditionally in one round trip", selectedModpackId);
		} else {
			headExpected = null;
			journalExpected = null;
		}
		// The host's own cached etag rides behind the vouched sha1 (never alone - a 304 must always stand for "the
		// vouched mirror is current"), so foreign hosts whose ETags are not our sha1 can answer 304 too.
		DocumentConditional headConditional = headExpected == null
				? null
				: new DocumentConditional(headExpected, validators.get(endpoint, GenerationHosting.HEAD_DOCUMENT_KEY));
		DocumentConditional journalConditional = journalExpected == null
				? null
				: new DocumentConditional(journalExpected, validators.get(endpoint, GenerationHosting.JOURNAL_KEY));

		// When the mirror vouches, the journal request is decided before either request is sent, so it pipelines behind
		// the head: a matching pair answers in one round trip, and a moved head still hands back the journal that head
		// wants - the same request the sequential chain would have issued after parsing. The head leads the wire: it is
		// the gate whose answer decides everything else, and on a close-framed host its body ends the lane only after
		// both requests are in flight.
		CompletableFuture<DocumentFetch> headFetched = transport.downloadDocument(GenerationHosting.HEAD_DOCUMENT_KEY.getBytes(StandardCharsets.UTF_8), storage.modpackContentTempFile(), headConditional,
				(IntConsumer) null);
		CompletableFuture<DocumentFetch> journalFetched = headExpected == null ? null : fetchPipelinedJournal(transport, storage, journalConditional);
		return headFetched
				.thenComposeAsync(fetch -> applyFetchedHead(storage, connectionInfo, transport, selectedModpackId, headExpected, journalExpected, journalFetched, fetch, validators), DownloadClient.NET_EXECUTOR)
				.whenComplete((ignored, error) -> {
					try {
						Files.deleteIfExists(storage.modpackContentTempFile());
					} catch (IOException e) {
						LOGGER.warn("Failed to remove temporary modpack content", e);
					}
				});
	}

	/** One full journal fetch into the temp file; callers delete the temp only after their last consumer of the fetch has run. */
	private static CompletableFuture<DocumentFetch> fetchJournal(PackTransport transport, ClientStorage storage, DocumentConditional journalConditional) {
		return transport.downloadDocument(GenerationHosting.JOURNAL_KEY.getBytes(StandardCharsets.UTF_8), storage.journalTempFile(), journalConditional, (IntConsumer) null);
	}

	/**
	 * The pipelined journal fetch shares the head's lane, and a close-framed head body ends at EOF and spends that lane,
	 * so the pipelined journal response never parses behind it. One re-issue on a fresh lane recovers it; a status
	 * verdict (a rejected secret, a missing document) is the server's answer and never retries.
	 */
	private static CompletableFuture<DocumentFetch> fetchPipelinedJournal(PackTransport transport, ClientStorage storage, DocumentConditional journalConditional) {
		return fetchJournal(transport, storage, journalConditional).exceptionallyCompose(error -> {
			Throwable cause = Throwables.unwrap(error);
			if (!(cause instanceof IOException) || cause instanceof UnauthorizedException || cause instanceof MissingObjectException) return CompletableFuture.failedFuture(error);
			LOGGER.warn("The pipelined journal fetch lost its lane; fetching the journal again on a fresh one", cause);
			return fetchJournal(transport, storage, journalConditional);
		});
	}

	private static void deleteJournalTemp(ClientStorage storage) {
		try {
			Files.deleteIfExists(storage.journalTempFile());
		} catch (IOException e) {
			LOGGER.warn("Failed to remove the temporary journal download", e);
		}
	}

	/** Applies one head fetch answer: parses the served or mirrored document, writes a fresh fetch through to the mirror, then syncs the journal vouch. */
	private static CompletableFuture<GenerationJsons.HeadDocumentFields> applyFetchedHead(ClientStorage storage, ConnectionJsons.ConnectionInfo connectionInfo, PackTransport transport, String selectedModpackId,
			String headExpected, String journalExpected,
			CompletableFuture<DocumentFetch> journalFetched, DocumentFetch fetch, HostValidatorCache validators) {
		InetSocketAddress endpoint = connectionInfo.endpoint;
		GenerationJsons.HeadDocumentFields content;
		if (fetch.unchanged()) {
			// The validator is the only thing that can make UNCHANGED a truthful answer; anything else is a broken or
			// hostile server, and no mirror may be trusted on its word.
			if (headExpected == null) return CompletableFuture.failedFuture(new IOException("Server answered UNCHANGED to an unconditional head request"));
			// A conditional match: the server sent nothing, or it ignored the validator and the body hashed to the
			// expectation - either way the mirror holds exactly those bytes, so the content reads from there. A body
			// that did arrive verified the mirror byte-for-byte, so its etag is cacheable.
			Path source = fetch.path() != null ? fetch.path() : storage.historyHeadFile(selectedModpackId);
			content = ModpackContentTools.readHeadDocument(source);
			if (fetch.path() != null) validators.put(endpoint, GenerationHosting.HEAD_DOCUMENT_KEY, fetch.etag());
		} else {
			LOGGER.info("Fetched the head document from the server (installed mirror {})", headExpected == null ? "did not vouch" : "is stale");
			content = ModpackContentTools.readHeadDocument(storage.modpackContentTempFile());
			if (content != null) {
				try {
					// Write-through before the caller's temp deletion; the swap moves the temp into the mirror.
					new HeadMirror(storage).replaceFrom(storage.modpackContentTempFile());
					// The mirror now holds the served bytes, so the served etag stands for them and may be replayed.
					validators.put(endpoint, GenerationHosting.HEAD_DOCUMENT_KEY, fetch.etag());
				} catch (IOException e) {
					return CompletableFuture.failedFuture(e);
				}
			}
		}
		if (content == null) {
			// The pipelined journal fetch, if any, lands with nobody to consume it: sweep its temp once it settles.
			if (journalFetched != null) journalFetched.whenComplete((ignored, error) -> deleteJournalTemp(storage));
			return CompletableFuture.completedFuture(null);
		}
		CompletableFuture<Boolean> journalSync = syncJournalMirror(storage, connectionInfo, transport, content, journalExpected, journalFetched, validators);
		if (!fetch.unchanged()) return journalSync.thenApply(journalRefetched -> content);
		// The validator matched, so the head mirror - and the journal that vouched for it - already hold what the
		// server serves: a journal fetch that keeps failing even after its fresh-lane re-issue must not discard a
		// good head. The callers still verify the local projection against the content, which is what repairs a
		// corrupted or removed mirror.
		return journalSync.handle((journalRefetched, journalError) -> {
			if (journalError != null) LOGGER.warn("The journal fetch for modpack {} kept failing; keeping the current head mirror without its journal sync", selectedModpackId, Throwables.unwrap(journalError));
			return content;
		});
	}

	/**
	 * After a successful head fetch the pack's durable history artifacts must vouch for it: the head policy document
	 * is stored in the client CAS under its own hash (the mirror's entries name it), and the journal mirror must
	 * vouch for the head content token; when it is stale, missing, or unreadable, the whole journal file is fetched
	 * under the reserved key - conditionally when the mirror's own hash can serve as the validator - and swapped in
	 * whole. A journal fetch already pipelined beside the head request skips the staleness gate: it is the same
	 * request the gate would have issued, and a body the server did send is always fresher than deciding from the
	 * gate alone. Returns whether the journal was actually refetched.
	 */
	private static CompletableFuture<Boolean> syncJournalMirror(ClientStorage storage, ConnectionJsons.ConnectionInfo connectionInfo, PackTransport transport, GenerationJsons.HeadDocumentFields content,
			String journalExpected,
			CompletableFuture<DocumentFetch> journalFetched, HostValidatorCache validators) {
		if (content == null) return CompletableFuture.completedFuture(false);
		String modpackId;
		JournalMirror mirror = new JournalMirror(storage);
		try {
			modpackId = ModpackId.requireValid(content.policy.modpackId);
			byte[] policyBytes = ConfigTools.GSON.toJson(content.policy).getBytes(StandardCharsets.UTF_8);
			ClientObjectStore.storeObject(storage, content.policySha1, policyBytes);
		} catch (RuntimeException | IOException e) {
			if (journalFetched != null) journalFetched.whenComplete((ignored, error) -> deleteJournalTemp(storage));
			return CompletableFuture.failedFuture(e);
		}
		if (journalFetched == null) {
			if (!mirror.isStale(modpackId, content.contentToken)) return CompletableFuture.completedFuture(false);
			LOGGER.info("Journal mirror is stale for modpack {}; fetching the full journal from the server", modpackId);
			journalFetched = fetchJournal(transport, storage, journalExpected == null
					? null
					: new DocumentConditional(journalExpected, validators.get(connectionInfo.endpoint, GenerationHosting.JOURNAL_KEY)));
		}
		return journalFetched.thenComposeAsync(fetch -> {
			try {
				if (fetch.unchanged() && journalExpected == null) throw new IOException("Server answered UNCHANGED to an unconditional journal request");
				// A conditional match means the served journal equals the mirror's own bytes: nothing to replace.
				if (!fetch.unchanged()) {
					mirror.replaceFrom(modpackId, fetch.path());
					// The mirror now holds the served bytes, so the served etag stands for them and may be replayed.
					validators.put(connectionInfo.endpoint, GenerationHosting.JOURNAL_KEY, fetch.etag());
				}
				return CompletableFuture.completedFuture(!fetch.unchanged());
			} catch (IOException e) {
				return CompletableFuture.failedFuture(e);
			}
		}, DownloadClient.NET_EXECUTOR).whenComplete((ignored, error) -> deleteJournalTemp(storage));
	}

	private static CompletableFuture<PackTransport> createTransport(ConnectionJsons.ConnectionInfo connectionInfo, String secret,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) {
		CompletableFuture<DownloadClient> transport = DownloadClient.createAsync(connectionInfo, secret, trustCallback);
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

			LOGGER.warn("First contact with AutoModpack endpoint {}:{} for Minecraft server {}: its certificate {} must be trusted by the player or a published fingerprint",
					connectionInfo.endpoint.getHostString(), connectionInfo.endpoint.getPort(), originHost, fingerprint);
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
