package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;
import static pl.skidam.automodpack_core.utils.LegacyClientCacheUtils.*;

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

import org.jetbrains.annotations.NotNull;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.protocol.CertificatePinMismatchException;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.LegacyClientCacheUtils;
import pl.skidam.automodpack_core.utils.ModpackContentTools;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;
import pl.skidam.automodpack_loader_core.screen.ScreenManager;

public class ModpackUtils {

	// Modpack may require update even if there's no files to update, because some files may need to be deleted
	public record UpdateCheckResult(boolean requiresUpdate, Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate,
			Set<String> changedOverwriteEditableFiles) {}

	// Fast and friendly method to check if the modpack is up to date without modifying anything on disk
	public static UpdateCheckResult isUpdate(Jsons.ModpackContentFields serverModpackContent, Path modpackDir) {
		if (serverModpackContent == null || serverModpackContent.list == null) throw new IllegalArgumentException("Server modpack content list is null");

		var optionalClientModpackContentFile = ModpackContentTools.getModpackContentFile(modpackDir);
		if (optionalClientModpackContentFile.isEmpty() || !Files.exists(optionalClientModpackContentFile.get())) {
			return new UpdateCheckResult(true, serverModpackContent.list, Set.of());
		}

		Jsons.ModpackContentFields clientModpackContent = ModpackContentTools.read(optionalClientModpackContentFile.get());
		if (clientModpackContent == null) return new UpdateCheckResult(true, serverModpackContent.list, Set.of());

		LOGGER.info("Verifying content against server list...");
		var start = System.currentTimeMillis();

		Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate = new HashSet<>();
		Set<String> changedOverwriteEditableFiles = findChangedOverwriteEditableFiles(serverModpackContent.list, clientModpackContent);

		// Group & Sort Server Files (Optimizes Disk Seek Pattern)
		// Grouping by parent folder ensures we process the disk sequentially (Dir A, then Dir B).
		// TreeMap ensures alphabetical order of directories (HDD friendly).
		Map<Path, List<Jsons.ModpackContentFields.ModpackContentItem>> itemsByDir = serverModpackContent.list.stream()
				.collect(Collectors.groupingBy(item -> SmartFileUtils.getPath(modpackDir, item.file).getParent(), TreeMap::new, Collectors.toList()));

		try (var cache = FileMetadataCache.open(hashCacheDBFile)) {

			// Process Directory by Directory
			for (Map.Entry<Path, List<Jsons.ModpackContentFields.ModpackContentItem>> entry : itemsByDir.entrySet()) {
				Path parentDir = entry.getKey();
				List<Jsons.ModpackContentFields.ModpackContentItem> itemsInDir = entry.getValue();

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
			LOGGER.info("Modpack {} requires update! Took {} ms", modpackDir, System.currentTimeMillis() - start);
			return new UpdateCheckResult(true, filesToUpdate, changedOverwriteEditableFiles);
		}

		LOGGER.info("Checking for deleted files...");

		Set<String> serverFileSet = serverModpackContent.list.stream().map(item -> item.file).collect(Collectors.toSet());

		for (Jsons.ModpackContentFields.ModpackContentItem clientItem : clientModpackContent.list) {
			if (!serverFileSet.contains(clientItem.file)) {
				LOGGER.info("Found file marked for deletion: {}", clientItem.file);
				return new UpdateCheckResult(true, Set.of(), Set.of());
			}
		}

		LOGGER.info("Modpack {} is up to date! Took {} ms", modpackDir, System.currentTimeMillis() - start);
		return new UpdateCheckResult(false, Set.of(), Set.of());
	}

	static Set<String> findChangedOverwriteEditableFiles(Collection<Jsons.ModpackContentFields.ModpackContentItem> serverItems,
			Jsons.ModpackContentFields installedContent) {
		if (installedContent == null || installedContent.list == null) return Set.of();

		Set<String> overwriteEditablePaths = new HashSet<>();
		for (var item : serverItems) {
			if (item.editable && item.overwriteEditable) overwriteEditablePaths.add(item.file);
		}
		if (overwriteEditablePaths.isEmpty()) return Set.of();

		Map<String, String> installedHashes = new HashMap<>(overwriteEditablePaths.size());
		for (var item : installedContent.list) {
			if (overwriteEditablePaths.contains(item.file)) installedHashes.put(item.file, item.sha1);
		}

		Set<String> changedPaths = new HashSet<>();
		for (var item : serverItems) {
			if (!item.editable || !item.overwriteEditable) continue;
			String installedHash = installedHashes.get(item.file);
			if (!item.sha1.equalsIgnoreCase(installedHash)) changedPaths.add(item.file);
		}
		return changedPaths;
	}

	// Scans for files missing from the store. If found in the CWD (and the hash matches), copies them to the store.
	public static void populateStoreFromCWD(Set<Jsons.ModpackContentFields.ModpackContentItem> filesToUpdate, FileMetadataCache cache) {
		for (var entry : filesToUpdate) {
			Path storeFile = SmartFileUtils.getPath(storeDir, entry.sha1);
			long expectedSize = Long.parseLong(entry.size);

			if (SmartFileUtils.isValidFile(storeFile, expectedSize, entry.sha1)) {
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
			if (SmartFileUtils.isValidFile(fileInCWD, expectedSize, entry.sha1)) {
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
	public static Set<Jsons.ModpackContentFields.ModpackContentItem> identifyUncachedFiles(Set<Jsons.ModpackContentFields.ModpackContentItem> filesToCheck) {
		Set<Jsons.ModpackContentFields.ModpackContentItem> uncachedFiles = new HashSet<>();
		for (var entry : filesToCheck) {
			Path storeFile = SmartFileUtils.getPath(storeDir, entry.sha1);
			if (SmartFileUtils.isValidFile(storeFile, Long.parseLong(entry.size), entry.sha1)) continue;
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

	public static Jsons.ClientConfigFieldsV3 planModpackSelection(String modpackId, Path modpackDirToSelect, Jsons.ConnectionInfo connectionInfo) {
		ModpackId.requireValid(modpackId);
		if (!modpackDirToSelect.getFileName().toString().equals(modpackId)) throw new IllegalArgumentException("Modpack directory does not match its ID");
		if (connectionInfo == null || !connectionInfo.isComplete()) throw new IllegalArgumentException("Connection origin or endpoint is missing");

		Jsons.ClientConfigFieldsV3 updatedConfig = new Jsons.ClientConfigFieldsV3(clientConfig);
		updatedConfig.selectedModpackId = modpackId;
		updatedConfig.modpackConnections.put(modpackId, connectionInfo);
		return updatedConfig;
	}

	public static Path getModpackPath(String modpackId) {
		return modpacksDir.resolve(ModpackId.requireValid(modpackId));
	}

	public static Optional<Jsons.ModpackContentFields> requestServerModpackContent(Jsons.ConnectionInfo connectionInfo, Secrets.Secret secret,
			boolean allowAskingUser) {
		return fetchModpackContent(connectionInfo, secret, (client) -> client.downloadFile(new byte[0], modpackContentTempFile, null), allowAskingUser);
	}

	public static Optional<Jsons.ModpackContentFields> refreshServerModpackContent(Jsons.ConnectionInfo connectionInfo, Secrets.Secret secret,
			byte[][] fileHashes, boolean allowAskingUser) {
		return fetchModpackContent(connectionInfo, secret, (client) -> client.requestRefresh(fileHashes, modpackContentTempFile), allowAskingUser);
	}

	private static Optional<Jsons.ModpackContentFields> fetchModpackContent(Jsons.ConnectionInfo connectionInfo, Secrets.Secret secret,
			Function<DownloadClient, CompletableFuture<Path>> operation, boolean allowAskingUser) {
		if (secret == null) return Optional.empty();
		if (!connectionInfo.isComplete()) throw new IllegalArgumentException("Connection origin or endpoint is missing!");

		try {
			return fetchModpackContentAsync(connectionInfo, secret, operation, manualValidationCallbackAsync(connectionInfo, allowAskingUser)).get();
		} catch (Exception e) {
			LOGGER.error("Error while getting server modpack content", e);
			return Optional.empty();
		}
	}

	public static boolean canConnectModpackHost(Jsons.ConnectionInfo connectionInfo) {
		if (!connectionInfo.isComplete()) throw new IllegalArgumentException("Connection origin or endpoint is missing!");

		try (DownloadClient client = createDownloadClient(connectionInfo, null, 1, manualValidationCallbackAsync(connectionInfo, false)).get()) {
			return client != null;
		} catch (Exception e) {
			LOGGER.error("Error while pinging AutoModpack host server", e);
		}

		return false;
	}

	/**
	 * Returns a callback for use with {@link DownloadClient} that checks for trusted fingerprints in the known hosts
	 * list of the client config. Trust is owned by the player-selected Minecraft origin; the advertised endpoint is
	 * routing information only.
	 *
	 * @param connectionInfo
	 *            the authenticated Minecraft origin and advertised endpoint
	 * @param allowAskingUser
	 *            whether the user should be prompted if a certificate is not trusted
	 * @return the callback
	 */
	public static Function<X509Certificate, Boolean> manualValidationCallback(Jsons.ConnectionInfo connectionInfo, boolean allowAskingUser) {
		Function<X509Certificate, CompletableFuture<Boolean>> callback = manualValidationCallbackAsync(connectionInfo, allowAskingUser);
		return certificate -> {
			try {
				return callback.apply(certificate).get();
			} catch (Exception e) {
				return false;
			}
		};
	}

	// ---- Async versions (non-blocking, used by login packet flow) ----

	public static CompletableFuture<Optional<Jsons.ModpackContentFields>> requestServerModpackContentAsync(Jsons.ConnectionInfo connectionInfo,
			Secrets.Secret secret, boolean allowAskingUser) {
		if (secret == null) return CompletableFuture.completedFuture(Optional.empty());
		if (!connectionInfo.isComplete()) return CompletableFuture.failedFuture(new IllegalArgumentException("Connection origin or endpoint is missing!"));

		return fetchModpackContentAsync(connectionInfo, secret, (client) -> client.downloadFile(new byte[0], modpackContentTempFile, null),
				manualValidationCallbackAsync(connectionInfo, allowAskingUser));
	}

	private static CompletableFuture<Optional<Jsons.ModpackContentFields>> fetchModpackContentAsync(Jsons.ConnectionInfo connectionInfo,
			Secrets.Secret secret, Function<DownloadClient, CompletableFuture<Path>> operation,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) {
		if (secret == null) return CompletableFuture.completedFuture(Optional.empty());
		if (!connectionInfo.isComplete()) return CompletableFuture.failedFuture(new IllegalArgumentException("Connection origin or endpoint is missing!"));

		return createDownloadClient(connectionInfo, secret.secretBytes(), 1, trustCallback).thenCompose(client -> {
			CompletableFuture<Path> operationFuture;
			try {
				operationFuture = operation.apply(client);
			} catch (Exception e) {
				try {
					client.close();
				} catch (Exception ignored) {
				}
				return CompletableFuture.completedFuture(Optional.<Jsons.ModpackContentFields>empty());
			}

			return operationFuture.handleAsync((path, throwable) -> {
				try (client) {
					if (throwable != null) {
						LOGGER.error("Error while getting server modpack content", throwable);
						return Optional.<Jsons.ModpackContentFields>empty();
					}

					var content = Optional.ofNullable(ModpackContentTools.read(path));
					Files.deleteIfExists(modpackContentTempFile);

					if (content.isPresent() && potentiallyMalicious(content.get())) return Optional.<Jsons.ModpackContentFields>empty();

					return content;
				} catch (Exception e) {
					LOGGER.error("Error while getting server modpack content", e);
					return Optional.<Jsons.ModpackContentFields>empty();
				}
			});
		}).exceptionally(e -> {
			showPinMismatch(e);
			LOGGER.error("Error while getting server modpack content", e);
			return Optional.empty();
		});
	}

	private static CompletableFuture<DownloadClient> createDownloadClient(Jsons.ConnectionInfo connectionInfo, byte[] secret, int poolSize,
			Function<X509Certificate, CompletableFuture<Boolean>> trustCallback) {
		return DownloadClient.createAsync(connectionInfo, secret, poolSize, trustCallback).thenApply(client -> {
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

	public static Function<X509Certificate, CompletableFuture<Boolean>> manualValidationCallbackAsync(Jsons.ConnectionInfo connectionInfo,
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

	private static CompletableFuture<Boolean> askUserAboutCertificateAsync(Jsons.ConnectionInfo connectionInfo, String fingerprint) {
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

	public static boolean potentiallyMalicious(Jsons.ModpackContentFields serverModpackContent) {
		if (!ModpackId.isValid(serverModpackContent.modpackId)) {
			LOGGER.error("Modpack content has an invalid modpack ID: '{}'", serverModpackContent.modpackId);
			return true;
		}

		if (serverModpackContent.list == null || serverModpackContent.list.isEmpty()) return false;

		boolean listInvalid = serverModpackContent.list.stream().anyMatch(item -> {
			if (isHashInvalid(item.sha1)) {
				LOGGER.error("Modpack content is invalid: file '{}' has invalid sha1 '{}'", item.file, item.sha1);
				return true;
			}
			if (isUnsafePath(item.file, false)) {
				LOGGER.error("Modpack content is invalid: file path '{}' is unsafe/malicious", item.file);
				return true;
			}
			return false;
		});

		boolean nonModpackFilesToDeleteInvalid = serverModpackContent.nonModpackFilesToDelete.stream().anyMatch(item -> {
			if (isHashInvalid(item.sha1)) {
				LOGGER.error("Modpack content is invalid: file '{}' has invalid sha1 '{}'", item.file, item.sha1);
				return true;
			}
			if (isUnsafePath(item.file, false)) {
				LOGGER.error("Modpack content is invalid: file to delete path '{}' is unsafe/malicious", item.file);
				return true;
			}
			return false;
		});

		return listInvalid || nonModpackFilesToDeleteInvalid;
	}

	// Assumes sha1 hash
	private static boolean isHashInvalid(String hash) {
		if (hash == null || hash.isBlank()) return true;

		// SHA-1 hashes are 40 hexadecimal characters
		return !hash.matches("^[a-fA-F0-9]{40}$");
	}

	private static boolean isUnsafePath(String rawPath, boolean blankIsFine) {
		if (rawPath == null) return true;

		if (!blankIsFine && rawPath.isBlank()) return true;

		// Null Byte Check
		if (rawPath.indexOf('\0') != -1) return true;

		// Most files are just "mods/fabric-api.jar", so they hit this and return false immediately
		if (!rawPath.contains("..")) return false;

		// We must distinguish between malicious "../" and valid names like "super..mario.jar"
		String normalized = rawPath.replace('\\', '/');

		// Edge case
		if (normalized.equals("..") || normalized.equals(".")) return true;

		String[] segments = normalized.split("/");
		for (String segment : segments) {
			if (segment.equals("..")) return true; // Directory traversal
		}

		if (normalized.startsWith("automodpack/") || normalized.startsWith("/automodpack/")) {
			return true; // Trying to mess with automodpack internal files
		}

		return false;
	}
}
