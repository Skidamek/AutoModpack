package pl.skidam.automodpack_loader_core.utils;

import pl.skidam.automodpack_loader_core.platforms.CurseForgeAPI;
import pl.skidam.automodpack_loader_core.platforms.ModrinthAPI;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public class FetchManager {

    // Throw all the sha1 and murmurs
    // Send request to Modrinth with sha1s
    // Send request to CurseForge with murmurs
    // Return the results i guess

    public record FetchData(String file, String sha1, String murmur, long fileSize, String fileType) { }
    public record FetchedData (List<String> urls, List<String> mainPageUrls) { }
    public record Datas(FetchData fetchData, FetchedData fetchedData) { }
    private final Map<String, Datas> fetchDatas = new HashMap<>();

    public FetchManager(List<FetchData> fetchDatas) {
        for (FetchData fetchData : fetchDatas) {
            this.fetchDatas.put(fetchData.sha1, new Datas(fetchData, new FetchedData(
                    Collections.synchronizedList(new ArrayList<>(2)),
                    Collections.synchronizedList(new ArrayList<>(2)))
            ));
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
            if (data.fetchData.murmur != null && !data.fetchData.murmur.isBlank()) {
                cfHashes.put(data.fetchData.sha1, data.fetchData.murmur);
            }
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
            List<String> urls = data.fetchedData().urls();

            // Coin filp order
            if (urls.size() == 2 && rng.nextBoolean()) {
                String first = urls.get(0);
                urls.set(0, urls.get(1));
                urls.set(1, first);
            }
        }
    }

    private void fetchBySha1(List<String> sha1s) {
        List<ModrinthAPI> results = ModrinthAPI.getModsInfosFromListOfSHA1(sha1s);
        if (results == null) return;

        for (ModrinthAPI info : results) {
            Datas datas = fetchDatas.get(info.SHA1Hash());
            if (datas != null) {
                datas.fetchedData().urls().add(info.downloadUrl());
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
                datas.fetchedData().urls().add(info.downloadUrl());
                fetchesDone.incrementAndGet();
            }
        }
    }

    public Map<String, Datas> getFetchDatas() {
        return fetchDatas;
    }
}