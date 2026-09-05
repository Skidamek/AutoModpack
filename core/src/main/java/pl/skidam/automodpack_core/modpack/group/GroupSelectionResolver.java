package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public final class GroupSelectionResolver {
	private GroupSelectionResolver() {}

	public static SelectionIntent defaultIntent(GroupManifest manifest) {
		Objects.requireNonNull(manifest);
		Set<String> requestedGroups = new TreeSet<>();
		for (var entry : manifest.groups().entrySet()) {
			GroupManifest.Group group = entry.getValue();
			if (group.defaultSelected() && !group.required()) requestedGroups.add(entry.getKey());
		}
		return new SelectionIntent(requestedGroups);
	}

	public static ResolvedSelection resolveDefault(GroupManifest manifest, ClientPlatform platform) {
		return resolveInternal(manifest, defaultIntent(manifest), platform, true);
	}

	public static ResolvedSelection resolve(GroupManifest manifest, SelectionIntent intent, ClientPlatform platform) {
		return resolveInternal(manifest, intent, platform, false);
	}

	private static ResolvedSelection resolveInternal(GroupManifest manifest, SelectionIntent intent, ClientPlatform platform, boolean defaultSelection) {
		Objects.requireNonNull(manifest);
		Objects.requireNonNull(intent);
		Objects.requireNonNull(platform);
		ResolutionState state = new ResolutionState(manifest, intent, platform);
		state.initializeMandatoryGroups();
		state.collectStaleChoices();

		for (String groupId : state.requiredGroups) state.resolveRoot(groupId, Source.REQUIRED, true);
		for (String category : intent.requestedCategories()) for (var entry : manifest.groups().entrySet()) {
			GroupManifest.Group group = entry.getValue();
			if (category.equals(group.category()) && !group.required() && group.supports(platform)) state.resolveRoot(entry.getKey(), Source.EXPLICIT, false);
		}
		for (String groupId : intent.requestedGroups()) if (manifest.groups().containsKey(groupId)) {
			GroupManifest.Group group = manifest.groups().get(groupId);
			state.resolveRoot(groupId, defaultSelection && group.defaultSelected() ? Source.DEFAULT_SELECTED : Source.EXPLICIT, false);
		}

		state.checkConflicts();
		ResolvedSelection resolved = state.result();
		if (!state.errors.isEmpty()) throw new SelectionResolutionException(state.errors.stream().distinct().sorted().toList(), resolved);
		return resolved;
	}

	public static SelectionIntent prefer(GroupManifest manifest, SelectionIntent current, String clicked, ClientPlatform platform) {
		Objects.requireNonNull(manifest);
		Objects.requireNonNull(current);
		Objects.requireNonNull(clicked);
		Objects.requireNonNull(platform);
		Set<String> requestedGroups = new TreeSet<>(current.requestedGroups());
		Set<String> requestedCategories = new TreeSet<>(current.requestedCategories());
		Set<String> excludedGroups = new TreeSet<>(current.excludedGroups());
		GroupManifest.Group clickedGroup = manifest.groups().get(clicked);
		if (clickedGroup != null && !clickedGroup.category().isEmpty() && requestedCategories.remove(clickedGroup.category())) {
			for (var entry : manifest.groups().entrySet()) {
				GroupManifest.Group group = entry.getValue();
				if (clickedGroup.category().equals(group.category()) && !group.required() && group.supports(platform)) requestedGroups.add(entry.getKey());
			}
		}
		if (!requestedGroups.add(clicked)) requestedGroups.remove(clicked);
		excludedGroups.remove(clicked);
		return new SelectionIntent(requestedGroups, requestedCategories, excludedGroups);
	}

	/** Persists a category request and clears the per-group choices inside it, so the category speaks with one voice. */
	public static SelectionIntent preferCategory(GroupManifest manifest, SelectionIntent current, String category, ClientPlatform platform) {
		Objects.requireNonNull(manifest);
		Objects.requireNonNull(current);
		Objects.requireNonNull(category);
		Objects.requireNonNull(platform);
		Set<String> requestedGroups = new TreeSet<>(current.requestedGroups());
		Set<String> requestedCategories = new TreeSet<>(current.requestedCategories());
		Set<String> excludedGroups = new TreeSet<>(current.excludedGroups());
		requestedCategories.add(category);
		clearCategoryChoices(categoryGroups(manifest, category, platform), requestedGroups, excludedGroups);
		return new SelectionIntent(requestedGroups, requestedCategories, excludedGroups);
	}

	/** Excludes every optional group in the category and drops the category request, so one click turns the whole category off — even groups that are on by default. */
	public static SelectionIntent excludeCategory(GroupManifest manifest, SelectionIntent current, String category, ClientPlatform platform) {
		Objects.requireNonNull(manifest);
		Objects.requireNonNull(current);
		Objects.requireNonNull(category);
		Objects.requireNonNull(platform);
		Set<String> requestedGroups = new TreeSet<>(current.requestedGroups());
		Set<String> requestedCategories = new TreeSet<>(current.requestedCategories());
		Set<String> excludedGroups = new TreeSet<>(current.excludedGroups());
		requestedCategories.remove(category);
		Set<String> categoryGroups = categoryGroups(manifest, category, platform);
		clearCategoryChoices(categoryGroups, requestedGroups, excludedGroups);
		excludedGroups.addAll(categoryGroups);
		return new SelectionIntent(requestedGroups, requestedCategories, excludedGroups);
	}

	private static Set<String> categoryGroups(GroupManifest manifest, String category, ClientPlatform platform) {
		Set<String> categoryGroups = new TreeSet<>();
		for (var entry : manifest.groups().entrySet()) if (category.equals(entry.getValue().category()) && !entry.getValue().required() && entry.getValue().supports(platform)) categoryGroups.add(entry.getKey());
		return categoryGroups;
	}

	private static void clearCategoryChoices(Set<String> categoryGroups, Set<String> requestedGroups, Set<String> excludedGroups) {
		for (String groupId : categoryGroups) {
			requestedGroups.remove(groupId);
			excludedGroups.remove(groupId);
		}
	}

	public static boolean conflicts(GroupManifest manifest, String first, String second) {
		if (Objects.equals(first, second)) return false;
		GroupManifest.Group firstGroup = manifest.groups().get(first);
		GroupManifest.Group secondGroup = manifest.groups().get(second);
		return (firstGroup != null && firstGroup.breaksWith().contains(second)) || (secondGroup != null && secondGroup.breaksWith().contains(first));
	}

	/** Builds the deterministic intent that keeps newly preferred groups and removes the choices which conflict with them. */
	public static Optional<ConflictReplacement> replaceConflicts(GroupManifest manifest, SelectionIntent candidate, Set<String> preferredGroups,
			ClientPlatform platform, ResolvedSelection partial) {
		Objects.requireNonNull(manifest);
		Objects.requireNonNull(candidate);
		Objects.requireNonNull(preferredGroups);
		Objects.requireNonNull(platform);
		if (preferredGroups.isEmpty() || partial == null) return Optional.empty();
		Set<String> conflicts = new TreeSet<>();
		for (String preferred : preferredGroups) {
			GroupResolution resolution = partial.resolution(preferred);
			if (resolution != null && resolution.status() == GroupResolution.Status.CONFLICT) conflicts.addAll(resolution.relatedGroups());
		}
		conflicts.removeAll(preferredGroups);
		if (conflicts.isEmpty()) return Optional.empty();

		Set<String> requestedGroups = new TreeSet<>(candidate.requestedGroups());
		requestedGroups.removeIf(groupId -> !preferredGroups.contains(groupId) && selectsAny(manifest, new SelectionIntent(Set.of(groupId)), platform, conflicts));
		Set<String> requestedCategories = new TreeSet<>(candidate.requestedCategories());
		Set<String> expandedGroups = new TreeSet<>();
		for (String category : new TreeSet<>(requestedCategories)) {
			if (!selectsAny(manifest, new SelectionIntent(Set.of(), Set.of(category), Set.of()), platform, conflicts)) continue;
			requestedCategories.remove(category);
			for (var entry : manifest.groups().entrySet()) {
				GroupManifest.Group group = entry.getValue();
				if (!category.equals(group.category()) || group.required() || !group.supports(platform)) continue;
				if (!selectsAny(manifest, new SelectionIntent(Set.of(entry.getKey())), platform, conflicts)) expandedGroups.add(entry.getKey());
			}
		}
		requestedGroups.addAll(expandedGroups);
		SelectionIntent replacement = new SelectionIntent(requestedGroups, requestedCategories, candidate.excludedGroups());
		try {
			resolve(manifest, replacement, platform);
			return Optional.of(new ConflictReplacement(replacement, conflicts));
		} catch (SelectionResolutionException ignored) {
			return Optional.empty();
		}
	}

	private static boolean selectsAny(GroupManifest manifest, SelectionIntent intent, ClientPlatform platform, Set<String> groupIds) {
		try {
			return !Collections.disjoint(resolve(manifest, intent, platform).selectedGroups(), groupIds);
		} catch (SelectionResolutionException exception) {
			return exception.resolution() != null && !Collections.disjoint(exception.resolution().selectedGroups(), groupIds);
		}
	}

	public record ConflictReplacement(SelectionIntent intent, Set<String> conflictingGroups) {
		public ConflictReplacement {
			intent = Objects.requireNonNull(intent);
			conflictingGroups = Set.copyOf(new TreeSet<>(conflictingGroups));
		}
	}

	private enum Source {
		REQUIRED,
		DEFAULT_SELECTED,
		EXPLICIT
	}

	private static final class ResolutionState {
		private final GroupManifest manifest;
		private final SelectionIntent intent;
		private final ClientPlatform platform;
		private final NavigableSet<String> selected = new TreeSet<>();
		private final NavigableSet<String> staleGroups = new TreeSet<>();
		private final NavigableSet<String> requiredGroups = new TreeSet<>();
		private final NavigableSet<String> forcedGroups = new TreeSet<>();
		private final NavigableSet<String> dependencyGroups = new TreeSet<>();
		private final NavigableSet<String> unavailableGroups = new TreeSet<>();
		private final NavigableSet<String> requestedUnavailableGroups = new TreeSet<>();
		private final NavigableSet<String> blockedGroups = new TreeSet<>();
		private final NavigableSet<String> excludedGroups = new TreeSet<>();
		private final NavigableSet<String> conflictGroups = new TreeSet<>();
		private final Map<String, EnumSet<GroupResolution.Reason>> reasons = new TreeMap<>();
		private final Map<String, NavigableSet<String>> relatedGroups = new TreeMap<>();
		private final List<String> errors = new ArrayList<>();

		private ResolutionState(GroupManifest manifest, SelectionIntent intent, ClientPlatform platform) {
			this.manifest = manifest;
			this.intent = intent;
			this.platform = platform;
		}

		private void initializeMandatoryGroups() {
			for (var entry : manifest.groups().entrySet()) if (entry.getValue().required()) {
				requiredGroups.add(entry.getKey());
				forcedGroups.add(entry.getKey());
				addReason(entry.getKey(), GroupResolution.Reason.REQUIRED);
				addReason(entry.getKey(), GroupResolution.Reason.FORCED);
			}
		}

		private void collectStaleChoices() {
			for (String groupId : intent.requestedGroups()) if (!manifest.groups().containsKey(groupId)) staleGroups.add(groupId);
		}

		private void resolveRoot(String groupId, Source source, boolean forced) {
			Closure closure = resolveClosure(groupId, source, forced, false, new LinkedHashSet<>());
			if (closure.success()) selected.addAll(closure.groups());
			else if (source == Source.EXPLICIT) {
				if (unavailableGroups.stream().anyMatch(closure.groups()::contains)) {
					requestedUnavailableGroups.add(groupId);
					addReason(groupId, GroupResolution.Reason.EXPLICIT_REQUEST_UNAVAILABLE);
					errors.add("Group '" + groupId + "' was explicitly requested but is unavailable on " + platform.id());
				} else if (!closure.excluded()) {
					errors.add("Group '" + groupId + "' was explicitly requested but could not be selected");
				}
			}
		}

		private Closure resolveClosure(String groupId, Source source, boolean forced, boolean dependency, Set<String> active) {
			GroupManifest.Group group = manifest.groups().get(groupId);
			if (group == null) {
				if (forced) errors.add("Group '" + groupId + "' is missing");
				return Closure.failure(Set.of());
			}
			markSource(groupId, source, forced, dependency, group);
			if (intent.excludedGroups().contains(groupId)) {
				excludedGroups.add(groupId);
				addReason(groupId, GroupResolution.Reason.EXPLICIT_EXCLUSION);
				if (forced) errors.add("Group '" + groupId + "' is required and cannot be excluded");
				return Closure.excluded(Set.of(groupId));
			}
			if (!group.supports(platform)) {
				unavailableGroups.add(groupId);
				addReason(groupId, GroupResolution.Reason.PLATFORM_INCOMPATIBLE);
				if (forced) errors.add("Group '" + groupId + "' is unavailable on " + platform.id());
				return Closure.failure(Set.of(groupId));
			}
			if (selected.contains(groupId)) return Closure.success(Set.of(groupId));
			if (!active.add(groupId)) {
				errors.add("Group dependency cycle includes '" + groupId + "'");
				return Closure.failure(Set.of(groupId));
			}
			Set<String> closure = new TreeSet<>();
			try {
				for (String dependencyId : group.requires()) {
					Closure dependencyClosure = resolveClosure(dependencyId, source, forced || group.required(), true, active);
					if (!dependencyClosure.success()) {
						Set<String> blocked = new TreeSet<>(dependencyClosure.groups());
						blocked.add(groupId);
						blockedGroups.addAll(blocked);
						addReason(groupId, GroupResolution.Reason.BLOCKED_BY_DEPENDENCY);
						relate(groupId, dependencyId);
						if (dependencyClosure.excluded()) errors.add("Group '" + groupId + "' cannot be selected because dependency '" + dependencyId + "' was excluded");
						else if (forced) errors.add("Group '" + groupId + "' cannot be selected because dependency '" + dependencyId + "' is unavailable");
						return Closure.failure(blocked, dependencyClosure.excluded());
					}
					relate(dependencyId, groupId);
					closure.addAll(dependencyClosure.groups());
				}
				closure.add(groupId);
				return Closure.success(closure);
			} finally {
				active.remove(groupId);
			}
		}

		private void markSource(String groupId, Source source, boolean forced, boolean dependency, GroupManifest.Group group) {
			if (dependency) {
				addReason(groupId, GroupResolution.Reason.DEPENDENCY);
				dependencyGroups.add(groupId);
			}
			if (forced || group.required()) {
				forcedGroups.add(groupId);
				addReason(groupId, GroupResolution.Reason.FORCED);
			}
			if (group.required()) {
				requiredGroups.add(groupId);
				addReason(groupId, GroupResolution.Reason.REQUIRED);
			}
			if (source == Source.DEFAULT_SELECTED) addReason(groupId, GroupResolution.Reason.DEFAULT_SELECTED);
			if (source == Source.EXPLICIT) addReason(groupId, GroupResolution.Reason.EXPLICIT_GROUP);
		}

		private void checkConflicts() {
			List<String> ordered = new ArrayList<>(selected);
			for (int i = 0; i < ordered.size(); i++) for (int j = i + 1; j < ordered.size(); j++) {
				String first = ordered.get(i);
				String second = ordered.get(j);
				if (!conflicts(manifest, first, second)) continue;
				conflictGroups.add(first);
				conflictGroups.add(second);
				addReason(first, GroupResolution.Reason.CONFLICTING_GROUP);
				addReason(second, GroupResolution.Reason.CONFLICTING_GROUP);
				relate(first, second);
				relate(second, first);
				errors.add("Groups '" + first + "' and '" + second + "' cannot be selected together");
			}
		}

		private ResolvedSelection result() {
			NavigableMap<String, GroupResolution> explanations = new TreeMap<>();
			for (var entry : manifest.groups().entrySet()) {
				String groupId = entry.getKey();
				GroupManifest.Group group = entry.getValue();
				EnumSet<GroupResolution.Reason> groupReasons = reasons.getOrDefault(groupId, EnumSet.noneOf(GroupResolution.Reason.class));
				if (!group.supports(platform)) {
					unavailableGroups.add(groupId);
					groupReasons.add(GroupResolution.Reason.PLATFORM_INCOMPATIBLE);
				}
				GroupResolution.Status status;
				if (conflictGroups.contains(groupId)) status = GroupResolution.Status.CONFLICT;
				else if (selected.contains(groupId)) status = GroupResolution.Status.SELECTED;
				else if (excludedGroups.contains(groupId) || intent.excludedGroups().contains(groupId)) status = GroupResolution.Status.EXCLUDED;
				else if (unavailableGroups.contains(groupId)) status = GroupResolution.Status.UNAVAILABLE;
				else if (blockedGroups.contains(groupId)) status = GroupResolution.Status.BLOCKED;
				else status = GroupResolution.Status.AVAILABLE;
				explanations.put(groupId, new GroupResolution(groupId, status, groupReasons, relatedGroups.get(groupId)));
			}
			dependencyGroups.retainAll(selected);
			return new ResolvedSelection(intent, selected, staleGroups, requiredGroups, forcedGroups, dependencyGroups, unavailableGroups, requestedUnavailableGroups, explanations);
		}

		private void addReason(String groupId, GroupResolution.Reason reason) {
			reasons.computeIfAbsent(groupId, ignored -> EnumSet.noneOf(GroupResolution.Reason.class)).add(reason);
		}

		private void relate(String groupId, String relatedGroupId) {
			relatedGroups.computeIfAbsent(groupId, ignored -> new TreeSet<>()).add(relatedGroupId);
		}
	}

	private record Closure(boolean success, Set<String> groups, boolean excluded) {
		private static Closure success(Set<String> groups) {
			return new Closure(true, Set.copyOf(groups), false);
		}

		private static Closure failure(Set<String> groups) {
			return failure(groups, false);
		}

		private static Closure failure(Set<String> groups, boolean excluded) {
			return new Closure(false, Set.copyOf(groups), excluded);
		}

		private static Closure excluded(Set<String> groups) {
			return new Closure(false, Set.copyOf(groups), true);
		}
	}
}
