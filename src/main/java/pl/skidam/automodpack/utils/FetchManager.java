/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import pl.skidam.automodpack.GlobalVariables;
import pl.skidam.automodpack.client.ScreenTools;
import pl.skidam.automodpack.platforms.CurseForgeAPI;
import pl.skidam.automodpack.platforms.ModrinthAPI;

import java.net.HttpURLConnection;
import java.net.URL;
import java.util.*;
import java.util.concurrent.*;

import static pl.skidam.automodpack.GlobalVariables.LOGGER;

public class FetchManager {
    private static final int MAX_FETCHES_IN_PROGRESS = 20;
    private final ExecutorService FETCH_EXECUTOR = Executors.newFixedThreadPool(MAX_FETCHES_IN_PROGRESS, new ThreadFactoryBuilder().setNameFormat("AutoModpackFetch-%d").build());
    private final Map<String, QueuedFetch> queuedFetches = new ConcurrentHashMap<>();
    private final Map<String, FetchData> fetchesInProgress = new ConcurrentHashMap<>();
    public final Map<String, FetchedData> fetchedData = new ConcurrentHashMap<>(); // Don't clear this list on cancel
    public int fetchesDone = 0;
    private int addedToQueue = 0;
    private final Semaphore semaphore = new Semaphore(0);
    private final boolean anyAPIUp;
    private boolean modrinthAPI = true;
    private boolean curseforgeAPI = true;

    public FetchManager() {
        if (!(anyAPIUp = APIsUp())) {
            LOGGER.warn("APIs are down, skipping fetches");
        } else if (!ScreenTools.getScreenString().contains("fetchscreen")) {
            ScreenTools.setTo.fetch();
        }
    }

    public void fetch(String serverUrl, String sha1, String murmur, String fileSize, String fileType) {
        if (!anyAPIUp) {
            return;
        }

        if (!queuedFetches.containsKey(serverUrl)) {
            queuedFetches.put(serverUrl, new QueuedFetch(sha1, murmur, fileSize, fileType));
            addedToQueue++;
            fetchNext();
        }
    }

    private void fetchNext() {
        if (fetchesInProgress.size() < MAX_FETCHES_IN_PROGRESS && !queuedFetches.isEmpty()) {
            String url = queuedFetches.keySet().iterator().next();
            QueuedFetch queuedFetch = queuedFetches.remove(url);

            if (queuedFetch == null) {
                return;
            }

            Runnable fetchTask = () -> {
                try {
                    FetchedData fetchedData = fetchUrl(queuedFetch, url);

                    if (fetchedData != null) {
                        fetchesDone++;

                        LOGGER.info("Successfully fetched: " + url);

                        synchronized (this.fetchedData) {
                            this.fetchedData.put(queuedFetch.sha1, fetchedData);
                        }
                    } else {
                        LOGGER.warn("Couldn't fetch: " + url);
                    }
                } catch (Exception e) {
                    e.printStackTrace();
                } finally {
                    fetchesInProgress.remove(url);
                    fetchNext();
                    semaphore.release();
                }
            };

            CompletableFuture<Void> future = CompletableFuture.runAsync(fetchTask, FETCH_EXECUTOR);

            synchronized (fetchesInProgress) {
                fetchesInProgress.put(url, new FetchData(future, queuedFetch.sha1));
            }
        }
    }

    public void joinAll() throws InterruptedException {
        semaphore.acquire(addedToQueue);
        semaphore.release(addedToQueue);
    }

    private FetchedData fetchUrl(QueuedFetch queuedFetch, String serverUrl) {

        if (modrinthAPI) {
            ModrinthAPI modrinthFileInfo = ModrinthAPI.getModInfoFromSHA512(queuedFetch.sha1);
            if (modrinthFileInfo != null) {
                String mainPageUrl = ModrinthAPI.getMainPageUrl(modrinthFileInfo.modrinthID, queuedFetch.fileType);
                return new FetchedData(serverUrl, modrinthFileInfo.downloadUrl, mainPageUrl);
            }
        }

        if (curseforgeAPI) {
            CurseForgeAPI curseforgeFileInfo = CurseForgeAPI.getModInfoFromMurmur(queuedFetch.murmur, queuedFetch.fileSize);
            if (curseforgeFileInfo != null) {
                // no sé cómo conseguir este Url...
                return new FetchedData(serverUrl, curseforgeFileInfo.downloadUrl, null);
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

    public boolean isAnyDownloadInProgress() {
        return !fetchesInProgress.isEmpty() && !queuedFetches.isEmpty();
    }

    public boolean isClosed() {
        return FETCH_EXECUTOR.isShutdown();
    }

    public boolean isDownloadInProgress(URL url) {
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

    private static class QueuedFetch {
        String sha1;
        String murmur;
        String fileSize;
        String fileType;

        public QueuedFetch(String sha1, String murmur, String fileSize, String fileType) {
            this.sha1 = sha1;
            this.murmur = murmur;
            this.fileSize = fileSize;
            this.fileType = fileType;
        }
    }

    private static class FetchData {
        CompletableFuture<Void> future;
        String sha1;

        public FetchData(CompletableFuture<Void> future, String sha1) {
            this.future = future;
            this.sha1 = sha1;
        }
    }

    public static class FetchedData {
        String serverUrl;
        String platformUrl;
        String mainPageUrl;

        public FetchedData(String serverUrl, String platformUrl, String mainPageUrl) {
            this.serverUrl = serverUrl;
            this.platformUrl = platformUrl;
            this.mainPageUrl = mainPageUrl;
        }

        public String getPlatformUrl() {
            return platformUrl;
        }

        public String getMainPageUrl() {
            return mainPageUrl;
        }
    }
}
