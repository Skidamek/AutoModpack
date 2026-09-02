package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.*;
import java.net.*;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.cert.CertificateEncodingException;
import java.security.cert.X509Certificate;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.function.Function;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.jetbrains.annotations.NotNull;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.protocol.CertificatePinMismatchException;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdatePlanner;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public class ModpackUtils {

	// Modpack may require update even if there's no files to update, because some files may need to be deleted
	public record UpdateCheckResult(boolean requiresUpdate, Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToUpdate,
			Set<String> changedOverwriteEditableFiles) {}

	public enum ManifestFetchState {
		SUCCESS, OPERATION_FAILED, CONNECTION_FAILED
	}

	public record ManifestFetchResult(ManifestFetchState state, ModpackJsons.CompleteModpackContentFields content, DownloadClient client, Throwable failure) {
		public boolean successful() {
			return state == ManifestFetchState.SUCCESS;
		}
	}

	// Fast and friendly method to check if the modpack is up to date without modifying anything on disk
	public static UpdateCheckResult isUpdate(ModpackJsons.ModpackContentFields serverModpackContent, ClientStorage storage) {
		if (serverModpackContent == null || serverModpackContent.list == null) throw new IllegalArgumentException("Server modpack content list is null");
		Path activeDirectory = storage.activeDirectory();
		try {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state == null || !Files.isDirectory(activeDirectory, LinkOption.NOFOLLOW_LINKS)) {
				return new UpdateCheckResult(true, serverModpackContent.list, Set.of());
			}
		} catch (IOException e) {
			LOGGER.warn("Cannot read active client generation state", e);
			return new UpdateCheckResult(true, serverModpackContent.list, Set.of());
		}

		LOGGER.info("Verifying content against server list...");
		var start = System.currentTimeMillis();

		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToUpdate = new HashSet<>();
		Set<String> changedOverwriteEditableFiles = findChangedOverwriteEditableFiles(serverModpackContent.list, activeDirectory);

		// Group & Sort Server Files (Optimizes Disk Seek Pattern)
		// Grouping by parent folder ensures we process the disk sequentially (Dir A, then Dir B).
		// TreeMap ensures alphabetical order of directories (HDD friendly).
		Map<Path, List<ModpackJsons.ModpackContentFields.ModpackContentItem>> itemsByDir = serverModpackContent.list.stream()
				.collect(Collectors.groupingBy(item -> SmartFileUtils.getPath(activeDirectory, item.file).getParent(), TreeMap::new, Collectors.toList()));

		try (var cache = FileMetadataCache.open(storage.fileMetadataDirectory())) {

			// Process Directory by Directory
			for (Map.Entry<Path, List<ModpackJsons.ModpackContentFields.ModpackContentItem>> entry : itemsByDir.entrySet()) {
				Path parentDir = entry.getKey();
				List<ModpackJsons.ModpackContentFields.ModpackContentItem> itemsInDir = entry.getValue();

				// If directory is missing, all items in it are missing.
				if (!Files.exists(parentDir)) {
					filesToUpdate.addAll(itemsInDir);
					continue;
				}

				// Read all file attributes in this folder in ONE pass.
				// This map will hold "FileName" -> "Attributes"
				Map<String, BasicFileAttributes> diskFiles = new HashMap<>();

				try {
					// walkFileTree with depth 1 is efficient on Windows (gets attributes for free within a single syscall)
					Files.walkFileTree(parentDir, EnumSet.noneOf(FileVisitOption.class), 1, new SimpleFileVisitor<>() {
						@NotNull
						@Override
						public FileVisitResult visitFile(@NotNull Path file, @NotNull BasicFileAttributes attrs) {
							diskFiles.put(file.getFileName().toString(), attrs);
							return FileVisitResult.CONTINUE;
						}

						@NotNull
						@Override
						public FileVisitResult visitFileFailed(@NotNull Path file, @NotNull IOException exc) {
							return FileVisitResult.CONTINUE; // Handle locked files or permission errors gracefully
						}
					});
				} catch (IOException e) {
					LOGGER.warn("Failed to inspect directory: {}", parentDir, e);
					filesToUpdate.addAll(itemsInDir);
					continue;
				}

				// Check Individual Files in a given directory (Pure RAM logic, 0 IO)
				for (var serverItem : itemsInDir) {
					String fileName = Paths.get(serverItem.file).getFileName().toString();
					BasicFileAttributes diskAttrs = diskFiles.get(fileName);

					if (diskAttrs == null) {
						// File does not exist in the directory map
						filesToUpdate.add(serverItem);
					} else {
						if (serverItem.editable) {
							if (changedOverwriteEditableFiles.contains(serverItem.file)) {
								LOGGER.info("Server changed overwrite-editable file: {}", serverItem.file);
								filesToUpdate.add(serverItem);
							} else {
								LOGGER.debug("Skipping editable file hash check: {}", serverItem.file);
							}
							continue;
						}

						// Check Size first from already read attributes
						if (diskAttrs.size() != Long.parseLong(serverItem.size)) {
							filesToUpdate.add(serverItem);
							continue;
						}

						// Finally, check Hash
						// We pass 'diskAttrs' to the cache so it doesn't need to re-stat the file.
						String hash = cache.getHashOrNullWithAttributes(parentDir.resolve(fileName), diskAttrs);

						if (!serverItem.sha1.equalsIgnoreCase(hash)) filesToUpdate.add(serverItem);
					}
				}
			}
		} catch (Exception e) {
			LOGGER.error("Error during update check", e);
			// Fail-safe: assume update needed if process crashes
			return new UpdateCheckResult(true, serverModpackContent.list, Set.of());
		}

		if (!filesToUpdate.isEmpty()) {
			LOGGER.info("Active projection requires update! Took {} ms", System.currentTimeMillis() - start);
			return new UpdateCheckResult(true, filesToUpdate, changedOverwriteEditableFiles);
		}

		LOGGER.info("Checking for deleted files...");

		Set<String> serverFileSet = serverModpackContent.list.stream().map(item -> UpdatePlanner.normalize(item.file)).collect(Collectors.toSet());
		try {
			try (Stream<Path> projectedFiles = Files.walk(activeDirectory)) {
				for (Path path : projectedFiles.filter(file -> Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)).toList()) {
					String relative = UpdatePlanner.normalize(activeDirectory.relativize(path).toString());
					if (!serverFileSet.contains(relative)) {
						LOGGER.info("Found projected file marked for deletion: {}", relative);
						return new UpdateCheckResult(true, Set.of(), Set.of());
					}
				}
			}
		} catch (IOException e) {
			LOGGER.warn("Failed to inspect the active projection for deleted files", e);
			return new UpdateCheckResult(true, serverModpackContent.list, Set.of());
		}

		LOGGER.info("Active projection is up to date! Took {} ms", System.currentTimeMillis() - start);
		return new UpdateCheckResult(false, Set.of(), Set.of());
	}

	static Set<String> findChangedOverwriteEditableFiles(Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> serverItems, Path projection) {

		Set<String> overwriteEditablePaths = new HashSet<>();
		for (var item : serverItems) {
			if (item.editable && item.overwriteEditable) overwriteEditablePaths.add(item.file);
		}
		if (overwriteEditablePaths.isEmpty()) return Set.of();

		Set<String> changedPaths = new HashSet<>();
		for (var item : serverItems) {
			if (!item.editable || !item.overwriteEditable) continue;
			Path path = SmartFileUtils.getPath(projection, item.file);
			String installedHash = HashUtils.getHash(path);
			if (!item.sha1.equalsIgnoreCase(installedHash)) changedPaths.add(item.file);
		}
		return changedPaths;
	}

	// Scans for files missing from the store. If found in the CWD (and the hash matches), copies them to the store.
	public static void populateStoreFromCWD(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToUpdate, FileMetadataCache cache, ClientStorage storage) {
		for (var entry : filesToUpdate) {
			Path storeFile = storage.objectsDirectory().resolve(entry.sha1);
			long expectedSize = Long.parseLong(entry.size);

			if (isValidFile(storeFile, expectedSize, entry.sha1, cache)) {
				LOGGER.debug("Verified file already exists in store: {}", entry.file);
				continue;
			}
			try {
				if (Files.exists(storeFile)) {
					LOGGER.warn("Evicting corrupt store object {}", entry.sha1);
					Files.delete(storeFile);
				}
			} catch (IOException e) {
				LOGGER.error("Failed to evict corrupt store object {}", entry.sha1, e);
				continue;
			}

			Path fileInCWD = SmartFileUtils.getPathFromCWD(entry.file);
			if (isValidFile(fileInCWD, expectedSize, entry.sha1, cache)) {
				LOGGER.info("Copying existing file from CWD to store: {}", entry.file);
				try {
					SmartFileUtils.copyVerifiedAtomic(fileInCWD, storeFile, expectedSize, entry.sha1);
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
			Path storeFile = storage.objectsDirectory().resolve(entry.sha1);
			if (isValidFile(storeFile, Long.parseLong(entry.size), entry.sha1, cache)) continue;
			if (Files.exists(storeFile)) {
				try {
					LOGGER.warn("Evicting corrupt store object {}", entry.sha1);
					Files.delete(storeFile);
				} catch (IOException e) {
					LOGGER.warn("Failed to evict corrupt store object {}", entry.sha1, e);
				}
			}
			uncachedFiles.add(entry);
		}
		return uncachedFiles;
	}

	private static boolean isValidFile(Path file, long expectedSize, String expectedSha1, FileMetadataCache cache) {
		if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return false;
		try {
			return Files.size(file) == expectedSize && expectedSha1.equalsIgnoreCase(cache.getOrComputeHash(file));
		} catch (IOException e) {
			return false;
		}
	}

	public static ClientConfigJsons.ClientConfigFieldsV3 planModpackSelection(String modpackId, ConnectionJsons.ConnectionInfo connectionInfo,
			ClientConfigJsons.ClientConfigFieldsV3 currentConfig) {
		ModpackId.requireValid(modpackId);
		if (connectionInfo == null || !connectionInfo.isComplete()) throw new IllegalArgumentException("Connection origin or endpoint is missing");

		ClientConfigJsons.ClientConfigFieldsV3 updatedConfig = new ClientConfigJsons.ClientConfigFieldsV3(currentConfig);
		updatedConfig.selectedModpackId = modpackId;
		return updatedConfig;
	}

	public static ClientConfigJsons.ClientConfigFieldsV3 planCachedModpackSelection(String modpackId, ClientConfigJsons.ClientConfigFieldsV3 currentConfig) {
		ModpackId.requireValid(modpackId);
		ClientConfigJsons.ClientConfigFieldsV3 updatedConfig = new ClientConfigJsons.ClientConfigFieldsV3(currentConfig);
		updatedConfig.selectedModpackId = modpackId;
		return updatedConfig;
	}

	public static ManifestFetchResult requestServerModpackContent(ClientStorage storage, ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret, boolean allowAskingUser) {
		try {
			return requestServerModpackContentAsync(storage, connectionInfo, secret, allowAskingUser).get();
		} catch (Exception e) {
			Throwable cause = DownloadClient.unwrap(e);
			LOGGER.error("Error while getting server modpack content: {}", formatThrowable(cause));
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
						LOGGER.error("Error while getting server modpack content: {}", formatThrowable(cause));
						return new ManifestFetchResult(ManifestFetchState.OPERATION_FAILED, null, null, cause);
					}
					return new ManifestFetchResult(ManifestFetchState.SUCCESS, content.get(), client, null);
				})).exceptionally(error -> {
					Throwable cause = DownloadClient.unwrap(error);
					showPinMismatch(cause);
					LOGGER.error("Error while connecting to the server modpack host: {}", formatThrowable(cause));
					return new ManifestFetchResult(connectionFailedState, null, null, cause);
				});
	}

	private static String formatThrowable(Throwable throwable) {
		StringWriter trace = new StringWriter();
		throwable.printStackTrace(new PrintWriter(trace));
		return trace.toString();
	}

	private static CompletableFuture<Optional<ModpackJsons.CompleteModpackContentFields>> fetchModpackContentAsync(ClientStorage storage, DownloadClient client,
			Function<DownloadClient, CompletableFuture<Path>> operation) {
		CompletableFuture<Path> operationFuture;
		try {
			operationFuture = operation.apply(client);
		} catch (Exception e) {
			return CompletableFuture.failedFuture(e);
		}

		return operationFuture.thenApplyAsync(path -> {
			ModpackJsons.CompleteModpackContentFields content = ModpackContentTools.readCompleteFields(path);
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

	private static void showPinMismatch(Throwable throwable) {
		CertificatePinMismatchException mismatch = DownloadClient.findCause(throwable, CertificatePinMismatchException.class);
		if (mismatch == null) return;

		new ScreenManager().error("automodpack.pin.mismatch", "Origin: " + mismatch.getOrigin(),
				"Expected: " + NetUtils.shortenFingerprint(mismatch.getExpectedFingerprint()),
				"Presented: " + NetUtils.shortenFingerprint(mismatch.getPresentedFingerprint()), "automodpack.pin.mismatch.help");
	}

	public static Function<X509Certificate, CompletableFuture<Boolean>> manualValidationCallbackAsync(ConnectionJsons.ConnectionInfo connectionInfo,
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

		var parent = new ScreenManager().getScreen().orElse(null);
		if (parent == null) {
			LOGGER.warn("No screen available, cannot ask user");
			return CompletableFuture.completedFuture(false);
		}

		CompletableFuture<Boolean> result = new CompletableFuture<>();
		Runnable trustAction = () -> {
			CertificateTrustStore.save(connectionInfo.origin, fingerprint, CertificateTrustStore.Reason.TOFU);
			result.complete(true);
		};
		Runnable cancelAction = () -> result.complete(false);
		new ScreenManager().validation(parent, fingerprint, trustAction, cancelAction);
		return result;
	}
}
