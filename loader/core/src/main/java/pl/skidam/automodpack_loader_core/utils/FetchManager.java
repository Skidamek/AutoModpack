package pl.skidam.automodpack_loader_core.utils;

import pl.skidam.automodpack_loader_core.platforms.CurseForgeAPI;
import pl.skidam.automodpack_loader_core.platforms.ModrinthAPI;

import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class FetchManager {

    // Throw all the sha1 and murmurs
    // Send request to Modrinth with sha1s
    // Send request to CurseForge with murmurs
    // Return the results i guess

    public record FetchData(String file, String sha1, String murmur, String fileSize, String fileType) { }
    public record FetchedData (List<String> urls, List<String> mainPageUrls) { }
    public record Datas(FetchData fetchData, FetchedData fetchedData) { }
    private final Map<String, Datas> fetchDatas = new HashMap<>();
    public FetchManager(List<FetchData> fetchDatas) {
        for (FetchData fetchData : fetchDatas) {
            this.fetchDatas.put(fetchData.sha1, new Datas(fetchData, new FetchedData(new ArrayList<>(), new ArrayList<>())));
        }
    }

    // Matrices for screen
    public int fetchesDone = 0;
    private CompletableFuture<Void> completableFuture;

    public void cancel() {
        completableFuture.cancel(true);
    }

    public void fetch() {
        // make map of sha1s and murmurs
        Map<String, String> cf = new HashMap<>();
        List<String> mo = new ArrayList<>();
        for (Map.Entry<String, Datas> entry : fetchDatas.entrySet()) {
            FetchData fetchData = entry.getValue().fetchData();
            if (fetchData.murmur != null && !fetchData.murmur.isBlank()) {
                cf.put(fetchData.sha1, fetchData.murmur);
            }

            mo.add(fetchData.sha1);
        }

        try {
            completableFuture = CompletableFuture.runAsync(() -> {
                fetchByMurmur(cf);
                fetchBySha1(mo);
            });
            completableFuture.join();
        } catch (CancellationException e) {
            LOGGER.warn("Fetch canceled");
        } catch (Exception e) {
            e.printStackTrace();
        }
    }

    private void fetchBySha1(List<String> sha1s) {
        List<ModrinthAPI> modrinthFileInfos = ModrinthAPI.getModsInfosFromListOfSHA1(sha1s);
        if (modrinthFileInfos == null) return;
        for (ModrinthAPI modrinthFileInfo : modrinthFileInfos) {
            String sha1 = modrinthFileInfo.SHA1Hash();
            final Datas datas = fetchDatas.get(sha1);
            String mainPageUrl = ModrinthAPI.getMainPageUrl(modrinthFileInfo.modrinthID(), datas.fetchData.fileType);
            datas.fetchedData().urls().add(modrinthFileInfo.downloadUrl());
            datas.fetchedData().mainPageUrls().add(mainPageUrl);
            fetchDatas.put(sha1, datas);
            fetchesDone++;
        }
    }

    private void fetchByMurmur(Map<String, String> hashes) {
        List<CurseForgeAPI> cfFileInfos = CurseForgeAPI.getModInfosFromFingerPrints(hashes);
        if (cfFileInfos == null) return;
        for (CurseForgeAPI cfFileInfo : cfFileInfos) {
            String sha1 = cfFileInfo.sha1Hash();
            final Datas datas = fetchDatas.get(sha1);
            datas.fetchedData().urls().add(cfFileInfo.downloadUrl());
            fetchDatas.put(sha1, datas);
            fetchesDone++;
        }
    }

    public Map<String, Datas> getFetchDatas() {
        return fetchDatas;
    }
}
