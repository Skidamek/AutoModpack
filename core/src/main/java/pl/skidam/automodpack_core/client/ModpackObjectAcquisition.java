package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import java.util.function.Supplier;
import java.util.stream.Collectors;

import org.jetbrains.annotations.Nullable;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.ClientObjectStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.ByteFormat;
import pl.skidam.automodpack_core.utils.DownloadSource;
import pl.skidam.automodpack_core.utils.FetchManager;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.PlatformCache;

/** Acquires selected-target objects into CAS. The updater owns confirmation and apply; this owns the download queue. */
final class ModpackObjectAcquisition {
	private final ClientStorage storage;
	private final PlatformCache platformCache;
	private final SourceCatalogue sourceCatalogue;
	private final ClientUpdatePlanBuilder planBuilder;
	private final ConnectionJsons.ConnectionInfo connectionInfo;
	private final PackTransport transport;
	private final AtomicBoolean playerCancelled;
	private final Supplier<String> modpackName;
	private final Runnable onPlayerCancel;
	private final Set<String> reservedObjectHashes = new TreeSet<>();
	private final Map<ModpackJsons.ModpackContentFields.ModpackContentItem, List<String>> failedDownloads = new ConcurrentHashMap<>();
	private final Map<ModpackJsons.ModpackContentFields.ModpackContentItem, DownloadManager.FailureCategory> failedDownloadCategories = new ConcurrentHashMap<>();
	private DownloadManager downloadManager;

	ModpackObjectAcquisition(ClientStorage storage, PlatformCache platformCache, SourceCatalogue sourceCatalogue, ClientUpdatePlanBuilder planBuilder,
			ConnectionJsons.ConnectionInfo connectionInfo, PackTransport transport, AtomicBoolean playerCancelled, Supplier<String> modpackName, Runnable onPlayerCancel) {
		this.storage = storage;
		this.platformCache = platformCache;
		this.sourceCatalogue = sourceCatalogue;
		this.planBuilder = planBuilder;
		this.connectionInfo = connectionInfo;
		this.transport = transport;
		this.playerCancelled = playerCancelled;
		this.modpackName = modpackName;
		this.onPlayerCancel = onPlayerCancel;
	}

	void interrupt() {
		DownloadManager manager = downloadManager;
		if (manager != null && manager.isRunning()) manager.cancelAllAndShutdown();
	}

	void release() {
		if (reservedObjectHashes.isEmpty()) return;
		reservedObjectHashes.clear();
		try {
			ClientObjectStore.publishOwnership(storage);
		} catch (IOException e) {
			LOGGER.warn("Could not release in-flight CAS ownership; the next startup will refresh it", e);
		}
	}

	Set<ModpackJsons.ModpackContentFields.ModpackContentItem> missingTargetObjects(ModpackJsons.ModpackContentFields target, FileCache cache) throws IOException {
		Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items = target.list == null ? List.of() : target.list;
		return ModpackUtils.identifyUncachedFiles(uniqueObjects(items), cache, storage);
	}

	/** The download queue needs a complete connection and its client; entry points that can run without a live handshake trip this. */
	void requireTransferSession() throws IOException {
		if (connectionInfo == null || !connectionInfo.isComplete() || transport == null) throw new IOException("Modpack transfer session is unavailable");
	}

	int acquireTargetObjects(ModpackJsons.ModpackContentFields target, FileCache cache, boolean playerFacing) throws Exception {
		failedDownloads.clear();
		failedDownloadCategories.clear();
		Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items = target.list == null ? List.of() : target.list;
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> targetObjects = uniqueObjects(items);
		reserveObjects(targetObjects.stream().map(item -> item.sha1).collect(Collectors.toSet()));
		ModpackUtils.populateStoreFromCWD(targetObjects, cache, storage);
		planBuilder.populateStoreFromCachedLocations(target, cache);
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> missing = ModpackUtils.identifyUncachedFiles(targetObjects, cache, storage);
		if (missing.isEmpty()) {
			LOGGER.info("All {} selected modpack objects are already acquired locally", targetObjects.size());
			return 0;
		}

		requireTransferSession();
		long start = System.currentTimeMillis();
		long totalBytes = missing.stream().mapToLong(item -> item.size).sum();
		FetchManager fetchManager = sourceCatalogue.sourceFetch(missing);
		try {
			if (!downloadModpack(missing, start, totalBytes, fetchManager, playerFacing))
				throw new IOException("One or more selected modpack objects could not be acquired");
		} catch (Exception e) {
			if (downloadManager != null) {
				if (downloadManager.isCancelled()) playerCancelled.compareAndSet(false, true);
				else downloadManager.cancelAllAndShutdown();
			}
			throw e;
		}

		planBuilder.populateStoreFromLogicalProjection(target, cache);
		Set<ModpackJsons.ModpackContentFields.ModpackContentItem> stillMissing = ModpackUtils.identifyUncachedFiles(targetObjects, cache, storage);
		if (!stillMissing.isEmpty()) throw new IOException("Verified selected-target objects are still missing after acquisition: " + stillMissing.size());
		if (!playerFacing) LOGGER.info("Launch apply acquired {} complete modpack objects in {}ms", targetObjects.size(), System.currentTimeMillis() - start);
		return missing.size();
	}

	private void reserveObjects(Set<String> hashes) throws IOException {
		reservedObjectHashes.addAll(hashes.stream().map(hash -> hash.toLowerCase(Locale.ROOT)).toList());
		ClientObjectStore.publishOwnership(storage, Set.copyOf(reservedObjectHashes));
	}

	private boolean downloadModpack(Set<ModpackJsons.ModpackContentFields.ModpackContentItem> files, long startFetching, long totalBytes, @Nullable FetchManager fetchManager,
			boolean playerFacing) throws InterruptedException {
		if (files.isEmpty()) {
			LOGGER.info("No files to download.");
			return true;
		}

		LOGGER.info("In queue left {} files to download ({})", files.size(), ByteFormat.formatSize(totalBytes));
		if (transport == null) return false;
		if (fetchManager != null) {
			if (fetchManager.isComplete()) LOGGER.info("Third-party sources ready ({} of {} files matched)", fetchManager.resolvedFiles(), fetchManager.totalFiles());
			else LOGGER.info("Downloading from the AutoModpack host without waiting for CurseForge/Modrinth lookup");
		}

		downloadManager = new DownloadManager(totalBytes, storage.dataLocation().layout(), platformCache);
		if (playerFacing) ScreenManager.download(downloadManager, modpackName.get(), onPlayerCancel);
		downloadManager.attachTransport(transport);
		for (var serverItem : files) {
			Path downloadFile = storage.activePath(serverItem.file);
			List<DownloadSource> sources = fetchManager == null ? List.of() : fetchManager.sourcesFor(serverItem.sha1);
			Consumer<DownloadManager.FailureCategory> failureCallback = category -> {
				if (category == DownloadManager.FailureCategory.CANCELLED) return;
				failedDownloads.put(serverItem, sources.stream().map(DownloadSource::url).toList());
				failedDownloadCategories.put(serverItem, category);
			};
			downloadManager.download(downloadFile, serverItem.sha1, serverItem.murmur, serverItem.type, sources, serverItem.size, () -> {}, failureCallback);
		}

		downloadManager.joinAll();
		LOGGER.info("Finished downloading files in {}ms", System.currentTimeMillis() - startFetching);
		if (downloadManager.isCancelled()) {
			LOGGER.info("Download canceled");
			return false;
		}
		downloadManager.finish();
		if (failedDownloads.isEmpty()) return true;
		if (failedDownloadCategories.values().stream().anyMatch(category -> category != DownloadManager.FailureCategory.REMOTE_SOURCE)) {
			LOGGER.error("Object acquisition failed locally; regeneration is not allowed: {}", failedDownloadCategories);
			return false;
		}
		LOGGER.error("Remote object acquisition failed for {}; the advertised generation remains unchanged", failedDownloads.keySet());
		return false;
	}

	private static Set<ModpackJsons.ModpackContentFields.ModpackContentItem> uniqueObjects(Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items) {
		Map<String, ModpackJsons.ModpackContentFields.ModpackContentItem> unique = new LinkedHashMap<>();
		for (var item : items) unique.putIfAbsent(item.sha1.toLowerCase(Locale.ROOT), item);
		return new LinkedHashSet<>(unique.values());
	}
}
