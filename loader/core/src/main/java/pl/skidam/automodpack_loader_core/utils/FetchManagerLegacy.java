package pl.skidam.automodpack_loader_core.utils;

import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.utils.CustomThreadFactoryBuilder;
import pl.skidam.automodpack_loader_core.platforms.CurseForgeAPI;
import pl.skidam.automodpack_loader_core.platforms.ModrinthAPI;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class FetchManagerLegacy {
    private static final int MAX_FETCHES_IN_PROGRESS = 20;
    private final ExecutorService FETCH_EXECUTOR = Executors.newFixedThreadPool(MAX_FETCHES_IN_PROGRESS, new CustomThreadFactoryBuilder().setNameFormat("AutoModpackFetch-%d").build());
    private final Map<String, QueuedFetch> queuedFetches = new ConcurrentHashMap<>();
    private final Map<String, FetchData> fetchesInProgress = new ConcurrentHashMap<>();
    public final Map<String, FetchedData> fetchedData = new ConcurrentHashMap<>(); // Don't clear this list on cancel
    public int fetchesDone = 0;
    private int addedToQueue = 0;
    private final Semaphore semaphore = new Semaphore(0);
    private final boolean anyAPIUp;
    private boolean modrinthAPI = true;
    private boolean curseforgeAPI = true;

    public FetchManagerLegacy() {
        if (!(anyAPIUp = APIsUp())) {
            LOGGER.warn("APIs are down, skipping fetches");
        }
    }

    public void fetch(String file, String sha1, String murmur, String fileSize, String fileType) {
        if (!anyAPIUp) {
            return;
        }

        if (!queuedFetches.containsKey(file)) {
            queuedFetches.put(file, new QueuedFetch(sha1, murmur, fileSize, fileType));
            addedToQueue++;
            fetchNext();
        }
    }

    private void fetchNext() {
        if (fetchesInProgress.size() < MAX_FETCHES_IN_PROGRESS && !queuedFetches.isEmpty()) {
            String file = queuedFetches.keySet().iterator().next();
            QueuedFetch queuedFetch = queuedFetches.remove(file);

            if (queuedFetch == null) {
                return;
            }

            Runnable fetchTask = () -> {
                try {
                    FetchedData fetchedData = fetchUrl(queuedFetch, file);

                    if (fetchedData != null) {
                        fetchesDone++;

                        LOGGER.info("Successfully fetched: " + file);

                        synchronized (this.fetchedData) {
                            this.fetchedData.put(queuedFetch.sha1, fetchedData);
                        }
                    } else {
                        LOGGER.warn("Couldn't fetch: " + file);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    fetchesInProgress.remove(file);
                    fetchNext();
                    semaphore.release();
                }
            };

            CompletableFuture<Void> future = CompletableFuture.runAsync(fetchTask, FETCH_EXECUTOR);

            synchronized (fetchesInProgress) {
                fetchesInProgress.put(file, new FetchData(future, queuedFetch.sha1));
            }
        }
    }

    public void joinAll() throws InterruptedException {
        semaphore.acquire(addedToQueue);
        semaphore.release(addedToQueue);
    }

    private FetchedData fetchUrl(QueuedFetch queuedFetch, String file) {

        if (modrinthAPI) {
            ModrinthAPI modrinthFileInfo = ModrinthAPI.getModInfoFromSHA1(queuedFetch.sha1);
            if (modrinthFileInfo != null) {
                String mainPageUrl = ModrinthAPI.getMainPageUrl(modrinthFileInfo.modrinthID(), queuedFetch.fileType);
                return new FetchedData(file, modrinthFileInfo.downloadUrl(), mainPageUrl);
            }
        }

        if (curseforgeAPI) {
            List<CurseForgeAPI> curseforgeFileInfo = CurseForgeAPI.getModInfosFromFingerPrints(Map.of(queuedFetch.sha1, queuedFetch.murmur));
            if (!curseforgeFileInfo.isEmpty()) {
                return new FetchedData(file, curseforgeFileInfo.get(0).downloadUrl(), null);
            }
        }

        return null;
    }

    private boolean APIsUp() {
        String[] urls = {
                "https://api.modrinth.com/",
                "https://api.curseforge.com/"
        };

        return Arrays.stream(urls).parallel().anyMatch(url -> pingURL(url, 3000));
    }

    public boolean pingURL(String url, int timeout) {
        try {
            HttpURLConnection connection = (HttpURLConnection) new URL(url).openConnection();
            connection.setConnectTimeout(timeout);
            connection.setReadTimeout(timeout);
            int responseCode = connection.getResponseCode();
            connection.disconnect();
            if (responseCode != 200) {
                if (url.contains("modrinth")) {
                    this.modrinthAPI = false;
                    GlobalVariables.LOGGER.warn("Modrinth API is down!");
                } else if (url.contains("curseforge")) {
                    this.curseforgeAPI = false;
                    GlobalVariables.LOGGER.warn("Curseforge API is down!");
                }
                return false;
            }
            return true;
        } catch (Exception exception) {
            return false;
        }
    }

    public boolean isAnyFetchInProgress() {
        return !fetchesInProgress.isEmpty() && !queuedFetches.isEmpty();
    }

    public boolean isClosed() {
        return FETCH_EXECUTOR.isShutdown();
    }

    public boolean isFetchInProgress(URL url) {
        return fetchesInProgress.containsKey(url.toString());
    }

    public void cancelAllAndShutdown() {
        queuedFetches.clear();
        fetchesInProgress.forEach((url, downloadData) -> {
            downloadData.future.cancel(true);
        });
        fetchesInProgress.clear();
        fetchesDone = 0;

        FETCH_EXECUTOR.shutdownNow();
        try {
            if (!FETCH_EXECUTOR.awaitTermination(5, TimeUnit.SECONDS)) {
                FETCH_EXECUTOR.shutdownNow();
                if (!FETCH_EXECUTOR.awaitTermination(3, TimeUnit.SECONDS)) {
                    GlobalVariables.LOGGER.error("FETCH EXECUTOR did not terminate");
                }
            }
        } catch (InterruptedException e) {
            FETCH_EXECUTOR.shutdownNow();
        }
    }

    private record QueuedFetch(String sha1, String murmur, String fileSize, String fileType) { }

    private record FetchData(CompletableFuture<Void> future, String sha1) { }

    public record FetchedData (String file, String platformUrl, String mainPageUrl) {
        public String getPlatformUrl() {
            return platformUrl;
        }

        public String getMainPageUrl() {
            return mainPageUrl;
        }
    }
}
