package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.protocol.CertificateTrustCancelledException;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePlanner;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.VerifiedFileTransfer;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public class ModpackUtils {

	// Modpack may require update even if there's no files to update, because some files may need to be deleted
	public record UpdateCheckResult(boolean requiresUpdate, Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToUpdate) {}

	public enum ManifestFetchState {
		SUCCESS, OPERATION_FAILED, CONNECTION_FAILED
	}

	public record ManifestFetchResult(ManifestFetchState state, GenerationJsons.HeadDocumentFields content, DownloadClient client, Throwable failure) {
		public boolean successful() {
			return state == ManifestFetchState.SUCCESS;
		}
	}

	// Fast and friendly method to check if the modpack is up to date without modifying anything on disk
	public static UpdateCheckResult isUpdate(ModpackJsons.ModpackContentFields serverModpackContent, ClientStorage storage) {
		if (serverModpackContent == null || serverModpackContent.list == null) throw new IllegalArgumentException("Server modpack content list is null");
		if (verificationCannotDecide(serverModpackContent, storage)) return new UpdateCheckResult(true, serverModpackContent.list);

		LOGGER.info("Verifying content against server list...");
		var start = System.currentTimeMillis();

		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToUpdate = new HashSet<>();
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			Map<String, UpdatePlan.FileState> live = ClientProjectionView.open(storage).liveFiles(cache);
			for (var serverItem : serverModpackContent.list) {
				if (verifyActiveItem(serverItem, UpdatePlanner.normalize(serverItem.file), live) == FileVerification.MISMATCH) filesToUpdate.add(serverItem);
			}

			if (filesToUpdate.isEmpty()) {
				LOGGER.info("Checking for deleted files...");
				Set<String> serverFileSet = serverModpackContent.list.stream().map(item -> UpdatePlanner.normalize(item.file)).collect(Collectors.toSet());
				for (String relative : live.keySet()) {
					if (!serverFileSet.contains(relative)) {
						LOGGER.info("Found projected file marked for deletion: {}", relative);
						return new UpdateCheckResult(true, Set.of());
					}
				}
			}
		} catch (Exception e) {
			LOGGER.error("Error during update check", e);
			return new UpdateCheckResult(true, serverModpackContent.list);
		}

		if (!filesToUpdate.isEmpty()) {
			LOGGER.info("Active projection requires update! Took {} ms", System.currentTimeMillis() - start);
			return new UpdateCheckResult(true, filesToUpdate);
		}

		LOGGER.info("Active projection is up to date! Took {} ms", System.currentTimeMillis() - start);
		return new UpdateCheckResult(false, Set.of());
	}

	// Re-applies the filesystem's immutability to active files that already match the server content; the update verdict above stays read-only
	public static void reprotectActiveFiles(ModpackJsons.ModpackContentFields serverModpackContent, ClientStorage storage) {
		if (serverModpackContent == null || serverModpackContent.list == null) throw new IllegalArgumentException("Server modpack content list is null");
		if (verificationCannotDecide(serverModpackContent, storage)) return;
		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {
			Map<String, UpdatePlan.FileState> live = ClientProjectionView.open(storage).liveFiles(cache);
			for (var serverItem : serverModpackContent.list) {
				String relative = UpdatePlanner.normalize(serverItem.file);
				if (verifyActiveItem(serverItem, relative, live) == FileVerification.MATCH) ImmutableFiles.protect(storage.activePath(relative));
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to re-protect the matching active files", e);
		}
	}

	// True when the per-file scan cannot decide anything and every file must be treated as an update: without an active projection nothing can match, and differing content digests can never pass the per-file scan
	private static boolean verificationCannotDecide(ModpackJsons.ModpackContentFields serverModpackContent, ClientStorage storage) {
		Path activeDirectory = storage.activeDirectory();
		ClientStorageJsons.ClientGenerationStateFields state;
		try {
			state = storage.readActiveState();
			if (state == null || !Files.isDirectory(activeDirectory, LinkOption.NOFOLLOW_LINKS)) return true;
		} catch (IOException e) {
			LOGGER.warn("Cannot read active client generation state", e);
			return true;
		}

		try {
			PackDocument active = new ClientGenerationStore(storage).read(state.contentToken).orElse(null);
			if (active != null && !serverModpackContent.contentToken.isBlank() && !serverModpackContent.contentToken.equals(active.contentToken())) {
				LOGGER.info("Server modpack content differs from the installed modpack; skipping the per-file verification");
				return true;
			}
		} catch (IOException | RuntimeException e) {
			LOGGER.debug("Cannot compare the installed modpack content token", e);
		}
		return false;
	}

	private enum FileVerification {
		MATCH, MISMATCH, SKIP
	}

	// Editable files are skipped from the hash check entirely; only non-editable files present in the projection with matching size and sha1 are a match
	private static FileVerification verifyActiveItem(ModpackJsons.ModpackContentFields.ModpackContentItem serverItem, String relative, Map<String, UpdatePlan.FileState> live) {
		UpdatePlan.FileState observed = live.get(relative);
		if (observed == null || !observed.regularFile()) return FileVerification.MISMATCH;
		if (serverItem.editable) {
			LOGGER.debug("Skipping editable file hash check: {}", serverItem.file);
			return FileVerification.SKIP;
		}
		long size;
		try {
			size = Long.parseLong(serverItem.size);
		} catch (NumberFormatException e) {
			return FileVerification.MISMATCH;
		}
		if (observed.size() != size || serverItem.sha1 == null || !serverItem.sha1.equalsIgnoreCase(observed.sha1())) return FileVerification.MISMATCH;
		return FileVerification.MATCH;
	}

	// Scans for files missing from the store. If found in the CWD (and the hash matches), copies them to the store.
	public static void populateStoreFromCWD(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToUpdate, FileMetadataCache cache, ClientStorage storage) {
		for (var entry : filesToUpdate) {
			Path storeFile = storage.objectFile(entry.sha1);
			long expectedSize = Long.parseLong(entry.size);

			if (FileIntegrity.matchesNamed(storeFile, expectedSize, entry.sha1, cache)) {
				LOGGER.debug("Verified file already exists in store: {}", entry.file);
				continue;
			}
			try {
				if (Files.exists(storeFile)) {
					LOGGER.warn("Evicting corrupt store object {}", entry.sha1);
					ImmutableFiles.deleteIfExists(storeFile);
				}
			} catch (IOException e) {
				LOGGER.error("Failed to evict corrupt store object {}", entry.sha1, e);
				continue;
			}

			Path fileInCWD = storage.gamePath(entry.file);
			if (FileIntegrity.matches(fileInCWD, expectedSize, entry.sha1, cache)) {
				LOGGER.info("Copying existing file from CWD to store: {}", entry.file);
				try {
					VerifiedFileTransfer.copyAtomicImmutable(fileInCWD, storeFile, expectedSize, entry.sha1, cache);
				} catch (IOException e) {
					LOGGER.error("Failed to copy file from CWD to store: {}", entry.file, e);
				}
			}
		}
	}

	// Returns the set of files that are missing or corrupt in the store.
	public static Set<ModpackJsons.ModpackContentFields.ModpackContentItem> identifyUncachedFiles(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToCheck,
			FileMetadataCache cache, ClientStorage storage) {
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> uncachedFiles = new HashSet<>();
		for (var entry : filesToCheck) {
			Path storeFile = storage.objectFile(entry.sha1);
			if (FileIntegrity.matchesNamed(storeFile, Long.parseLong(entry.size), entry.sha1, cache)) continue;
			if (Files.exists(storeFile)) {
				try {
					LOGGER.warn("Evicting corrupt store object {}", entry.sha1);
					ImmutableFiles.deleteIfExists(storeFile);
				} catch (IOException e) {
					LOGGER.warn("Failed to evict corrupt store object {}", entry.sha1, e);
				}
			}
			uncachedFiles.add(entry);
		}
		return uncachedFiles;
	}

	public static ClientConfigJsons.ClientConfigFieldsV3 planModpackSelection(String modpackId, ConnectionJsons.ConnectionInfo connectionInfo,
			ClientConfigJsons.ClientConfigFieldsV3 currentConfig) {
		ModpackId.requireValid(modpackId);
		if (connectionInfo == null || !connectionInfo.isComplete()) throw new IllegalArgumentException("Connection origin or endpoint is missing");
		return planCachedModpackSelection(modpackId, currentConfig);
	}

	public static ClientConfigJsons.ClientConfigFieldsV3 planCachedModpackSelection(String modpackId, ClientConfigJsons.ClientConfigFieldsV3 currentConfig) {
		ModpackId.requireValid(modpackId);
		return currentConfig.withSelectedModpackId(modpackId);
	}

	public static ManifestFetchResult requestServerModpackContent(ClientStorage storage, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret, boolean allowAskingUser) {
		try {
			CompletableFuture<ManifestFetchResult> future = requestServerModpackContentAsync(storage, connectionInfo, secret, allowAskingUser);
			if (allowAskingUser) return future.get();
			// Non-interactive fetch: the protocol's per-stage timeouts sum to at most 5 * NETWORK_TIMEOUT, so anything past 6 gave up somewhere.
			return future.get(NetUtils.NETWORK_TIMEOUT.multipliedBy(6).toSeconds(), TimeUnit.SECONDS);
		} catch (Exception e) {
			Throwable cause = DownloadClient.unwrap(e);
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
				.thenCompose(client -> fetchModpackContentAsync(storage, client, current -> current.downloadFile(new byte[0], storage.modpackContentTempFile(), null)).handle((content, error) -> {
					if (error != null || content.isEmpty()) {
						client.close();
						Throwable cause = error == null ? new IOException("Server returned no usable modpack content") : DownloadClient.unwrap(error);
						return new ManifestFetchResult(ManifestFetchState.OPERATION_FAILED, null, null, cause);
					}
					return new ManifestFetchResult(ManifestFetchState.SUCCESS, content.get(), client, null);
				})).exceptionally(error -> {
					Throwable cause = DownloadClient.unwrap(error);
					return new ManifestFetchResult(connectionFailedState, null, null, cause);
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

		var parent = ScreenManager.getScreen().orElse(null);
		if (parent == null) {
			LOGGER.warn("No screen available, cannot ask user");
			return CompletableFuture.completedFuture(false);
		}

		CompletableFuture<Boolean> result = new CompletableFuture<>();
		Runnable trustAction = () -> {
			CertificateTrustStore.save(connectionInfo.origin, fingerprint, CertificateTrustStore.Reason.TOFU);
			result.complete(true);
		};
		Runnable cancelAction = () -> result.completeExceptionally(new CertificateTrustCancelledException());
		ScreenManager.validation(parent, fingerprint, AddressHelpers.formatAddress(connectionInfo.origin), trustAction, cancelAction);
		return result;
	}
}
