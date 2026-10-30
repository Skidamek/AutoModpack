package pl.skidam.automodpack_core.modpack.candidate;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
import pl.skidam.automodpack_core.loader.ModEnvironment;
import pl.skidam.automodpack_core.loader.ModFileCache;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.ServerSideModExclusion;
import pl.skidam.automodpack_core.platforms.PlatformSideVerdicts;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;

public final class ModpackCandidateScanner {
	private final StableSourceSnapshotter sourceSnapshotter = new StableSourceSnapshotter();
	private final PlatformSideVerdicts sideVerdicts;

	public ModpackCandidateScanner() {
		this(null);
	}

	/** The platform side stage is a collaborator, so the scanner holds it; without one it asks no platform anything. */
	public ModpackCandidateScanner(PlatformSideVerdicts sideVerdicts) {
		this.sideVerdicts = sideVerdicts;
	}

	public ModpackCandidate scan(Request request) throws CandidateBuildException {
		Objects.requireNonNull(request);
		if (request.modpack() == null || request.modpack().isEmpty()) throw new CandidateBuildException("No groups are configured");
		Map<String, ServerConfigJsons.GroupDeclaration> declarations = new LinkedHashMap<>();
		Map<String, String> categoryByGroup = new LinkedHashMap<>();
		Map<String, GroupRules> rulesByGroup = new LinkedHashMap<>();
		for (var categoryEntry : request.modpack().entrySet()) {
			var category = categoryEntry.getValue();
			if (category == null) throw new CandidateBuildException("Category '" + categoryEntry.getKey() + "' has no declaration");
			for (var entry : category.entrySet()) {
				try {
					GroupManifestValidator.requireIdentifier(entry.getKey());
				} catch (IllegalArgumentException e) {
					throw new CandidateBuildException(e.getMessage(), e);
				}
				if (declarations.containsKey(entry.getKey())) throw new CandidateBuildException("Group '" + entry.getKey() + "' is declared more than once");
				if (entry.getValue() == null) throw new CandidateBuildException("Group '" + entry.getKey() + "' has no declaration");
				declarations.put(entry.getKey(), entry.getValue());
				categoryByGroup.put(entry.getKey(), categoryEntry.getKey());
				rulesByGroup.put(entry.getKey(), compileRules(entry.getKey(), entry.getValue()));
			}
		}

		List<ExcludedCandidate> ruleExclusions = new ArrayList<>();
		Map<String, SourcePair> sources = new TreeMap<>();
		for (var entry : declarations.entrySet()) {
			String groupId = entry.getKey();
			ServerConfigJsons.GroupDeclaration declaration = entry.getValue();
			GroupRules groupRules = rulesByGroup.get(groupId);
			Path groupDirectory = request.groupRoot().resolve(groupId).normalize();
			if (!groupDirectory.startsWith(request.groupRoot().toAbsolutePath().normalize()))
				throw new CandidateBuildException("Group directory escapes host-modpack: " + groupId);
			for (var file : walk(groupDirectory).entrySet()) {
				// The group directory is included in full; exclude is the only way to leave content out of it.
				PathRuleSet.Decision excluded = groupRules.excluded(file.getKey());
				if (excluded.matched()) {
					ruleExclusions.add(new ExcludedCandidate(new CandidateSource(groupId, file.getKey(), CandidateSource.SourceKind.GROUP_DIRECTORY, file.getValue(), null),
							ExcludedCandidate.Reason.EXCLUDED_BY_RULE, "excluded by " + excluded.decisiveRule()));
					continue;
				}
				CandidateSource source = new CandidateSource(groupId, file.getKey(), CandidateSource.SourceKind.GROUP_DIRECTORY, file.getValue(), null);
				sources.computeIfAbsent(ModpackCandidate.provenanceKey(groupId, file.getKey()), ignored -> new SourcePair()).explicit = source;
			}
		}

		List<GroupRules> synchronizedGroups = rulesByGroup.values().stream().filter(rules -> !rules.fromServer().isEmpty()).toList();
		if (!synchronizedGroups.isEmpty()) {
			Set<String> scanRoots = new TreeSet<>();
			for (GroupRules rules : synchronizedGroups) scanRoots.addAll(rules.fromServer().safeScanRoots());
			Set<String> minimalRoots = minimalScanRoots(scanRoots);
			Map<String, List<String>> groupsByScanRoot = indexGroupsByScanRoot(minimalRoots, rulesByGroup);
			for (String scanRoot : minimalRoots) {
				Path root = (scanRoot.isEmpty() ? request.serverRoot() : request.serverRoot().resolve(scanRoot)).normalize();
				if (!root.startsWith(request.serverRoot())) throw new CandidateBuildException("Synchronized scan root escapes server root: " + scanRoot);
				for (var file : walk(root, request.serverRoot()).entrySet()) {
					for (String groupId : groupsByScanRoot.get(scanRoot)) {
						GroupRules groupRules = rulesByGroup.get(groupId);
						PathRuleSet.Decision included = groupRules.synced(file.getKey());
						if (!included.matched()) continue;
						PathRuleSet.Decision excluded = groupRules.excluded(file.getKey());
						if (excluded.matched()) {
							ruleExclusions.add(new ExcludedCandidate(new CandidateSource(groupId, file.getKey(), CandidateSource.SourceKind.SYNCED_ROOT, file.getValue(), included.decisiveRule()),
									ExcludedCandidate.Reason.EXCLUDED_BY_RULE, "excluded by " + excluded.decisiveRule()));
							continue;
						}
						CandidateSource source = new CandidateSource(groupId, file.getKey(), CandidateSource.SourceKind.SYNCED_ROOT, file.getValue(), included.decisiveRule());
						SourcePair pair = sources.computeIfAbsent(ModpackCandidate.provenanceKey(groupId, file.getKey()), ignored -> new SourcePair());
						if (pair.synced != null && !pair.synced.sourcePath().equals(source.sourcePath()))
							throw new CandidateBuildException("Multiple synchronized sources resolve to group '" + groupId + "' path '" + file.getKey() + "'");
						pair.synced = source;
					}
				}
			}
		}

		List<CompletableFuture<PathResult>> futures = new ArrayList<>();
		try {
			for (SourcePair pair : sources.values()) futures.add(CompletableFuture.supplyAsync(() -> {
				try {
					return process(pair, rulesByGroup.get(pair.groupId()), exclusionMode(declarations.get(pair.groupId())), request);
				} catch (CandidateBuildException e) {
					throw new CompletionException(e);
				}
			}, request.executor()));
		} catch (RejectedExecutionException e) {
			CandidateBuildException submissionFailure = new CandidateBuildException("Failed to submit candidate task", e);
			List<PathResult> completed = new ArrayList<>();
			CandidateBuildException taskFailure = drain(futures, completed);
			cleanup(completed, submissionFailure);
			if (taskFailure != null) submissionFailure.addSuppressed(taskFailure);
			throw submissionFailure;
		}

		List<PathResult> results = new ArrayList<>();
		CandidateBuildException taskFailure = drain(futures, results);
		if (taskFailure != null) {
			cleanup(results, taskFailure);
			throw taskFailure;
		}
		results.sort(Comparator.comparing(result -> result.selected == null ? result.sourceForOrdering() : result.selected));
		applyPlatformSideVerdicts(results, request);

		Map<String, Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields>> filesByGroup = new TreeMap<>();
		for (String groupId : declarations.keySet()) filesByGroup.put(groupId, new TreeMap<>());
		Map<String, StagedObject> objects = new TreeMap<>();
		Map<String, CandidateProvenance> provenance = new TreeMap<>();
		List<ExcludedCandidate> exclusions = new ArrayList<>(ruleExclusions);
		try {
			for (PathResult result : results) {
				exclusions.addAll(result.exclusions);
				if (result.selected == null || result.file == null) continue;
				GroupManifest.GroupFile file = result.file;
				filesByGroup.get(result.selected.groupId()).put(result.selected.logicalPath(), GroupManifest.publishedFile(result.selected.logicalPath(), file));
				if (result.object != null) {
					StagedObject redundant = objects.putIfAbsent(file.sha1().toLowerCase(Locale.ROOT), result.object);
					if (redundant != null) result.object.delete();
				}
				provenance.put(ModpackCandidate.provenanceKey(result.selected.groupId(), result.selected.logicalPath()), result.provenance);
			}

			ModpackJsons.CompleteModpackContentFields fields = new ModpackJsons.CompleteModpackContentFields();
			fields.modpackId = request.modpackId();
			fields.modpackName = request.modpackName();
			fields.automodpackVersion = request.automodpackVersion();
			fields.loader = request.loader();
			fields.loaderVersion = request.loaderVersion();
			fields.mcVersion = request.mcVersion();
			Map<String, Map<String, ModpackJsons.CompleteModpackContentFields.ModpackGroupFields>> categories = new LinkedHashMap<>();
			for (var entry : declarations.entrySet()) {
				ServerConfigJsons.GroupDeclaration declaration = entry.getValue();
				ModpackJsons.CompleteModpackContentFields.ModpackGroupFields group = new ModpackJsons.CompleteModpackContentFields.ModpackGroupFields();
				group.displayName = declaration.displayName;
				group.description = declaration.description;
				group.required = declaration.selection.alwaysInstalled();
				group.defaultSelected = declaration.selection.arrivesSelected();
				group.breaksWith = sortedSet(declaration.breaksWith);
				group.requires = sortedSet(declaration.requires);
				group.compatiblePlatforms = sortedSet(declaration.compatiblePlatforms);
				group.files = filesByGroup.get(entry.getKey());
				categories.computeIfAbsent(categoryByGroup.get(entry.getKey()), ignored -> new LinkedHashMap<>()).put(entry.getKey(), group);
			}
			fields.categories = categories;
			auditEditableRules(declarations, filesByGroup);
			GroupManifest manifest = GroupManifestValidator.validate(fields);
			return new ModpackCandidate(manifest, new TreeMap<>(objects), new TreeMap<>(provenance), exclusions);
		} catch (Exception e) {
			cleanup(results, e);
			if (e instanceof CandidateBuildException candidateBuildException) throw candidateBuildException;
			throw new CandidateBuildException("Failed to construct candidate", e);
		}
	}

	private PathResult process(SourcePair pair, GroupRules rules, ServerSideModExclusion exclusion, Request request) throws CandidateBuildException {
		List<ExcludedCandidate> exclusions = new ArrayList<>();
		CandidateSource candidate = pair.explicit != null ? pair.explicit : pair.synced;
		CandidateSource selected = null;
		GroupManifest.GroupFile file = null;
		StagedObject object = null;
		StableSourceSnapshotter.Snapshot snapshot = null;
		if (candidate != null) {
			snapshot = sourceSnapshotter.snapshot(candidate, exclusion.dropsJarMetadata(),
					request.stagingDirectory(), request.fileCache(), request.modFileCache(), request.objectStoreDirectory(), request.materializeMissingObjects());
			if (snapshot.exclusion() != null) {
				exclusions.add(excluded(candidate, snapshot.exclusion()));
			} else if (snapshot.file() != null) {
				selected = candidate;
				file = snapshot.file();
				object = snapshot.object();
			}
		}
		CandidateProvenance provenance = null;
		if (selected != null && file != null) {
			PathRuleSet.Decision editable = rules.editable(selected.logicalPath());
			file = new GroupManifest.GroupFile(file.size(), editable.matched(), file.sha1(), file.murmur());
			provenance = new CandidateProvenance(selected, editable.decisiveRule());
		}
		return new PathResult(selected, file, object, provenance, exclusions, pair.explicit != null ? pair.explicit : pair.synced, snapshot == null ? null : snapshot.environment(),
				snapshot == null || snapshot.clientMayBeAbsent(), file != null && HashUtils.isSha1(file.sha1()) ? HashUtils.normalizeSha1(file.sha1()) : null, exclusion,
				snapshot != null && snapshot.mod());
	}

	/**
	 * A publish drops the jars the platforms say clients cannot run; a preview only reports them, so what a generate would
	 * publish is read from the report instead of from a second network round that may answer differently tomorrow.
	 */
	private void applyPlatformSideVerdicts(List<PathResult> results, Request request) {
		if (sideVerdicts == null) return;
		Set<String> askable = new LinkedHashSet<>();
		for (PathResult result : results) {
			if (!result.exclusionMode.asksPlatforms()) continue;
			// A jar the loader would kick clients without (a Forge jar without a client-optional displayTest) stays whatever the platform says.
			if (!result.clientMayBeAbsent) continue;
			if (result.file == null || result.normalizedSha1 == null || !result.mod()) continue;
			if (result.environment == ModEnvironment.CLIENT || result.environment == ModEnvironment.SERVER) continue;
			askable.add(result.normalizedSha1);
		}
		if (askable.isEmpty()) return;
		Map<String, String> reasons = sideVerdicts.excludeFromClientPack(askable);
		if (reasons.isEmpty()) return;
		boolean applied = request.applyPlatformExclusions();
		for (int i = 0; i < results.size(); i++) {
			PathResult result = results.get(i);
			if (!result.exclusionMode.asksPlatforms()) continue;
			String reason = result.normalizedSha1 == null ? null : reasons.get(result.normalizedSha1);
			if (reason == null) continue;
			List<ExcludedCandidate> exclusions = new ArrayList<>(result.exclusions);
			exclusions.add(new ExcludedCandidate(result.selected, ExcludedCandidate.Reason.SERVER_SIDE_MOD, reason));
			if (applied) {
				if (result.object != null) try {
					result.object.delete();
				} catch (IOException e) {
					LOGGER.error("Could not delete the staged object of the platform-excluded {}", result.selected.logicalPath(), e);
				}
				results.set(i, new PathResult(null, null, null, null, exclusions, result.sourceForOrdering, result.environment, result.clientMayBeAbsent, null, result.exclusionMode, false));
			} else
				results.set(i, new PathResult(result.selected, result.file, result.object, result.provenance, exclusions, result.sourceForOrdering, result.environment, result.clientMayBeAbsent, result.normalizedSha1,
						result.exclusionMode, result.mod()));
		}
	}

	private static ServerSideModExclusion exclusionMode(ServerConfigJsons.GroupDeclaration declaration) {
		return ServerSideModExclusion.orDefault(declaration == null ? null : declaration.autoExcludeServerSideMods);
	}

	private static GroupRules compileRules(String groupId, ServerConfigJsons.GroupDeclaration declaration) throws CandidateBuildException {
		return new GroupRules(compileRuleSet(declaration.fromServer, groupId, "from-server"), compileRuleSet(declaration.exclude, groupId, "exclude"),
				compileRuleSet(declaration.editable, groupId, "editable"));
	}

	/** Warns about editable rules that match no pack files, and about rules matching active mods, which load from the standard mods folder instead of the projection. */
	private static void auditEditableRules(Map<String, ServerConfigJsons.GroupDeclaration> declarations,
			Map<String, Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields>> filesByGroup) {
		for (var entry : declarations.entrySet()) {
			Set<String> rules = entry.getValue().editable;
			if (rules == null || rules.isEmpty()) continue;
			Map<String, ModpackJsons.CompleteModpackContentFields.GroupFileFields> files = filesByGroup.getOrDefault(entry.getKey(), Map.of());
			for (String rule : rules) {
				if (rule == null || rule.startsWith("!")) continue;
				PathRuleSet matcher = new PathRuleSet(List.of(rule));
				boolean matched = false;
				long matchedMods = 0;
				for (var file : files.entrySet()) if (matcher.matches(file.getKey())) {
					matched = true;
					if (ModpackPathPolicy.isActiveMod(file.getKey())) matchedMods++;
				}
				if (!matched) LOGGER.warn("editable rule '{}' in group '{}' matched no pack files", rule, entry.getKey());
				else
					if (matchedMods > 0)
						LOGGER.warn(
								"editable rule '{}' in group '{}' matches {} active mod(s), which will load from the standard mods folder instead of the modpack projection. Not recommended: such mods need a restart to update and fall outside in-place mod management. Use this only for mods that break when loaded in place.",
								rule, entry.getKey(), matchedMods);
			}
		}
	}

	private static PathRuleSet compileRuleSet(Set<String> rules, String groupId, String name) throws CandidateBuildException {
		try {
			return new PathRuleSet(rules);
		} catch (IllegalArgumentException e) {
			throw new CandidateBuildException("Invalid " + name + " rules for group '" + groupId + "'", e);
		}
	}

	private static CandidateBuildException drain(List<CompletableFuture<PathResult>> futures, List<PathResult> results) {
		CandidateBuildException taskFailure = null;
		for (CompletableFuture<PathResult> future : futures) {
			try {
				results.add(future.join());
			} catch (CompletionException e) {
				Throwable cause = e.getCause();
				CandidateBuildException failure = cause instanceof CandidateBuildException candidateBuildException
						? candidateBuildException
						: new CandidateBuildException("Candidate task failed", cause);
				if (taskFailure == null) taskFailure = failure;
				else taskFailure.addSuppressed(failure);
			}
		}
		return taskFailure;
	}

	private static ExcludedCandidate excluded(CandidateSource source, StableSourceSnapshotter.Exclusion exclusion) {
		return new ExcludedCandidate(source, exclusion.reason(), exclusion.message());
	}

	private static void cleanup(List<PathResult> results, Exception failure) {
		for (PathResult result : results) {
			if (result.object == null) continue;
			try {
				result.object.delete();
			} catch (IOException e) {
				failure.addSuppressed(e);
			}
		}
	}

	private static Set<String> minimalScanRoots(Set<String> roots) {
		TreeSet<String> minimal = new TreeSet<>();
		for (String root : roots) {
			if (root.isEmpty()) return Set.of("");
			boolean covered = minimal.stream().anyMatch(existing -> root.equals(existing) || root.startsWith(existing + "/"));
			if (!covered) {
				minimal.removeIf(existing -> existing.startsWith(root + "/"));
				minimal.add(root);
			}
		}
		return minimal;
	}

	private static Map<String, List<String>> indexGroupsByScanRoot(Set<String> scanRoots, Map<String, GroupRules> rulesByGroup) {
		Map<String, List<String>> result = new TreeMap<>();
		for (String scanRoot : scanRoots) {
			List<String> groups = new ArrayList<>();
			for (var entry : rulesByGroup.entrySet()) {
				Set<String> groupRoots = entry.getValue().fromServer().safeScanRoots();
				if (groupRoots.stream().anyMatch(groupRoot -> groupRoot.isEmpty() || scanRoot.isEmpty() || scanRoot.equals(groupRoot)
						|| scanRoot.startsWith(groupRoot + "/") || groupRoot.startsWith(scanRoot + "/")))
					groups.add(entry.getKey());
			}
			result.put(scanRoot, List.copyOf(groups));
		}
		return Map.copyOf(result);
	}

	private static NavigableMap<String, Path> walk(Path root) throws CandidateBuildException {
		return walk(root, root);
	}

	private static NavigableMap<String, Path> walk(Path root, Path logicalRoot) throws CandidateBuildException {
		TreeMap<String, Path> files = new TreeMap<>();
		if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return files;
		Path normalizedRoot = root.toAbsolutePath().normalize();
		Path normalizedLogicalRoot = logicalRoot.toAbsolutePath().normalize();
		if (!normalizedRoot.startsWith(normalizedLogicalRoot)) throw new CandidateBuildException("Source root escapes logical root: " + normalizedRoot);
		try {
			Files.walkFileTree(normalizedRoot, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
					String logicalPath = LogicalPath.normalize(normalizedLogicalRoot.relativize(file.toAbsolutePath().normalize()).toString());
					files.put(logicalPath, file);
					return FileVisitResult.CONTINUE;
				}

				@Override
				public FileVisitResult visitFileFailed(Path file, IOException exception) throws IOException {
					throw exception;
				}
			});
			return files;
		} catch (IOException | RuntimeException e) {
			throw new CandidateBuildException("Failed to walk source root " + normalizedRoot, e);
		}
	}

	private static Set<String> sortedSet(Set<String> values) {
		return values == null ? Set.of() : new LinkedHashSet<>(new TreeSet<>(values));
	}

	private record GroupRules(PathRuleSet fromServer, PathRuleSet exclude, PathRuleSet editableRules) {
		private PathRuleSet.Decision synced(String path) {
			return fromServer.evaluate(path);
		}

		private PathRuleSet.Decision excluded(String path) {
			return exclude.evaluate(path);
		}

		private PathRuleSet.Decision editable(String path) {
			return editableRules.evaluate(path);
		}
	}

	private static final class SourcePair {
		private CandidateSource explicit;
		private CandidateSource synced;

		private String groupId() {
			return explicit != null ? explicit.groupId() : synced.groupId();
		}
	}

	private record PathResult(
			CandidateSource selected,
			GroupManifest.GroupFile file,
			StagedObject object,
			CandidateProvenance provenance,
			List<ExcludedCandidate> exclusions,
			CandidateSource sourceForOrdering,
			ModEnvironment environment,
			boolean clientMayBeAbsent,
			String normalizedSha1,
			ServerSideModExclusion exclusionMode,
			boolean mod) {}

	public record Request(
			String modpackId,
			String modpackName,
			String automodpackVersion,
			String loader,
			String loaderVersion,
			String mcVersion,
			Path serverRoot,
			Path groupRoot,
			Map<String, Map<String, ServerConfigJsons.GroupDeclaration>> modpack,
			boolean applyPlatformExclusions,
			Path stagingDirectory,
			Executor executor,
			Path objectStoreDirectory,
			FileCache fileCache,
			ModFileCache modFileCache,
			boolean materializeMissingObjects) {
		public Request {
			serverRoot = serverRoot.toAbsolutePath().normalize();
			groupRoot = groupRoot.toAbsolutePath().normalize();
			stagingDirectory = stagingDirectory.toAbsolutePath().normalize();
			if (objectStoreDirectory != null) objectStoreDirectory = objectStoreDirectory.toAbsolutePath().normalize();
			Objects.requireNonNull(executor);
		}
	}
}
