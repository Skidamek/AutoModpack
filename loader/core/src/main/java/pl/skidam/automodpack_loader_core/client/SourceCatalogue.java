package pl.skidam.automodpack_loader_core.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.group.ModpackContentType;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.utils.FetchManager;
import pl.skidam.automodpack_core.utils.cache.PlatformCache;

/** Owns the Modrinth/CurseForge source lookup of one updater session and every provenance reference resolved from it. */
final class SourceCatalogue {
	private final Supplier<SelectedModpackTarget> selectedTarget;
	private final PlatformCache platformCache;
	private volatile FetchManager sourceFetchManager;

	SourceCatalogue(Supplier<SelectedModpackTarget> selectedTarget, PlatformCache platformCache) {
		this.selectedTarget = selectedTarget;
		this.platformCache = platformCache;
	}

	ModpackUpdater.SourceAvailability sourceAvailability() {
		FetchManager manager = sourceFetchManager;
		if (manager == null) return new ModpackUpdater.SourceAvailability(0, 0, true, false);
		return new ModpackUpdater.SourceAvailability(manager.totalFiles(), manager.resolvedFiles(), manager.isComplete(), manager.isCancelled());
	}

	List<String> mainPageUrlsForCatalogue(String location, String path) {
		FetchManager manager = sourceFetchManager;
		SelectedModpackTarget target = selectedTarget.get();
		if (manager == null || target == null || path == null || path.isBlank()) return List.of();
		String sha1 = null;
		var items = target.completeTarget().list;
		if (items != null) for (var item : items) if (path.equals(item.file)) {
			sha1 = item.sha1;
			break;
		}
		if (sha1 == null || sha1.isBlank()) return List.of();
		return manager.mainPageUrlsFor(sha1);
	}

	void startSourceFetch() throws IOException {
		if (sourceFetchManager != null) {
			sourceFetchManager.fetch();
			return;
		}
		Map<String, FetchManager.FetchData> unique = new LinkedHashMap<>();
		SelectedModpackTarget target = selectedTarget.get();
		if (target != null) {
			ModpackJsons.ModpackContentFields catalogue = target.completeTarget();
			if (catalogue.list != null)
				for (var item : catalogue.list)
					addSourceFetchData(unique, item.file, item.sha1, item.murmur, item.size, item.type);
		}
		sourceFetchManager = newSourceFetchManager(new ArrayList<>(unique.values()));
		if (sourceFetchManager != null) sourceFetchManager.fetch();
	}

	static boolean gatedJar(String path) {
		return path != null && path.toLowerCase(Locale.ROOT).endsWith(".jar");
	}

	boolean firstPartyHit(String sha1) {
		FetchManager manager = sourceFetchManager;
		if (manager == null || sha1 == null || sha1.isBlank()) return false;
		return manager.hasSource(sha1);
	}

	List<String> unverifiedSelectedJarPaths(SelectedModpackTarget target) {
		if (target == null || target.flatTarget().list == null) return List.of();
		List<String> unverified = new ArrayList<>();
		for (var item : target.flatTarget().list) {
			if (!gatedJar(item.file)) continue;
			if (!firstPartyHit(item.sha1)) unverified.add(item.file);
		}
		return List.copyOf(unverified);
	}

	boolean planWritesUnverifiedJar(UpdatePlan plan) {
		if (plan == null) return false;
		for (UpdatePlan.Operation operation : plan.operations()) {
			if (operation.operation() != UpdatePlan.OperationType.INSTALL_OBJECT) continue;
			if (!gatedJar(operation.relativePath())) continue;
			if (!firstPartyHit(operation.expectedObjectHash())) return true;
		}
		return false;
	}

	FetchManager sourceFetch(Collection<ModpackJsons.ModpackContentFields.ModpackContentItem> items) {
		Map<String, FetchManager.FetchData> unique = new LinkedHashMap<>();
		for (var item : items) addSourceFetchData(unique, item.file, item.sha1, item.murmur, item.size, item.type);
		List<FetchManager.FetchData> fetchData = new ArrayList<>(unique.values());
		if (fetchData.isEmpty()) return null;
		FetchManager current = sourceFetchManager;
		if (current != null && fetchData.stream().allMatch(item -> current.tracks(item.sha1()))) return current;
		if (current != null) current.cancel();
		sourceFetchManager = newSourceFetchManager(fetchData);
		return sourceFetchManager;
	}

	private FetchManager newSourceFetchManager(List<FetchManager.FetchData> fetchData) {
		if (fetchData.isEmpty()) return null;
		FetchManager manager = new FetchManager(fetchData, platformCache);
		manager.fetchAsync();
		return manager;
	}

	private static void addSourceFetchData(Map<String, FetchManager.FetchData> unique, String file, String sha1, String murmur, String size, String type) {
		if (!ModpackContentType.isSourceFetchable(type) || sha1 == null || sha1.isBlank()) return;
		unique.putIfAbsent(sha1, new FetchManager.FetchData(file, sha1, murmur, size, type));
	}

	ChangeSet.ReferenceProvider resolveMainPageReferences(ClientUpdatePlanBuilder.PreparedPlan prepared) {
		FetchManager manager = sourceFetchManager;
		if (manager == null) return (location, path) -> List.of();
		Map<UpdatePlan.FileKey, String> hashes = new LinkedHashMap<>();
		for (UpdatePlan.Operation operation : prepared.plan().operations()) {
			UpdatePlan.FileKey file = new UpdatePlan.FileKey(operation.root(), operation.relativePath());
			if (operation.operation() == UpdatePlan.OperationType.INSTALL_OBJECT && operation.expectedObjectHash() != null) {
				hashes.put(file, operation.expectedObjectHash());
			} else if (operation.operation() == UpdatePlan.OperationType.DELETE) {
				UpdatePlan.FileState original = prepared.originalFiles().get(file);
				if (original != null && original.sha1() != null) hashes.put(file, original.sha1());
			}
		}
		Map<UpdatePlan.FileKey, List<String>> resolved = new LinkedHashMap<>();
		for (var entry : hashes.entrySet()) {
			List<String> mainPageUrls = manager.mainPageUrlsFor(entry.getValue());
			if (mainPageUrls.isEmpty()) continue;
			resolved.put(entry.getKey(), mainPageUrls);
		}
		Map<UpdatePlan.FileKey, List<String>> references = Map.copyOf(resolved);
		return (location, path) -> {
			try {
				return references.getOrDefault(new UpdatePlan.FileKey(UpdatePlan.Root.valueOf(location), path), List.of());
			} catch (IllegalArgumentException e) {
				return List.of();
			}
		};
	}

	/** Stops an incomplete lookup so a cancelled or closing session leaves no network work behind. */
	void cancelIfRunning() {
		FetchManager sourceFetch = sourceFetchManager;
		if (sourceFetch != null && !sourceFetch.isComplete()) sourceFetch.cancel();
	}
}
