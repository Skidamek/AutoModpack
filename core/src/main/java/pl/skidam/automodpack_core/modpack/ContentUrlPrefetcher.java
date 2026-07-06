package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.utils.FetchManager;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import static pl.skidam.automodpack_core.Constants.LOGGER;

/**
 * Fills modrinth/curseforge download and project-page urls into generated content items,
 * so clients get them for free with the content json instead of each client querying the
 * platform APIs on every install/update. Clients still fetch on their own when the fields
 * are absent (older servers), keeping the platforms as fallback rather than the hot path.
 */
public class ContentUrlPrefetcher {

    // sha1 -> fetched result, so regenerations only query the APIs for files that changed.
    // Negative results are cached too (empty urls) - a file not on the platforms won't
    // appear there later under the same hash.
    private static final Map<String, FetchManager.FetchedData> resultsBySha1 = new ConcurrentHashMap<>();

    private ContentUrlPrefetcher() {
    }

    public static void prefetch(Collection<Jsons.ModpackContentFields.ModpackContentItem> items) {
        try {
            List<FetchManager.FetchData> toFetch = new ArrayList<>();
            for (var item : items) {
                if (!isPlatformType(item.type))
                    continue;
                FetchManager.FetchedData cached = resultsBySha1.get(item.sha1);
                if (cached != null) {
                    apply(item, cached);
                } else {
                    toFetch.add(new FetchManager.FetchData(item.file, item.sha1, item.murmur, item.size, item.type));
                }
            }

            if (toFetch.isEmpty())
                return;

            long start = System.currentTimeMillis();
            FetchManager fetchManager = new FetchManager(toFetch);
            fetchManager.fetch();

            int found = 0;
            for (var item : items) {
                if (!isPlatformType(item.type) || resultsBySha1.containsKey(item.sha1))
                    continue;
                var datas = fetchManager.getFetchDatas().get(item.sha1);
                FetchManager.FetchedData fetched = datas == null
                        ? new FetchManager.FetchedData(List.of(), List.of())
                        : datas.fetchedData();
                resultsBySha1.put(item.sha1, fetched);
                if (apply(item, fetched))
                    found++;
            }

            LOGGER.info("Prefetched platform urls for {}/{} files in {}ms", found, toFetch.size(), System.currentTimeMillis() - start);
        } catch (Exception e) {
            LOGGER.warn("Failed to prefetch platform urls - clients will fetch them themselves", e);
        }
    }

    private static boolean isPlatformType(String type) {
        return "mod".equals(type) || "shader".equals(type) || "resourcepack".equals(type);
    }

    private static boolean apply(Jsons.ModpackContentFields.ModpackContentItem item, FetchManager.FetchedData fetched) {
        if (fetched.urls().isEmpty())
            return false;
        item.dlUrls = List.copyOf(fetched.urls());
        item.pageUrls = List.copyOf(fetched.mainPageUrls());
        return true;
    }
}
