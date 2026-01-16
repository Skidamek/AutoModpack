package pl.skidam.automodpack_core.utils.sort;

import pl.skidam.automodpack_core.utils.FileInspection;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

public class DependencyOptimizer {

    /**
     * Optimizes the standard mod set.
     * Retains mods that are:
     * 1. Seeds (Mods in Standard NOT in Modpack)
     * 2. Dependencies of those Seeds (Recursively)
     */
    public static Set<FileInspection.Mod> optimize(Set<FileInspection.Mod> standardMods, Set<FileInspection.Mod> modpackMods) {
        // Create a global lookup map: ID -> Top-Level Mod Container
        Map<String, FileInspection.Mod> idToContainerMap = new HashMap<>();

        // Map Modpack first (Low Priority)
        mapIdsToContainer(modpackMods, idToContainerMap);
        // Map Standard second (High Priority - Overwrites Modpack entries)
        // This ensures trace() prefers the existing Standard file if available.
        mapIdsToContainer(standardMods, idToContainerMap);

        // Identify "Seeds" (User-added mods that are NOT in the modpack)
        // We check if the modpack provides the IDs. If not, it's a seed.
        Set<String> modpackIds = new HashSet<>();
        collectAllIds(modpackMods, modpackIds);

        Set<FileInspection.Mod> seeds = standardMods.stream()
                .filter(mod -> mod.IDs().stream().noneMatch(modpackIds::contains))
                .collect(Collectors.toSet());

        // Trace dependencies starting from Seeds
        Set<FileInspection.Mod> essentialMods = new HashSet<>();
        for (FileInspection.Mod seed : seeds) {
            trace(seed, idToContainerMap, essentialMods);
        }

        // Return the subset of standardMods that are essential
        return standardMods.stream()
                .filter(essentialMods::contains)
                .collect(Collectors.toSet());
    }

    /**
     * Finds standard mods that have a counterpart in the modpack with a DIFFERENT version.
     * Returns Map: <StandardMod (File on Disk), ModpackMod (Target)>
     */
    public static Map<FileInspection.Mod, FileInspection.Mod> findMismatchedVersions(Set<FileInspection.Mod> standardMods, Set<FileInspection.Mod> modpackMods) {
        Map<FileInspection.Mod, FileInspection.Mod> mismatches = new HashMap<>();

        // Map Modpack IDs for version lookup
        Map<String, FileInspection.Mod> modpackMap = new HashMap<>();
        mapIdsToContainer(modpackMods, modpackMap);

        for (FileInspection.Mod stdMod : standardMods) {
            // Check IDs provided by this standard mod
            for (String id : stdMod.IDs()) {
                FileInspection.Mod packMod = modpackMap.get(id);

                // If modpack has this ID, but version differs
                if (packMod != null && !areVersionsEqual(stdMod.version(), packMod.version())) {
                    mismatches.put(stdMod, packMod);
                    // Stop checking other IDs for this file, one mismatch is enough to trigger replacement
                    break;
                }
            }
        }
        return mismatches;
    }

    // Depth-First Search to mark dependencies as essential
    private static void trace(FileInspection.Mod current, Map<String, FileInspection.Mod> lookup, Set<FileInspection.Mod> visited) {
        if (current == null || visited.contains(current)) return;

        visited.add(current);

        // Trace direct dependencies
        if (current.deps() != null) {
            for (String depId : current.deps()) {
                FileInspection.Mod provider = lookup.get(depId);
                trace(provider, lookup, visited);
            }
        }
    }

    // Recursively maps IDs from a mod and its nested children to the TOP-LEVEL container file.
    private static void mapIdsToContainer(Set<FileInspection.Mod> mods, Map<String, FileInspection.Mod> map) {
        for (FileInspection.Mod container : mods) {
            registerIdsRecursive(container, container, map);
        }
    }

    private static void registerIdsRecursive(FileInspection.Mod current, FileInspection.Mod topLevelContainer, Map<String, FileInspection.Mod> map) {
        // Register current mod's IDs
        if (current.IDs() != null) {
            for (String id : current.IDs()) {
                map.put(id, topLevelContainer);
            }
        }
        // Recurse into nested mods
        if (current.nestedMods() != null) {
            for (FileInspection.Mod nested : current.nestedMods()) {
                registerIdsRecursive(nested, topLevelContainer, map);
            }
        }
    }

    private static void collectAllIds(Set<FileInspection.Mod> mods, Set<String> targetSet) {
        for (FileInspection.Mod mod : mods) {
            collectIdsRecursive(mod, targetSet);
        }
    }

    private static void collectIdsRecursive(FileInspection.Mod mod, Set<String> targetSet) {
        if (mod.IDs() != null) targetSet.addAll(mod.IDs());
        if (mod.nestedMods() != null) {
            mod.nestedMods().forEach(n -> collectIdsRecursive(n, targetSet));
        }
    }

    private static boolean areVersionsEqual(String v1, String v2) {
        if (v1 == null) return v2 == null;
        return v1.equals(v2);
    }
}