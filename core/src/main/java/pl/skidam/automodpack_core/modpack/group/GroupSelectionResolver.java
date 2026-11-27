package pl.skidam.automodpack_core.modpack.group;

import java.util.*;

public final class GroupSelectionResolver {
	private GroupSelectionResolver() {}

	public record SelectionUpdate(SelectionIntent intent, ResolvedSelection resolution, NavigableSet<String> turnedOff, String error) {
		public SelectionUpdate {
			intent = Objects.requireNonNull(intent, "intent");
			turnedOff = turnedOff == null ? Collections.emptyNavigableSet() : Collections.unmodifiableNavigableSet(new TreeSet<>(turnedOff));
			error = error == null ? "" : error;
		}

		public boolean ok() {
			return error.isEmpty() && resolution != null;
		}
	}

	public static ResolvedSelection resolveDefault(GroupManifest manifest, ClientPlatform platform) {
		return resolve(manifest, SelectionIntent.DEFAULTS, platform);
	}

	public static ResolvedSelection resolve(GroupManifest manifest, SelectionIntent intent, ClientPlatform platform) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(intent, "selection intent");

		Map<String, GroupManifest.Group> groups = manifest.groups();
		Map<String, GroupManifest.Category> categories = manifest.categories();

		NavigableSet<String> staleGroups = new TreeSet<>();
		for (String id : intent.enabled()) {
			if (!groups.containsKey(id)) staleGroups.add(id);
		}

		Map<String, Set<GroupResolution.Reason>> reasons = new LinkedHashMap<>();
		Map<String, Set<String>> related = new LinkedHashMap<>();
		for (String id : groups.keySet()) {
			reasons.put(id, new LinkedHashSet<>());
			related.put(id, new LinkedHashSet<>());
		}

		NavigableSet<String> selected = new TreeSet<>();
		NavigableSet<String> coreRoots = new TreeSet<>();
		NavigableSet<String> unavailable = new TreeSet<>();
		NavigableSet<String> requestedUnavailable = new TreeSet<>();
		Map<String, Set<String>> blockedBy = new LinkedHashMap<>();
		List<String> errors = new ArrayList<>();

		for (var entry : groups.entrySet()) {
			String id = entry.getKey();
			if (!entry.getValue().supports(platform)) {
				unavailable.add(id);
				reasons.get(id).add(GroupResolution.Reason.PLATFORM_INCOMPATIBLE);
			} else {
				Set<String> badDeps = incompatibleTransitiveDeps(manifest, id, platform);
				if (!badDeps.isEmpty()) blockedBy.put(id, badDeps);
			}
		}

		// Pass 1: REQUIRED groups always install.
		for (var entry : groups.entrySet()) {
			String id = entry.getKey();
			GroupManifest.Group group = entry.getValue();
			if (group.selection() != GroupSelectionMode.REQUIRED) continue;
			coreRoots.add(id);
			if (!group.supports(platform)) {
				errors.add("Required group '" + id + "' is not available on " + platformLabel(platform));
			} else if (blockedBy.containsKey(id)) {
				errors.add("Required group '" + id + "' requires incompatible groups on " + platformLabel(platform) + ": " + String.join(", ", blockedBy.get(id)));
			} else {
				selected.add(id);
				reasons.get(id).add(GroupResolution.Reason.REQUIRED);
			}
		}

		// Pass 2: Uncategorized and ANY-category choosable groups.
		for (var entry : groups.entrySet()) {
			String id = entry.getKey();
			GroupManifest.Group group = entry.getValue();
			if (!group.selection().choosable()) continue;
			GroupManifest.Category cat = manifest.categoryOf(id);
			if (cat != null && cat.pick().isExclusive()) continue;

			if (intent.enabled().contains(id)) {
				if (!group.supports(platform)) {
					requestedUnavailable.add(id);
					reasons.get(id).add(GroupResolution.Reason.EXPLICIT_ENABLE_UNAVAILABLE);
					errors.add("Requested group '" + id + "' is not available on " + platformLabel(platform));
				} else if (blockedBy.containsKey(id)) {
					requestedUnavailable.add(id);
					reasons.get(id).add(GroupResolution.Reason.EXPLICIT_ENABLE_UNAVAILABLE);
					errors.add("Requested group '" + id + "' requires incompatible groups on " + platformLabel(platform) + ": " + String.join(", ", blockedBy.get(id)));
				} else {
					selected.add(id);
					reasons.get(id).add(GroupResolution.Reason.EXPLICIT_ENABLE);
				}
			} else if (group.selection() == GroupSelectionMode.RECOMMENDED) {
				if (intent.disabled().contains(id)) {
					reasons.get(id).add(GroupResolution.Reason.EXPLICIT_DISABLE);
				} else if (group.supports(platform) && !blockedBy.containsKey(id)) {
					selected.add(id);
					reasons.get(id).add(GroupResolution.Reason.RECOMMENDED);
				}
			}
		}

		// Pass 3: Exclusive categories (AT_MOST_ONE, EXACTLY_ONE).
		Map<String, List<String>> exclusiveMembers = exclusiveCategoryMembers(manifest);
		for (var catEntry : exclusiveMembers.entrySet()) {
			String catId = catEntry.getKey();
			GroupManifest.Category cat = categories.get(catId);
			List<String> compatible = new ArrayList<>();
			for (String id : catEntry.getValue()) {
				GroupManifest.Group group = groups.get(id);
				if (!group.supports(platform)) {
					if (intent.enabled().contains(id)) {
						requestedUnavailable.add(id);
						reasons.get(id).add(GroupResolution.Reason.EXPLICIT_ENABLE_UNAVAILABLE);
						errors.add("Requested group '" + id + "' in category '" + catId + "' is not available on " + platformLabel(platform));
					}
				} else if (blockedBy.containsKey(id)) {
					if (intent.enabled().contains(id)) {
						requestedUnavailable.add(id);
						reasons.get(id).add(GroupResolution.Reason.EXPLICIT_ENABLE_UNAVAILABLE);
						errors.add("Requested group '" + id + "' in category '" + catId + "' requires incompatible groups on " + platformLabel(platform) + ": "
								+ String.join(", ", blockedBy.get(id)));
					}
				} else {
					compatible.add(id);
				}
			}

			List<String> explicit = compatible.stream().filter(intent.enabled()::contains).toList();
			if (explicit.size() > 1) {
				for (String id : explicit) {
					reasons.get(id).add(GroupResolution.Reason.EXCLUSIVE_CONFLICT);
					for (String other : explicit) if (!other.equals(id)) related.get(id).add(other);
				}
				errors.add("Multiple groups selected in exclusive category '" + catId + "': " + String.join(", ", explicit));
			} else if (explicit.size() == 1) {
				String winner = explicit.get(0);
				selected.add(winner);
				reasons.get(winner).add(GroupResolution.Reason.EXPLICIT_ENABLE);
				for (String id : compatible) {
					if (!id.equals(winner) && groups.get(id).selection() == GroupSelectionMode.RECOMMENDED && intent.disabled().contains(id))
						reasons.get(id).add(GroupResolution.Reason.EXPLICIT_DISABLE);
				}
			} else {
				List<String> recommended = new ArrayList<>();
				for (String id : compatible) {
					GroupManifest.Group group = groups.get(id);
					if (group.selection() != GroupSelectionMode.RECOMMENDED) continue;
					if (intent.disabled().contains(id)) reasons.get(id).add(GroupResolution.Reason.EXPLICIT_DISABLE);
					else recommended.add(id);
				}
				if (recommended.size() > 1) {
					for (String id : recommended) {
						reasons.get(id).add(GroupResolution.Reason.EXCLUSIVE_CONFLICT);
						for (String other : recommended) if (!other.equals(id)) related.get(id).add(other);
					}
					errors.add("Exclusive category '" + catId + "' has multiple RECOMMENDED defaults: " + String.join(", ", recommended));
				} else if (recommended.size() == 1) {
					String winner = recommended.get(0);
					selected.add(winner);
					reasons.get(winner).add(GroupResolution.Reason.RECOMMENDED);
				} else if (cat.pick() == CategoryPickMode.EXACTLY_ONE && !compatible.isEmpty()) {
					String fallback = null;
					for (String id : compatible) {
						if (!intent.disabled().contains(id)) {
							fallback = id;
							break;
						}
					}
					if (fallback != null) {
						selected.add(fallback);
						reasons.get(fallback).add(GroupResolution.Reason.CATEGORY_DEFAULT);
					} else {
						errors.add("Category '" + catId + "' requires selecting one option");
					}
				}
			}
		}

		// Pass 4: Transitive dependency closure (`requires`) + tracking core-locked and dependency groups.
		NavigableSet<String> requiredGroups = new TreeSet<>(coreRoots);
		NavigableSet<String> dependencyGroups = new TreeSet<>();

		ArrayDeque<String> queue = new ArrayDeque<>(selected);
		Set<String> expanded = new HashSet<>();
		while (!queue.isEmpty()) {
			String parent = queue.removeFirst();
			if (!expanded.add(parent)) continue;
			boolean parentFromCore = requiredGroups.contains(parent);
			for (String dep : groups.get(parent).requires()) {
				GroupManifest.Group depGroup = groups.get(dep);
				if (depGroup == null) {
					errors.add("Group '" + parent + "' requires missing group '" + dep + "'");
					continue;
				}
				if (!depGroup.supports(platform)) {
					blockedBy.computeIfAbsent(parent, ignored -> new LinkedHashSet<>()).add(dep);
					errors.add("Group '" + parent + "' requires '" + dep + "', which is not available on " + platformLabel(platform));
					continue;
				}
				selected.add(dep);
				dependencyGroups.add(dep);
				if (parentFromCore) requiredGroups.add(dep);
				reasons.get(dep).add(GroupResolution.Reason.DEPENDENCY);
				related.get(dep).add(parent);
				queue.addLast(dep);
			}
		}
		// Keep propagation exact when a non-core group entered `dep` before a core group did.
		ArrayDeque<String> coreQueue = new ArrayDeque<>(coreRoots);
		Set<String> coreVisited = new HashSet<>();
		while (!coreQueue.isEmpty()) {
			String coreId = coreQueue.removeFirst();
			if (!coreVisited.add(coreId)) continue;
			GroupManifest.Group coreGroup = groups.get(coreId);
			if (coreGroup == null) continue;
			for (String dep : coreGroup.requires()) {
				if (selected.contains(dep) && requiredGroups.add(dep)) coreQueue.addLast(dep);
			}
		}

		// Pass 5: Verify no exclusive category ended up with > 1 group after dependency expansion.
		Map<String, List<String>> conflicts = new LinkedHashMap<>();
		for (var catEntry : exclusiveMembers.entrySet()) {
			String catId = catEntry.getKey();
			List<String> chosenInCat = new ArrayList<>();
			for (String id : catEntry.getValue()) if (selected.contains(id)) chosenInCat.add(id);
			if (chosenInCat.size() > 1) {
				conflicts.put(catId, chosenInCat);
				errors.add("Exclusive category '" + catId + "' cannot install multiple groups together: " + String.join(", ", chosenInCat));
			}
		}

		// Assemble per-group statuses.
		NavigableMap<String, GroupResolution> groupResolutions = new TreeMap<>();
		for (var entry : groups.entrySet()) {
			String id = entry.getKey();
			GroupManifest.Group group = entry.getValue();
			String catId = group.category();
			List<String> catConflicts = catId.isEmpty() ? List.of() : conflicts.getOrDefault(catId, List.of());

			GroupResolution.Status status;
			if (catConflicts.size() > 1 && catConflicts.contains(id)) {
				status = GroupResolution.Status.CONFLICT;
				reasons.get(id).add(GroupResolution.Reason.EXCLUSIVE_CONFLICT);
				for (String other : catConflicts) if (!other.equals(id)) related.get(id).add(other);
			} else if (unavailable.contains(id)) {
				status = GroupResolution.Status.UNAVAILABLE;
			} else if (blockedBy.containsKey(id)) {
				status = GroupResolution.Status.BLOCKED;
				reasons.get(id).add(GroupResolution.Reason.BLOCKED_BY_DEPENDENCY);
				related.get(id).addAll(blockedBy.get(id));
			} else if (selected.contains(id)) {
				status = GroupResolution.Status.SELECTED;
			} else if (reasons.get(id).contains(GroupResolution.Reason.EXPLICIT_DISABLE)) {
				status = GroupResolution.Status.EXCLUDED;
			} else if (reasons.get(id).contains(GroupResolution.Reason.EXCLUSIVE_CONFLICT)) {
				status = GroupResolution.Status.CONFLICT;
			} else {
				status = GroupResolution.Status.AVAILABLE;
				if (group.selection() == GroupSelectionMode.AUTOMATIC) reasons.get(id).add(GroupResolution.Reason.AUTOMATIC_UNUSED);
			}
			groupResolutions.put(id, new GroupResolution(id, status, reasons.get(id), related.get(id)));
		}

		ResolvedSelection resolution = new ResolvedSelection(intent, selected, staleGroups, requiredGroups, dependencyGroups, unavailable, requestedUnavailable, groupResolutions);
		if (!errors.isEmpty()) throw new SelectionResolutionException(errors.stream().distinct().toList(), resolution);
		return resolution;
	}

	// ------------------------------------------------------------------ UI transition operations

	public static SelectionUpdate toggleGroup(GroupManifest manifest, SelectionIntent current, String groupId, ClientPlatform platform) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(current, "current");
		GroupManifest.Group group = manifest.groups().get(groupId);
		if (group == null) return failure(current, null, "Unknown group: " + groupId);
		if (!group.selection().choosable()) return failure(current, null, "Group cannot be toggled directly");

		GroupManifest.Category category = manifest.categoryOf(groupId);
		ResolvedSelection previous = safeResolve(manifest, current, platform);
		if (previous == null) return failure(current, null, "Current selection is invalid");

		boolean currentlySelected = previous.selectedGroups().contains(groupId);
		if (category != null && category.pick().isExclusive()) {
			if (currentlySelected) {
				if (category.pick() == CategoryPickMode.EXACTLY_ONE) return failure(current, previous, "This category requires keeping one option selected");
				return pickInCategory(manifest, current, group.category(), null, platform);
			}
			return pickInCategory(manifest, current, group.category(), groupId, platform);
		}

		if (currentlySelected && previous.requiredGroups().contains(groupId))
			return failure(current, previous, "Required by core modpack");

		Set<String> enabled = new TreeSet<>(current.enabled());
		Set<String> disabled = new TreeSet<>(current.disabled());
		if (currentlySelected) {
			Set<String> cascade = selectedDependentsClosure(manifest, previous.selectedGroups(), groupId);
			for (String off : cascade) {
				GroupManifest.Group offGroup = manifest.groups().get(off);
				if (offGroup == null || !offGroup.selection().choosable()) continue;
				if (previous.requiredGroups().contains(off))
					return failure(current, previous, "Cannot turn off because '" + manifest.displayName(off) + "' is required");
				GroupManifest.Category offCat = manifest.categoryOf(off);
				if (offCat != null && offCat.pick() == CategoryPickMode.EXACTLY_ONE) {
					return failure(current, previous, "Cannot turn off mandatory option '" + manifest.displayName(off) + "'");
				}
				enabled.remove(off);
				if (offGroup.selection() == GroupSelectionMode.RECOMMENDED) disabled.add(off);
			}
			// Clean any stale disabled non-RECOMMENDED ids that no longer apply.
			pruneEnabledNonExclusive(manifest, enabled, disabled);
		} else {
			if (!group.supports(platform)) return failure(current, previous, "Not available on this platform");
			enableGroupTree(manifest, groupId, enabled, disabled, platform);
		}

		SelectionIntent nextIntent = new SelectionIntent(enabled, disabled, current.platform());
		return settleTransition(manifest, current, previous, nextIntent, Set.of(groupId), platform);
	}

	public static SelectionUpdate pickInCategory(GroupManifest manifest, SelectionIntent current, String categoryId, String chosenGroupId, ClientPlatform platform) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(current, "current");
		String chosenId = (chosenGroupId == null || chosenGroupId.isEmpty()) ? null : chosenGroupId;
		GroupManifest.Category category = manifest.categories().get(categoryId);
		if (category == null || !category.pick().isExclusive()) return failure(current, null, "Not an exclusive category: " + categoryId);
		if (chosenId == null && category.pick() == CategoryPickMode.EXACTLY_ONE)
			return failure(current, safeResolve(manifest, current, platform), "Category requires one option");
		if (chosenId != null) {
			GroupManifest.Group chosen = manifest.groups().get(chosenId);
			if (chosen == null || !categoryId.equals(chosen.category())) return failure(current, null, "Group does not belong to category: " + categoryId);
			if (!chosen.supports(platform)) return failure(current, safeResolve(manifest, current, platform), "Not available on this platform");
		}

		ResolvedSelection previous = safeResolve(manifest, current, platform);
		if (previous == null) return failure(current, null, "Current selection is invalid");

		Set<String> enabled = new TreeSet<>(current.enabled());
		Set<String> disabled = new TreeSet<>(current.disabled());
		List<String> members = categoryMembers(manifest, categoryId);

		// Cascade off currently selected choosable dependents outside this category whose dependency was a displaced sibling.
		for (String sibling : members) {
			if (sibling.equals(chosenId) || !previous.selectedGroups().contains(sibling)) continue;
			for (String dep : selectedDependentsClosure(manifest, previous.selectedGroups(), sibling)) {
				GroupManifest.Group depGroup = manifest.groups().get(dep);
				if (depGroup != null && !categoryId.equals(depGroup.category()) && depGroup.selection().choosable() && !previous.requiredGroups().contains(dep)) {
					enabled.remove(dep);
					if (depGroup.selection() == GroupSelectionMode.RECOMMENDED) disabled.add(dep);
				}
			}
		}

		for (String sibling : members) {
			enabled.remove(sibling);
			disabled.remove(sibling);
		}

		if (chosenId == null) {
			for (String sibling : members) {
				GroupManifest.Group siblingGroup = manifest.groups().get(sibling);
				if (siblingGroup != null && siblingGroup.supports(platform) && siblingGroup.selection() == GroupSelectionMode.RECOMMENDED) disabled.add(sibling);
			}
		} else {
			enableGroupTree(manifest, chosenId, enabled, disabled, platform);
			// Drop redundant enabled flag when the category's clean state already selects chosenId naturally.
			Set<String> probeEnabled = new TreeSet<>(enabled);
			probeEnabled.remove(chosenId);
			try {
				ResolvedSelection probe = resolve(manifest, new SelectionIntent(probeEnabled, disabled, current.platform()), platform);
				if (probe.selectedGroups().contains(chosenId)) enabled.remove(chosenId);
			} catch (SelectionResolutionException ignored) {
			}
		}

		SelectionIntent nextIntent = new SelectionIntent(enabled, disabled, current.platform());
		Set<String> suppressed = chosenId == null ? Set.copyOf(members) : Set.of(chosenId);
		return settleTransition(manifest, current, previous, nextIntent, suppressed, platform);
	}

	public static SelectionUpdate setCategoryAll(GroupManifest manifest, SelectionIntent current, String categoryId, boolean selectAll, ClientPlatform platform) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(current, "current");
		GroupManifest.Category category = manifest.categories().get(categoryId);
		if (category == null || category.pick().isExclusive()) return failure(current, null, "Bulk toggle is only valid on ANY categories");

		ResolvedSelection previous = safeResolve(manifest, current, platform);
		if (previous == null) return failure(current, null, "Current selection is invalid");

		Set<String> enabled = new TreeSet<>(current.enabled());
		Set<String> disabled = new TreeSet<>(current.disabled());
		List<String> members = categoryMembers(manifest, categoryId);

		if (selectAll) {
			for (String id : members) {
				GroupManifest.Group g = manifest.groups().get(id);
				if (g == null || !g.selection().choosable() || !g.supports(platform)) continue;
				enableGroupTree(manifest, id, enabled, disabled, platform);
			}
		} else {
			Set<String> toClear = new LinkedHashSet<>();
			for (String id : members) {
				GroupManifest.Group g = manifest.groups().get(id);
				if (g == null || !g.selection().choosable() || previous.requiredGroups().contains(id)) continue;
				if (previous.selectedGroups().contains(id)) toClear.addAll(selectedDependentsClosure(manifest, previous.selectedGroups(), id));
				else {
					enabled.remove(id);
					if (g.selection() == GroupSelectionMode.RECOMMENDED) disabled.add(id);
				}
			}
			for (String off : toClear) {
				GroupManifest.Group offGroup = manifest.groups().get(off);
				if (offGroup == null || !offGroup.selection().choosable() || previous.requiredGroups().contains(off)) continue;
				GroupManifest.Category offCat = manifest.categoryOf(off);
				if (offCat != null && offCat.pick() == CategoryPickMode.EXACTLY_ONE)
					return failure(current, previous, "Cannot clear because '" + manifest.displayName(off) + "' is mandatory");
				enabled.remove(off);
				if (offGroup.selection() == GroupSelectionMode.RECOMMENDED) disabled.add(off);
			}
		}

		SelectionIntent nextIntent = new SelectionIntent(enabled, disabled, current.platform());
		Set<String> suppressed = new HashSet<>(members);
		return settleTransition(manifest, current, previous, nextIntent, suppressed, platform);
	}

	public static SelectionUpdate withPlatform(GroupManifest manifest, SelectionIntent current, ClientPlatform override, ClientPlatform effectivePlatform) {
		Objects.requireNonNull(manifest, "manifest");
		Objects.requireNonNull(current, "current");
		ResolvedSelection previous = safeResolve(manifest, current, effectivePlatform);
		Set<String> enabled = new TreeSet<>();
		for (String id : current.enabled()) {
			GroupManifest.Group g = manifest.groups().get(id);
			if (g != null && g.supports(effectivePlatform) && incompatibleTransitiveDeps(manifest, id, effectivePlatform).isEmpty()) enabled.add(id);
		}
		SelectionIntent nextIntent = new SelectionIntent(enabled, current.disabled(), override);
		try {
			ResolvedSelection next = resolve(manifest, nextIntent, effectivePlatform);
			NavigableSet<String> turnedOff = new TreeSet<>();
			for (String id : current.enabled()) if (!enabled.contains(id)) turnedOff.add(id);
			return new SelectionUpdate(nextIntent, next, turnedOff, "");
		} catch (SelectionResolutionException e) {
			return new SelectionUpdate(current, previous, Collections.emptyNavigableSet(), String.join("; ", e.errors()));
		}
	}

	private static SelectionUpdate settleTransition(GroupManifest manifest, SelectionIntent previousIntent, ResolvedSelection previous,
			SelectionIntent candidateIntent, Set<String> suppressFromNotice, ClientPlatform platform) {
		try {
			ResolvedSelection next = resolve(manifest, candidateIntent, platform);
			NavigableSet<String> turnedOff = new TreeSet<>();
			for (String id : previous.selectedGroups()) {
				if (next.selectedGroups().contains(id) || suppressFromNotice.contains(id)) continue;
				GroupManifest.Group g = manifest.groups().get(id);
				if (g != null && !g.selection().automatic()) turnedOff.add(id);
			}
			return new SelectionUpdate(candidateIntent, next, turnedOff, "");
		} catch (SelectionResolutionException e) {
			String msg = e.errors().isEmpty() ? "Selection cannot be resolved" : e.errors().get(0);
			return new SelectionUpdate(previousIntent, previous, Collections.emptyNavigableSet(), msg);
		}
	}

	private static void enableGroupTree(GroupManifest manifest, String groupId, Set<String> enabled, Set<String> disabled, ClientPlatform platform) {
		GroupManifest.Group target = manifest.groups().get(groupId);
		if (target == null) return;
		if (target.selection() == GroupSelectionMode.OPTIONAL) enabled.add(groupId);
		disabled.remove(groupId);

		// Transitive dependencies: clear disabled, and if a dependency sits in an exclusive category, make room for it.
		ArrayDeque<String> queue = new ArrayDeque<>(target.requires());
		Set<String> visited = new HashSet<>();
		visited.add(groupId);
		while (!queue.isEmpty()) {
			String depId = queue.removeFirst();
			if (!visited.add(depId)) continue;
			GroupManifest.Group dep = manifest.groups().get(depId);
			if (dep == null || !dep.supports(platform)) continue;
			disabled.remove(depId);
			GroupManifest.Category depCat = manifest.categoryOf(depId);
			if (depCat != null && depCat.pick().isExclusive()) {
				for (String sibling : categoryMembers(manifest, dep.category())) {
					if (!sibling.equals(depId)) enabled.remove(sibling);
				}
				if (dep.selection().choosable()) enabled.add(depId);
			}
			queue.addAll(dep.requires());
		}
	}

	private static void pruneEnabledNonExclusive(GroupManifest manifest, Set<String> enabled, Set<String> disabled) {
		Iterator<String> it = enabled.iterator();
		while (it.hasNext()) {
			String id = it.next();
			GroupManifest.Group g = manifest.groups().get(id);
			if (g == null) continue;
			GroupManifest.Category cat = manifest.categoryOf(id);
			if ((cat == null || !cat.pick().isExclusive()) && g.selection() == GroupSelectionMode.RECOMMENDED) it.remove();
		}
	}

	private static Set<String> selectedDependentsClosure(GroupManifest manifest, Set<String> selectedGroups, String targetId) {
		Set<String> closure = new LinkedHashSet<>();
		closure.add(targetId);
		boolean changed = true;
		while (changed) {
			changed = false;
			for (String candidate : selectedGroups) {
				if (closure.contains(candidate)) continue;
				GroupManifest.Group g = manifest.groups().get(candidate);
				if (g == null) continue;
				for (String dep : g.requires()) {
					if (closure.contains(dep)) {
						closure.add(candidate);
						changed = true;
						break;
					}
				}
			}
		}
		return closure;
	}

	private static SelectionUpdate failure(SelectionIntent current, ResolvedSelection fallback, String message) {
		return new SelectionUpdate(current, fallback, Collections.emptyNavigableSet(), message);
	}

	private static ResolvedSelection safeResolve(GroupManifest manifest, SelectionIntent intent, ClientPlatform platform) {
		try {
			return resolve(manifest, intent, platform);
		} catch (SelectionResolutionException e) {
			return e.resolution();
		}
	}

	private static Set<String> incompatibleTransitiveDeps(GroupManifest manifest, String groupId, ClientPlatform platform) {
		Set<String> bad = new LinkedHashSet<>();
		ArrayDeque<String> queue = new ArrayDeque<>(manifest.groups().get(groupId).requires());
		Set<String> visited = new HashSet<>();
		while (!queue.isEmpty()) {
			String depId = queue.removeFirst();
			if (!visited.add(depId)) continue;
			GroupManifest.Group dep = manifest.groups().get(depId);
			if (dep == null) continue;
			if (!dep.supports(platform)) bad.add(depId);
			queue.addAll(dep.requires());
		}
		return bad;
	}

	private static List<String> categoryMembers(GroupManifest manifest, String categoryId) {
		List<String> members = new ArrayList<>();
		for (var entry : manifest.groups().entrySet()) {
			if (categoryId.equals(entry.getValue().category())) members.add(entry.getKey());
		}
		return members;
	}

	private static Map<String, List<String>> exclusiveCategoryMembers(GroupManifest manifest) {
		Map<String, List<String>> members = new LinkedHashMap<>();
		for (var entry : manifest.categories().entrySet()) {
			if (entry.getValue().pick().isExclusive()) members.put(entry.getKey(), new ArrayList<>());
		}
		if (members.isEmpty()) return Map.of();
		for (var entry : manifest.groups().entrySet()) {
			String catId = entry.getValue().category();
			List<String> list = members.get(catId);
			if (list != null) list.add(entry.getKey());
		}
		return members;
	}

	private static String platformLabel(ClientPlatform platform) {
		return platform == null ? "undetected platform" : platform.id();
	}
}
