package pl.skidam.automodpack_core.modpack.candidate;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.*;
import java.util.concurrent.*;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.GroupManifestValidator;
import pl.skidam.automodpack_core.modpack.group.LogicalPath;

public final class ModpackCandidateScanner {
	private final StableSourceSnapshotter sourceSnapshotter = new StableSourceSnapshotter();

	public ModpackCandidate scan(Request request) throws CandidateBuildException {
		Objects.requireNonNull(request);
		if (request.groups() == null || request.groups().isEmpty()) throw new CandidateBuildException("No groups are configured");
		Map<String, Jsons.GroupDeclaration> declarations = new TreeMap<>();
		Map<String, GroupRules> rulesByGroup = new TreeMap<>();
		for (var entry : request.groups().entrySet()) {
			try {
				GroupManifestValidator.requireIdentifier(entry.getKey());
			} catch (IllegalArgumentException e) {
				throw new CandidateBuildException(e.getMessage(), e);
			}
			if (entry.getValue() == null) throw new CandidateBuildException("Group '" + entry.getKey() + "' has no declaration");
			declarations.put(entry.getKey(), entry.getValue());
			rulesByGroup.put(entry.getKey(), compileRules(entry.getKey(), entry.getValue()));
		}

		List<ExcludedCandidate> ruleExclusions = new ArrayList<>();
		Map<String, SourcePair> sources = new TreeMap<>();
		for (var entry : declarations.entrySet()) {
			String groupId = entry.getKey();
			Jsons.GroupDeclaration declaration = entry.getValue();
			PathRuleSet syncedRules = rulesByGroup.get(groupId).syncedFiles();
			Path groupDirectory = request.groupRoot().resolve(groupId).normalize();
			if (!groupDirectory.startsWith(request.groupRoot().toAbsolutePath().normalize()))
				throw new CandidateBuildException("Group directory escapes host-modpack: " + groupId);
			for (var file : walk(groupDirectory).entrySet()) {
				CandidateSource source = new CandidateSource(groupId, file.getKey(), CandidateSource.SourceKind.GROUP_DIRECTORY, file.getValue(), null);
				sources.computeIfAbsent(key(groupId, file.getKey()), ignored -> new SourcePair()).explicit = source;
			}
			if (!syncedRules.isEmpty()) for (var file : walk(request.serverRoot()).entrySet()) {
				PathRuleSet.Decision decision = syncedRules.evaluate(file.getKey());
				if (!decision.matched()) continue;
				CandidateSource source = new CandidateSource(groupId, file.getKey(), CandidateSource.SourceKind.SYNCED_ROOT, file.getValue(), decision.decisiveRule());
				if (!decision.included()) {
					ruleExclusions.add(new ExcludedCandidate(source, ExcludedCandidate.Reason.EXCLUDED_BY_RULE, "excluded by " + decision.decisiveRule()));
					continue;
				}
				SourcePair pair = sources.computeIfAbsent(key(groupId, file.getKey()), ignored -> new SourcePair());
				if (pair.synced != null && !pair.synced.sourcePath().equals(source.sourcePath()))
					throw new CandidateBuildException("Multiple synchronized sources resolve to group '" + groupId + "' path '" + file.getKey() + "'");
				pair.synced = source;
			}
		}

		List<CompletableFuture<PathResult>> futures = new ArrayList<>();
		try {
			for (SourcePair pair : sources.values()) futures.add(CompletableFuture.supplyAsync(() -> {
				try {
					return process(pair, rulesByGroup.get(pair.groupId()), request);
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

		Map<String, Map<String, Jsons.CompleteModpackContentFields.GroupFileFields>> filesByGroup = new TreeMap<>();
		for (String groupId : declarations.keySet()) filesByGroup.put(groupId, new TreeMap<>());
		Map<String, StagedObject> objects = new TreeMap<>();
		Map<String, CandidateProvenance> provenance = new TreeMap<>();
		List<ExcludedCandidate> exclusions = new ArrayList<>(ruleExclusions);
		List<ShadowedCandidate> shadows = new ArrayList<>();
		try {
			for (PathResult result : results) {
				exclusions.addAll(result.exclusions);
				if (result.shadow != null) shadows.add(result.shadow);
				if (result.selected == null || result.file == null) continue;
				GroupManifest.GroupFile file = result.file;
				if (result.object == null) throw new CandidateBuildException("Selected source has no staged object: " + result.selected.sourcePath());
				filesByGroup.get(result.selected.groupId()).put(result.selected.logicalPath(), new Jsons.CompleteModpackContentFields.GroupFileFields(
						String.valueOf(file.size()), file.type(), file.editable(), file.overwriteEditable(), file.forceCopy(), file.sha1(), file.murmur()));
				StagedObject redundant = objects.putIfAbsent(file.sha1().toLowerCase(Locale.ROOT), result.object);
				if (redundant != null) result.object.delete();
				provenance.put(ModpackCandidate.provenanceKey(result.selected.groupId(), result.selected.logicalPath()), result.provenance);
			}

			Jsons.CompleteModpackContentFields fields = new Jsons.CompleteModpackContentFields();
			fields.modpackId = request.modpackId();
			fields.modpackName = request.modpackName();
			fields.automodpackVersion = request.automodpackVersion();
			fields.loader = request.loader();
			fields.loaderVersion = request.loaderVersion();
			fields.mcVersion = request.mcVersion();
			fields.nonModpackFilesToDelete = request.nonModpackFilesToDelete();
			Map<String, Jsons.CompleteModpackContentFields.ModpackGroupFields> groups = new LinkedHashMap<>();
			for (var entry : declarations.entrySet()) {
				Jsons.GroupDeclaration declaration = entry.getValue();
				Jsons.CompleteModpackContentFields.ModpackGroupFields group = new Jsons.CompleteModpackContentFields.ModpackGroupFields();
				group.displayName = declaration.displayName;
				group.description = declaration.description;
				group.category = declaration.category;
				group.required = declaration.required;
				group.recommended = declaration.recommended;
				group.breaksWith = sortedSet(declaration.breaksWith);
				group.requires = sortedSet(declaration.requires);
				group.tags = sortedSet(declaration.tags);
				group.compatiblePlatforms = sortedSet(declaration.compatiblePlatforms);
				group.files = filesByGroup.get(entry.getKey());
				groups.put(entry.getKey(), group);
			}
			fields.groups = groups;
			Map<String, Jsons.CompleteModpackContentFields.SelectionTagFields> tags = new LinkedHashMap<>();
			if (request.selectionTags() != null) for (var entry : new TreeMap<>(request.selectionTags()).entrySet()) {
				Jsons.SelectionTagDeclaration declaration = entry.getValue();
				if (declaration == null) throw new CandidateBuildException("Selection tag '" + entry.getKey() + "' has no declaration");
				Jsons.CompleteModpackContentFields.SelectionTagFields tag = new Jsons.CompleteModpackContentFields.SelectionTagFields();
				tag.displayName = declaration.displayName;
				tag.description = declaration.description;
				tag.defaultSelected = declaration.defaultSelected;
				tag.serverForced = declaration.serverForced;
				tags.put(entry.getKey(), tag);
			}
			fields.selectionTags = tags;
			GroupManifest manifest = GroupManifestValidator.validate(fields);
			return new ModpackCandidate(manifest, new TreeMap<>(objects), new TreeMap<>(provenance), exclusions, shadows);
		} catch (Exception e) {
			cleanup(results, e);
			if (e instanceof CandidateBuildException candidateBuildException) throw candidateBuildException;
			throw new CandidateBuildException("Failed to construct candidate", e);
		}
	}

	private PathResult process(SourcePair pair, GroupRules rules, Request request) throws CandidateBuildException {
		List<ExcludedCandidate> exclusions = new ArrayList<>();
		CandidateSource selected = null;
		GroupManifest.GroupFile file = null;
		StagedObject object = null;
		if (pair.explicit != null) {
			StableSourceSnapshotter.Snapshot snapshot = sourceSnapshotter.snapshot(pair.explicit, request.autoExcludeUnnecessaryFiles(), request.autoExcludeServerSideMods(),
					request.stagingDirectory());
			if (snapshot.exclusion() == null) {
				selected = pair.explicit;
				file = snapshot.file();
				object = snapshot.object();
			} else exclusions.add(excluded(pair.explicit, snapshot.exclusion()));
		}
		if (pair.explicit == null && pair.synced != null) {
			StableSourceSnapshotter.Snapshot snapshot = sourceSnapshotter.snapshot(pair.synced, request.autoExcludeUnnecessaryFiles(), request.autoExcludeServerSideMods(),
					request.stagingDirectory());
			if (snapshot.exclusion() == null) {
				selected = pair.synced;
				file = snapshot.file();
				object = snapshot.object();
			} else exclusions.add(excluded(pair.synced, snapshot.exclusion()));
		}
		ShadowedCandidate shadow = pair.explicit != null && pair.synced != null
				? new ShadowedCandidate(pair.explicit, pair.synced, ShadowedCandidate.Relationship.NOT_COMPARED)
				: null;
		CandidateProvenance provenance = null;
		if (selected != null && file != null) {
			PathRuleSet.Decision editable = rules.allowEditsInFiles().evaluate(selected.logicalPath());
			PathRuleSet.Decision overwrite = rules.overwriteEditableFiles().evaluate(selected.logicalPath());
			PathRuleSet.Decision forceCopy = rules.forceCopyFilesToStandardLocation().evaluate(selected.logicalPath());
			boolean isEditable = editable.included();
			file = new GroupManifest.GroupFile(file.size(), file.type(), isEditable, isEditable && overwrite.included(), forceCopy.included(), file.sha1(), file.murmur());
			provenance = new CandidateProvenance(selected, editable.decisiveRule(), overwrite.decisiveRule(), forceCopy.decisiveRule());
		}
		return new PathResult(selected, file, object, provenance, exclusions, shadow, pair.explicit != null ? pair.explicit : pair.synced);
	}

	private static GroupRules compileRules(String groupId, Jsons.GroupDeclaration declaration) throws CandidateBuildException {
		return new GroupRules(compileRuleSet(declaration.syncedFiles, groupId, "syncedFiles"),
				compileRuleSet(declaration.allowEditsInFiles, groupId, "allowEditsInFiles"),
				compileRuleSet(declaration.overwriteEditableFiles, groupId, "overwriteEditableFiles"),
				compileRuleSet(declaration.forceCopyFilesToStandardLocation, groupId, "forceCopyFilesToStandardLocation"));
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

	private static NavigableMap<String, Path> walk(Path root) throws CandidateBuildException {
		TreeMap<String, Path> files = new TreeMap<>();
		if (root == null || !Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return files;
		Path normalizedRoot = root.toAbsolutePath().normalize();
		try {
			Files.walkFileTree(normalizedRoot, EnumSet.noneOf(FileVisitOption.class), Integer.MAX_VALUE, new SimpleFileVisitor<>() {
				@Override
				public FileVisitResult visitFile(Path file, BasicFileAttributes attributes) {
					String logicalPath = LogicalPath.normalize(normalizedRoot.relativize(file.toAbsolutePath().normalize()).toString());
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

	private static String key(String groupId, String logicalPath) {
		return groupId + '\0' + logicalPath;
	}

	private record GroupRules(
			PathRuleSet syncedFiles,
			PathRuleSet allowEditsInFiles,
			PathRuleSet overwriteEditableFiles,
			PathRuleSet forceCopyFilesToStandardLocation) {}

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
			ShadowedCandidate shadow,
			CandidateSource sourceForOrdering) {}

	public record Request(
			String modpackId,
			String modpackName,
			String automodpackVersion,
			String loader,
			String loaderVersion,
			String mcVersion,
			Path serverRoot,
			Path groupRoot,
			Map<String, Jsons.GroupDeclaration> groups,
			Map<String, Jsons.SelectionTagDeclaration> selectionTags,
			Set<Jsons.ModpackContentFields.FileToDelete> nonModpackFilesToDelete,
			boolean autoExcludeUnnecessaryFiles,
			boolean autoExcludeServerSideMods,
			Path stagingDirectory,
			Executor executor) {
		public Request {
			serverRoot = serverRoot.toAbsolutePath().normalize();
			groupRoot = groupRoot.toAbsolutePath().normalize();
			stagingDirectory = stagingDirectory.toAbsolutePath().normalize();
			Objects.requireNonNull(executor);
		}
	}
}
