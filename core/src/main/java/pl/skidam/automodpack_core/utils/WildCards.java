package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;
import static pl.skidam.automodpack_core.utils.CustomFileUtils.formatPath;

public class WildCards {

    private final Map<String, Path> wildcardMatches = new HashMap<>();

    public boolean fileMatches(String path, Path file) {
        return wildcardMatches.containsKey(path) || wildcardMatches.containsValue(file);
    }

    public Map<String, Path> getWildcardMatches() {
        return wildcardMatches;
    }

    private final List<String> RULES;
    private final Set<Path> START_DIRECTORIES;

    public WildCards(List<String> rules, Set<Path> startDirectories) {
        RULES = rules;
        START_DIRECTORIES = startDirectories;
    }

    private static final Map<Path, Set<Path>> discoveredDirectories = new HashMap<>();
    
    // Cache for formatted paths to avoid recomputation
    private final Map<Path, String> formattedPathCache = new HashMap<>();
    
    // Cache for parsed wildcard patterns
    private static class WildcardPattern {
        final String prefix;
        final String suffix;
        final int layerCount;
        
        WildcardPattern(String prefix, String suffix, int layerCount) {
            this.prefix = prefix;
            this.suffix = suffix;
            this.layerCount = layerCount;
        }
    }
    
    private final Map<String, WildcardPattern> wildcardPatternCache = new HashMap<>();

    public static void clearDiscoveredDirectories() {
        discoveredDirectories.clear();
    }

    public void match() {
        try {
            if (RULES == null || RULES.isEmpty()) {
                return;
            }

            separateRules(RULES);
            Map<String, List<String>> composedWhiteRules = composeRules(whiteListRules);
            Map<String, List<String>> composedBlackRules = composeRules(blackListRules);
            
            // Pre-compute wildcard patterns for faster matching
            precomputeWildcardPatterns(composedWhiteRules);
            precomputeWildcardPatterns(composedBlackRules);

            for (Path startDirectory : START_DIRECTORIES) {
                Set<Path> alreadyDiscovered = discoveredDirectories.getOrDefault(startDirectory, Set.of());
                if (!alreadyDiscovered.isEmpty()) {
                    for (Path node : alreadyDiscovered) {
                        try {
                            matchWhiteRules(node, startDirectory, composedWhiteRules);
                        } catch (Exception e) {
                            LOGGER.error("Error processing file: {} From already discovered directory: {}", node, startDirectory, e);
                        }
                    }
                } else {
                    // Smart directory walking: only walk directories that match rules
                    walkSmartly(startDirectory, composedWhiteRules);
                }

                matchBlackRules(startDirectory, composedBlackRules);
            }
        } catch (Exception e) {
            LOGGER.error("Failed to walk directories: {}", START_DIRECTORIES, e);
        }
    }
    
    /**
     * Pre-compute wildcard patterns to avoid repeated parsing during matching
     */
    private void precomputeWildcardPatterns(Map<String, List<String>> rules) {
        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            String ruleDirectory = entry.getKey();
            if (ruleDirectory.contains("*")) {
                parseWildcardPattern(ruleDirectory);
            }
            
            for (String rulePath : entry.getValue()) {
                if (rulePath.contains("*")) {
                    parseWildcardPattern(rulePath);
                }
            }
        }
    }
    
    /**
     * Parse and cache wildcard pattern
     */
    private WildcardPattern parseWildcardPattern(String pattern) {
        return wildcardPatternCache.computeIfAbsent(pattern, p -> {
            int wildcardIndex = p.indexOf("*");
            if (wildcardIndex == -1) {
                return null;
            }
            
            // Only one * in the rule path is allowed
            if (p.indexOf("*", wildcardIndex + 1) != -1) {
                LOGGER.error("Only one * in the rule path is allowed: {}", p);
                return null;
            }
            
            String[] parts = p.split("\\*", 2);
            String prefix = parts[0];
            String suffix = parts.length > 1 ? parts[1] : "";
            int layerCount = p.split("/").length;
            
            return new WildcardPattern(prefix, suffix, layerCount);
        });
    }
    
    /**
     * Smart directory walking: only walk directories that could match rules
     */
    private void walkSmartly(Path startDirectory, Map<String, List<String>> composedWhiteRules) throws IOException {
        Set<Path> discoveredFiles = new HashSet<>();
        
        // Determine which directories need to be walked
        Set<Path> directoriesToWalk = new HashSet<>();
        
        for (Map.Entry<String, List<String>> entry : composedWhiteRules.entrySet()) {
            String ruleDirectory = entry.getKey();
            List<String> rulePaths = entry.getValue();
            
            // Check if any rule needs recursive walk
            boolean needsRecursiveWalk = false;
            for (String rulePath : rulePaths) {
                if (rulePath.equals("/**")) {
                    needsRecursiveWalk = true;
                    break;
                }
            }
            
            // If rule directory contains wildcards, we need to walk parent directories
            if (ruleDirectory.contains("*")) {
                // Find the non-wildcard prefix to determine where to start walking
                int wildcardIndex = ruleDirectory.indexOf("*");
                int lastSlash = ruleDirectory.lastIndexOf("/", wildcardIndex);
                String baseDir = lastSlash > 0 ? ruleDirectory.substring(0, lastSlash) : "";
                // Remove leading "/" from baseDir for path resolution
                if (baseDir.startsWith("/")) {
                    baseDir = baseDir.substring(1);
                }
                Path targetDir = baseDir.isEmpty() ? startDirectory : startDirectory.resolve(baseDir);
                if (Files.exists(targetDir) && Files.isDirectory(targetDir)) {
                    directoriesToWalk.add(targetDir);
                }
            } else {
                // For exact directory matches, only walk that specific directory
                // Remove leading "/" from ruleDirectory for path resolution
                String cleanRuleDir = ruleDirectory.startsWith("/") ? ruleDirectory.substring(1) : ruleDirectory;
                Path targetDir = cleanRuleDir.isEmpty() ? startDirectory : startDirectory.resolve(cleanRuleDir);
                if (Files.exists(targetDir) && Files.isDirectory(targetDir)) {
                    directoriesToWalk.add(targetDir);
                }
            }
        }
        
        // Perform the walk
        if (directoriesToWalk.isEmpty()) {
            // If no specific directories were identified, do a full walk
            try (Stream<Path> paths = Files.walk(startDirectory)) {
                paths.filter(Files::isRegularFile)
                        .forEach(node -> {
                            discoveredFiles.add(node);
                            matchWhiteRules(node, startDirectory, composedWhiteRules);
                        });
            } catch (Exception e) {
                LOGGER.error("Error processing files in directory: {}", startDirectory, e);
            }
        } else {
            // Walk only specific directories
            for (Path targetDir : directoriesToWalk) {
                try (Stream<Path> paths = Files.walk(targetDir)) {
                    paths.filter(Files::isRegularFile)
                            .forEach(node -> {
                                discoveredFiles.add(node);
                                matchWhiteRules(node, startDirectory, composedWhiteRules);
                            });
                } catch (Exception e) {
                    LOGGER.error("Error processing files in directory: {}", targetDir, e);
                }
            }
        }
        
        // Cache discovered files
        discoveredDirectories.put(startDirectory, discoveredFiles);
    }

    private Map<String, List<String>> composeRules(List<String> rules) {
        Map<String, List<String>> directoryRulePathsMap = new HashMap<>(rules.size());

        for (String rule : rules) {
            if (rule == null || rule.isBlank()) {
                continue;
            }

            int lastSlashIndex = rule.lastIndexOf("/");
            if (lastSlashIndex == -1) {
                continue;
            }

            String directoryPart = rule.substring(0, lastSlashIndex);
            String rulePath = rule.substring(lastSlashIndex);

            if (directoryPart.contains("*")) {
                LOGGER.warn("Wildcards in directories are experimental! Use with caution.");
            }

            directoryRulePathsMap.computeIfAbsent(directoryPart, k -> new ArrayList<>()).add(rulePath);
        }

        return directoryRulePathsMap;
    }

    private final List<String> whiteListRules = new ArrayList<>();
    private final List<String> blackListRules = new ArrayList<>();

    private void separateRules(List<String> rules) {
        for (String rule : rules) {
            if (rule == null || rule.isBlank()) {
                continue;
            }

            if (rule.startsWith("!")) {
                blackListRules.add(rule.substring(1));
            } else {
                whiteListRules.add(rule);
            }
        }
    }

    private void matchWhiteRules(Path node, Path startDirectory, Map<String, List<String>> composedWhiteRules) {
        String formattedPath = matchesRules(node, startDirectory, composedWhiteRules);
        if (formattedPath != null) {
            wildcardMatches.put(formattedPath, node);
        }
    }

    private void matchBlackRules(Path startDirectory, Map<String, List<String>> composedBlackRules) {
        Set<String> pathsToRemove = new HashSet<>();

        for (Path node : getWildcardMatches().values()) {
            String formattedPath = matchesRules(node, startDirectory, composedBlackRules);
            if (formattedPath != null) {
                pathsToRemove.add(formattedPath);
            }
        }

        for (String path : pathsToRemove) {
            wildcardMatches.remove(path);
        }
    }


    private String matchesRules(Path node, Path startDirectory, Map<String, List<String>> rules) {
        // Cache formatted path to avoid repeated computation
        final String formattedPath = formattedPathCache.computeIfAbsent(node, 
            n -> formatPath(n, startDirectory));

        int lastSlashIndex = formattedPath.lastIndexOf("/");
        if (lastSlashIndex == -1) {
            return null; // No directory part
        }

        String directoryPart = formattedPath.substring(0, lastSlashIndex);
        String fileNamePart = formattedPath.substring(lastSlashIndex);

        // Iterate through the rules and check directory matches
        for (Map.Entry<String, List<String>> entry : rules.entrySet()) {
            String ruleDirectory = entry.getKey();
            List<String> rulePaths = entry.getValue();

            boolean directoryMatch = directoryPart.startsWith(ruleDirectory);
            boolean directoryStrictMatch = directoryPart.equals(ruleDirectory);

            // Resolve wildcard in the directory part
            if (ruleDirectory.contains("*")) {
                // TODO: fix edge-cases
//                Wildcards:
//                /kubejs/**
//                !/kubejs/server*/**
//                Removing path: /kubejs/server_scripts/README.txt
//                HERE - It should also remove the README.txt file from subdirectoriy of the server_scripts
//                --- Matched Paths ---
//                /kubejs/server_scripts/01_unification/README.txt
                if (wildcardMatchCached(directoryPart, ruleDirectory)) {
                    directoryMatch = true;
                    directoryStrictMatch = true;
                }
            }

            // Check if the directory part matches the rule
            if (directoryMatch) {
                for (String rulePath : rulePaths) {
                    if (rulePath.equals("/**")) {
                        // Match all files in the directory
                        return formattedPath;
                    } else if (rulePath.equals("/*") || rulePath.equals("/")) {
                        if (!directoryStrictMatch) {
                            continue;
                        }

                        // Match any file in the directory
                        return formattedPath;
                    } else if (rulePath.contains("*")) {
                        if (!directoryStrictMatch) {
                            continue;
                        }

                        if (wildcardMatchCached(fileNamePart, rulePath)) {
                            return formattedPath;
                        }
                    } else if (rulePath.equals(fileNamePart)) { // Exact match
                        return formattedPath;
                    }
                }
            }
        }

        return null;
    }
    
    /**
     * Optimized wildcard matching using cached patterns
     */
    private boolean wildcardMatchCached(String target, String rule) {
        if (target.equals(rule)) {
            return true;
        }
        
        WildcardPattern pattern = wildcardPatternCache.get(rule);
        if (pattern == null) {
            pattern = parseWildcardPattern(rule);
            if (pattern == null) {
                return false;
            }
        }
        
        int targetLayers = target.split("/").length;
        if (targetLayers != pattern.layerCount) {
            return false;
        }
        
        return target.startsWith(pattern.prefix) && target.endsWith(pattern.suffix);
    }
}