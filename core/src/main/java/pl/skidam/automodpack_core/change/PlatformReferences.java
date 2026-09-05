package pl.skidam.automodpack_core.change;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

import pl.skidam.automodpack_core.utils.cache.PlatformCache;

/** Resolves Modrinth and CurseForge project pages for file digests from the shared platform cache. */
public final class PlatformReferences {
	private PlatformReferences() {}

	/** Returns the cached project pages of one sha1 digest, Modrinth first. */
	public static List<Page> cachedPages(PlatformCache cache, String sha1) {
		Objects.requireNonNull(cache, "platform cache");
		if (sha1 == null || sha1.isBlank()) return List.of();
		return pagesOf(cache.getAll(List.of(sha1)).get(sha1));
	}

	/** Attaches the cached project pages of every occurrence's file digests as references, opening a short-lived cache instance. */
	public static ChangeSet withCachedReferences(ChangeSet changes, Path platformCacheDirectory) {
		try (PlatformCache cache = PlatformCache.open(platformCacheDirectory)) {
			return withCachedReferences(changes, cache);
		} catch (IOException | RuntimeException e) {
			return changes;
		}
	}

	public static ChangeSet withCachedReferences(ChangeSet changes, PlatformCache cache) {
		Objects.requireNonNull(changes, "change set");
		Objects.requireNonNull(cache, "platform cache");
		Set<String> hashes = new LinkedHashSet<>();
		for (ChangeSet.Change change : changes.changes())
			for (ChangeSet.Occurrence occurrence : change.occurrences()) {
				if (occurrence.beforeHash() != null) hashes.add(occurrence.beforeHash());
				if (occurrence.afterHash() != null) hashes.add(occurrence.afterHash());
			}
		if (hashes.isEmpty()) return changes;
		Map<String, List<Page>> pagesBySha1 = cachedPagesBySha1(cache, hashes);
		if (pagesBySha1.isEmpty()) return changes;
		Map<String, List<String>> referencesByPath = new LinkedHashMap<>();
		for (ChangeSet.Change change : changes.changes()) {
			Set<String> references = new LinkedHashSet<>();
			for (ChangeSet.Occurrence occurrence : change.occurrences()) {
				addPages(references, pagesBySha1.get(occurrence.beforeHash()));
				addPages(references, pagesBySha1.get(occurrence.afterHash()));
			}
			if (!references.isEmpty()) referencesByPath.put(change.logicalPath(), List.copyOf(references));
		}
		if (referencesByPath.isEmpty()) return changes;
		return changes.withReferences((location, path) -> referencesByPath.getOrDefault(path, List.of()));
	}

	private static Map<String, List<Page>> cachedPagesBySha1(PlatformCache cache, Collection<String> sha1s) {
		Map<String, List<Page>> pages = new LinkedHashMap<>();
		Map<String, PlatformCache.Record> records = cache.getAll(sha1s);
		for (String sha1 : sha1s) {
			List<Page> found = pagesOf(records.get(sha1));
			if (!found.isEmpty()) pages.put(sha1.toLowerCase(Locale.ROOT), found);
		}
		return pages;
	}

	private static void addPages(Set<String> references, List<Page> pages) {
		if (pages == null) return;
		for (Page page : pages) references.add(page.url());
	}

	private static List<Page> pagesOf(PlatformCache.Record record) {
		if (record == null) return List.of();
		List<Page> pages = new ArrayList<>();
		if (record.modrinth() != null && isPresent(record.modrinth().mainPageUrl())) pages.add(new Page("modrinth", record.modrinth().mainPageUrl()));
		if (record.curseforge() != null && isPresent(record.curseforge().projectPageUrl())) pages.add(new Page("curseforge", record.curseforge().projectPageUrl()));
		return List.copyOf(pages);
	}

	private static boolean isPresent(String url) {
		return url != null && !url.isBlank();
	}

	/** One cached project page; the platform id doubles as the {@code automodpack.browser.<platform>} label key suffix. */
	public record Page(String platform, String url) {
		public Page {
			Objects.requireNonNull(platform, "page platform");
			Objects.requireNonNull(url, "page url");
		}
	}
}
