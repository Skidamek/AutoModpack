package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Collectors;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientProjectionView;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.utils.ImmutableFiles;
import pl.skidam.automodpack_core.utils.cache.FileCache;

public class ModpackUtils {

	// Modpack may require update even if there's no files to update, because some files may need to be deleted
	public record UpdateCheckResult(boolean requiresUpdate, Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToUpdate) {}

	// Fast and friendly method to check if the modpack is up to date without modifying anything on disk
	public static UpdateCheckResult isUpdate(ModpackJsons.ModpackContentFields serverModpackContent, ClientStorage storage) {
		if (serverModpackContent == null || serverModpackContent.list == null) throw new IllegalArgumentException("Server modpack content list is null");
		if (verificationCannotDecide(serverModpackContent, storage)) return new UpdateCheckResult(true, serverModpackContent.list);

		LOGGER.info("Verifying content against server list...");
		var start = System.currentTimeMillis();

		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToUpdate = new HashSet<>();
		try (var cache = FileCache.open(storage.fileCacheDirectory())) {
			Map<String, UpdatePlan.FileState> live = ClientProjectionView.open(storage).liveFiles(cache);
			for (var serverItem : serverModpackContent.list) {
				if (verifyActiveItem(serverItem, LogicalPath.normalize(serverItem.file), live) == FileVerification.MISMATCH) filesToUpdate.add(serverItem);
			}

			if (filesToUpdate.isEmpty()) {
				LOGGER.info("Checking for deleted files...");
				Set<String> serverFileSet = serverModpackContent.list.stream().map(item -> LogicalPath.normalize(item.file)).collect(Collectors.toSet());
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
		try (var cache = FileCache.open(storage.fileCacheDirectory())) {
			if (new ClientGenerationStore(storage).isDetached(serverModpackContent.modpackId)) {
				LOGGER.info("Modpack is detached; its active files stay editable");
				return;
			}
			Map<String, UpdatePlan.FileState> live = ClientProjectionView.open(storage).liveFiles(cache);
			for (var serverItem : serverModpackContent.list) {
				String relative = LogicalPath.normalize(serverItem.file);
				if (verifyActiveItem(serverItem, relative, live) == FileVerification.MATCH) ImmutableFiles.protect(storage.activePath(relative));
			}
		} catch (Exception e) {
			LOGGER.warn("Failed to re-protect the matching active files", e);
		}
	}

	// True when the per-file scan cannot decide anything and every file must be treated as an update: without an active projection nothing can match, and differing content digests can never pass the per-file scan
	private static boolean verificationCannotDecide(ModpackJsons.ModpackContentFields serverModpackContent, ClientStorage storage) {
		try {
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state == null || !Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) return true;
			if (!serverModpackContent.contentToken.isBlank() && !serverModpackContent.contentToken.equals(state.contentToken)) {
				LOGGER.info("Server modpack content differs from the installed modpack; skipping the per-file verification");
				return true;
			}
			return false;
		} catch (IOException e) {
			LOGGER.warn("Cannot read active client generation state", e);
			return true;
		}
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
			size = serverItem.size;
		} catch (NumberFormatException e) {
			return FileVerification.MISMATCH;
		}
		if (observed.size() != size || serverItem.sha1 == null || !serverItem.sha1.equalsIgnoreCase(observed.sha1())) return FileVerification.MISMATCH;
		return FileVerification.MATCH;
	}

	// Scans for files missing from the store. If found in the CWD (and the hash matches), copies them to the store.
	public static void populateStoreFromCWD(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToUpdate, FileCache cache, ClientStorage storage) {
		for (var entry : filesToUpdate) {
			try {
				ClientObjectStore.Acquisition acquisition = ClientObjectStore.acquireVerified(storage.objectFile(entry.sha1), entry.sha1, entry.size, List.of(storage.gamePath(entry.file)), cache,
						ClientObjectStore.CorruptObjectPolicy.EVICT_AND_REPORT);
				switch (acquisition.outcome()) {
					case PRESENT -> LOGGER.debug("Verified file already exists in store: {}", entry.file);
					case COPIED -> LOGGER.info("Copying existing file from CWD to store: {}", entry.file);
					case EVICTION_FAILED -> LOGGER.error("Failed to evict corrupt store object {}", entry.sha1, acquisition.evictionFailure());
					case MISSING_SOURCE -> {
					}
				}
			} catch (IOException e) {
				LOGGER.error("Failed to copy file from CWD to store: {}", entry.file, e);
			}
		}
	}

	// Returns the set of files that are missing or corrupt in the store.
	public static Set<ModpackJsons.ModpackContentFields.ModpackContentItem> identifyUncachedFiles(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> filesToCheck,
			FileCache cache, ClientStorage storage) throws IOException {
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> uncachedFiles = new HashSet<>();
		for (var entry : filesToCheck) {
			ClientObjectStore.Acquisition acquisition = ClientObjectStore.acquireVerified(storage.objectFile(entry.sha1), entry.sha1, entry.size, List.of(), cache,
					ClientObjectStore.CorruptObjectPolicy.EVICT_AND_REPORT);
			if (acquisition.outcome() == ClientObjectStore.Acquisition.Outcome.EVICTION_FAILED) LOGGER.warn("Failed to evict corrupt store object {}", entry.sha1, acquisition.evictionFailure());
			if (acquisition.present()) continue;
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
}
