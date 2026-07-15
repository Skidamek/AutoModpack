package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import pl.skidam.automodpack_core.platforms.CurseForgeAPI;
import pl.skidam.automodpack_core.platforms.ModrinthAPI;

public class FetchManager {

	// Throw all the sha1 and murmurs
	// Send request to Modrinth with sha1s
	// Send request to CurseForge with murmurs
	// Return the results i guess

	public record FetchData(String file, String sha1, String murmur, String fileSize, String fileType) {}
	public record FetchedData(List<DownloadSource> sources, List<String> mainPageUrls) {}
	public record Datas(FetchData fetchData, FetchedData fetchedData) {}
	private final Map<String, Datas> fetchDatas = new HashMap<>();

	public FetchManager(List<FetchData> fetchDatas) {
		for (FetchData fetchData : fetchDatas) {
			this.fetchDatas.put(fetchData.sha1,
					new Datas(fetchData, new FetchedData(Collections.synchronizedList(new ArrayList<>(2)), Collections.synchronizedList(new ArrayList<>(2)))));
		}
	}

	// Matrices for screen
	public final AtomicInteger fetchesDone = new AtomicInteger(0);
	private CompletableFuture<Void> completableFuture;

	public void cancel() {
		if (completableFuture != null) completableFuture.cancel(true);
	}

	public void fetch() {
		Map<String, String> cfHashes = new HashMap<>();
		List<String> moHashes = new ArrayList<>();

		for (Datas data : fetchDatas.values()) {
			if (data.fetchData.murmur != null && !data.fetchData.murmur.isBlank()) { cfHashes.put(data.fetchData.sha1, data.fetchData.murmur); }
			moHashes.add(data.fetchData.sha1);
		}

		try {
			CompletableFuture<Void> cfFuture = CompletableFuture.runAsync(() -> fetchByMurmur(cfHashes));
			CompletableFuture<Void> moFuture = CompletableFuture.runAsync(() -> fetchBySha1(moHashes));

			completableFuture = CompletableFuture.allOf(cfFuture, moFuture);
			completableFuture.join();

			randomizeFinalOrder();
		} catch (CancellationException e) {
			LOGGER.warn("Fetch canceled");
		} catch (Exception e) {
			e.printStackTrace();
		}
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
		List<ModrinthAPI> results = ModrinthAPI.getModsInfosFromListOfSHA1(sha1s);
		if (results == null) return;

		for (ModrinthAPI info : results) {
			Datas datas = fetchDatas.get(info.SHA1Hash());
			if (datas != null) {
				datas.fetchedData().sources().add(new DownloadSource(info.downloadUrl(), DownloadSource.Provider.MODRINTH));
				String mainPageUrl = ModrinthAPI.getMainPageUrl(info.modrinthID(), datas.fetchData.fileType);
				datas.fetchedData().mainPageUrls().add(mainPageUrl);
				fetchesDone.incrementAndGet();
			}
		}
	}

	private void fetchByMurmur(Map<String, String> hashes) {
		List<CurseForgeAPI> results = CurseForgeAPI.getModInfosFromFingerPrints(hashes);
		if (results == null) return;

		for (CurseForgeAPI info : results) {
			Datas datas = fetchDatas.get(info.sha1Hash());
			if (datas != null) {
				datas.fetchedData().sources().add(new DownloadSource(info.downloadUrl(), DownloadSource.Provider.CURSEFORGE));
				fetchesDone.incrementAndGet();
			}
		}
	}

	public Map<String, Datas> getFetchDatas() {
		return fetchDatas;
	}
}
