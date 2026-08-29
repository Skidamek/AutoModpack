package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import pl.skidam.automodpack_core.platforms.CurseForgeAPI;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.utils.cache.PlatformMetadataCache;

public class FetchManager {

	// Throw all the sha1 and murmurs
	// Send request to Modrinth with sha1s
	// Send request to CurseForge with murmurs
	// Return the results i guess

	public record FetchData(String file, String sha1, String murmur, String fileSize, String fileType) {}
	public record FetchedData(List<DownloadSource> sources, List<String> mainPageUrls) {}
	public record Datas(FetchData fetchData, FetchedData fetchedData) {}
	private final Map<String, Datas> fetchDatas = new HashMap<>();
	private final PlatformMetadataCache platformMetadataCache;

	public FetchManager(List<FetchData> fetchDatas, PlatformMetadataCache platformMetadataCache) {
		this.platformMetadataCache = platformMetadataCache;
		for (FetchData fetchData : fetchDatas) {
			this.fetchDatas.put(fetchData.sha1,
					new Datas(fetchData, new FetchedData(Collections.synchronizedList(new ArrayList<>(2)), Collections.synchronizedList(new ArrayList<>(2)))));
		}
	}

	// Matrices for screen
	public final AtomicInteger fetchesDone = new AtomicInteger(0);
	private volatile CompletableFuture<Void> completableFuture;
	private volatile boolean complete;
	private volatile boolean cancelled;

	public void cancel() {
		cancelled = true;
		CompletableFuture<Void> future = completableFuture;
		if (future != null) future.cancel(true);
	}

	public CompletableFuture<Void> fetchAsync() {
		CompletableFuture<Void> existing = completableFuture;
		if (existing != null) return existing;
		synchronized (this) {
			if (completableFuture != null) return completableFuture;
			Map<String, String> cfHashes = new HashMap<>();
			List<String> moHashes = new ArrayList<>();

			for (Datas data : fetchDatas.values()) {
				if (data.fetchData.murmur != null && !data.fetchData.murmur.isBlank()) cfHashes.put(data.fetchData.sha1, data.fetchData.murmur);
				moHashes.add(data.fetchData.sha1);
			}

			CompletableFuture<Void> cfFuture = CompletableFuture.runAsync(() -> fetchByMurmur(cfHashes), DownloadClient.NET_EXECUTOR);
			CompletableFuture<Void> moFuture = CompletableFuture.runAsync(() -> fetchBySha1(moHashes), DownloadClient.NET_EXECUTOR);
			completableFuture = CompletableFuture.allOf(cfFuture, moFuture).whenComplete((ignored, failure) -> {
				if (failure == null && !cancelled) randomizeFinalOrder();
				complete = true;
			});
			return completableFuture;
		}
	}

	public void fetch() {
		try {
			fetchAsync().join();
		} catch (CancellationException e) {
			LOGGER.warn("Fetch canceled");
		} catch (CompletionException e) {
			LOGGER.warn("Third-party source lookup failed", e.getCause() == null ? e : e.getCause());
		}
	}

	public boolean isComplete() {
		return complete;
	}

	public boolean isCancelled() {
		return cancelled;
	}

	public int totalFiles() {
		return fetchDatas.size();
	}

	public int resolvedFiles() {
		int resolved = 0;
		for (Datas data : fetchDatas.values()) {
			synchronized (data.fetchedData.sources()) {
				if (!data.fetchedData.sources().isEmpty()) resolved++;
			}
		}
		return resolved;
	}

	private void randomizeFinalOrder() {
		ThreadLocalRandom rng = ThreadLocalRandom.current();
		for (Datas data : fetchDatas.values()) {
			List<DownloadSource> sources = data.fetchedData().sources();

			// Coin filp order
			if (sources.size() == 2 && rng.nextBoolean()) {
				DownloadSource first = sources.get(0);
				sources.set(0, sources.get(1));
				sources.set(1, first);
			}
		}
	}

	private void fetchBySha1(List<String> sha1s) {
		Map<String, PlatformMetadataCache.Record> cached = platformMetadataCache.getAll(sha1s);
		List<String> missing = new ArrayList<>();
		for (String sha1 : sha1s) {
			PlatformMetadataCache.Record record = cached.get(sha1);
			if (record != null && record.modrinth() != null) applyModrinth(fetchDatas.get(sha1), record.modrinth());
			else missing.add(sha1);
		}
		if (missing.isEmpty()) return;

		List<ModrinthAPI> results = ModrinthAPI.getModsInfosFromListOfSHA1(missing);
		if (results == null) return;

		for (ModrinthAPI info : results) {
			Datas datas = fetchDatas.get(info.SHA1Hash());
			if (datas != null) {
				String mainPageUrl = ModrinthAPI.getMainPageUrl(info.modrinthID(), datas.fetchData.fileType);
				platformMetadataCache.putModrinth(info.SHA1Hash(), info, mainPageUrl);
				applyModrinth(datas, info.downloadUrl(), mainPageUrl);
			}
		}
	}

	private void fetchByMurmur(Map<String, String> hashes) {
		Map<String, PlatformMetadataCache.Record> cached = platformMetadataCache.getAll(hashes.keySet());
		Map<String, String> missing = new LinkedHashMap<>();
		for (Map.Entry<String, String> hash : hashes.entrySet()) {
			PlatformMetadataCache.Record record = cached.get(hash.getKey());
			if (record != null && record.curseforge() != null) {
				applyCurseForge(fetchDatas.get(hash.getKey()), record.curseforge().downloadUrl(), record.curseforge().projectPageUrl());
			} else {
				missing.put(hash.getKey(), hash.getValue());
			}
		}
		if (missing.isEmpty()) return;

		List<CurseForgeAPI> results = CurseForgeAPI.getModInfosFromFingerPrints(missing);
		if (results == null) return;

		for (CurseForgeAPI info : results) {
			Datas datas = fetchDatas.get(info.sha1Hash());
			if (datas != null) {
				platformMetadataCache.putCurseForge(info.sha1Hash(), info);
				applyCurseForge(datas, info.downloadUrl(), info.projectPageUrl());
			}
		}
	}

	private void applyModrinth(Datas datas, PlatformMetadataCache.ModrinthEntry entry) {
		applyModrinth(datas, entry.downloadUrl(), entry.mainPageUrl());
	}

	private void applyModrinth(Datas datas, String downloadUrl, String mainPageUrl) {
		if (datas == null) return;
		datas.fetchedData().sources().add(new DownloadSource(downloadUrl, DownloadSource.Provider.MODRINTH));
		addMainPageUrl(datas, mainPageUrl, true);
		fetchesDone.incrementAndGet();
	}

	private void applyCurseForge(Datas datas, String downloadUrl, String projectPageUrl) {
		if (datas == null) return;
		datas.fetchedData().sources().add(new DownloadSource(downloadUrl, DownloadSource.Provider.CURSEFORGE));
		addMainPageUrl(datas, projectPageUrl, false);
		fetchesDone.incrementAndGet();
	}

	private static void addMainPageUrl(Datas datas, String url, boolean preferred) {
		if (url == null || url.isBlank()) return;
		List<String> urls = datas.fetchedData().mainPageUrls();
		synchronized (urls) {
			if (urls.contains(url)) return;
			if (preferred) urls.add(0, url);
			else urls.add(url);
		}
	}

	public Map<String, Datas> getFetchDatas() {
		return fetchDatas;
	}
}
