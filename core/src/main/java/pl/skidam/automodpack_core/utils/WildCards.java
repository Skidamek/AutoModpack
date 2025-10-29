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

    private final Map<Path, Set<Path>> discoveredDirectories = new HashMap<>();
    
    // Cache for formatted paths to avoid recomputation
    private final Map<Path, String> formattedPathCache = new HashMap<>();
    
    // Cache for split path components
    private final Map<String, String[]> pathComponentsCache = new HashMap<>();

    /**
     * Parsed rule structure with depth information for pruning
     * @param components  Path split into components
     * @param isRecursive If rule ends with /**
     * @param minDepth    Minimum directory depth required
     * @param maxDepth    Maximum directory depth (-1 for unlimited)
     */
    private record ParsedRule(String originalRule, String[] components, boolean isRecursive, int minDepth, int maxDepth) {
        ParsedRule(String originalRule, String[] components, boolean isRecursive) {
            this(originalRule, components, isRecursive, components.length, isRecursive ? -1 : components.length);
        }
    }
    
    private final List<ParsedRule> whiteListRules = new ArrayList<>();
    private final List<ParsedRule> blackListRules = new ArrayList<>();

    public void match() {
        try {
            if (RULES == null || RULES.isEmpty()) {
                return;
            }

            parseRules(RULES);

            for (Path startDirectory : START_DIRECTORIES) {
                Set<Path> alreadyDiscovered = discoveredDirectories.getOrDefault(startDirectory, Set.of());
                if (!alreadyDiscovered.isEmpty()) {
                    for (Path node : alreadyDiscovered) {
                        try {
                            if (!Files.isRegularFile(node)) {
                                continue;
                            }
                            matchWhiteRules(node, startDirectory);
                        } catch (Exception e) {
                            LOGGER.error("Error processing file: {} From already discovered directory: {}", node, startDirectory, e);
                        }
                    }
                } else {
                    smartWalk(startDirectory);
                }

                matchBlackRules();
            }
        } catch (Exception e) {
            LOGGER.error("Failed to walk directories: {}", START_DIRECTORIES, e);
        }
    }
    
    /**
     * Parse rules into structured format for efficient matching
     */
    private void parseRules(List<String> rules) {
        for (String rule : rules) {
            if (rule == null || rule.isBlank()) {
                continue;
            }

            boolean isBlacklist = rule.startsWith("!");
            String cleanRule = isBlacklist ? rule.substring(1) : rule;
            
            // Check if it's a recursive rule (ends with /**)
            boolean isRecursive = cleanRule.endsWith("/**");
            if (isRecursive) {
                cleanRule = cleanRule.substring(0, cleanRule.length() - 3); // Remove /**
            }

            String[] components = splitPathIntoComponents(cleanRule);

            ParsedRule parsedRule = new ParsedRule(
                rule,
                components,
                isRecursive
            );
            
            if (isBlacklist) {
                blackListRules.add(parsedRule);
            } else {
                whiteListRules.add(parsedRule);
            }
        }
    }

    /**
     * Smart directory walking with aggressive pruning
     * Only traverses directories that could contain matches
     * Dramatically reduces I/O operations: O(matching paths) instead of O(all files)
     */
    private void smartWalk(Path startDirectory) {
        Set<Path> discoveredFiles = new HashSet<>();
        smartWalkRecursive(startDirectory, startDirectory, new String[0], discoveredFiles);
        discoveredDirectories.put(startDirectory, discoveredFiles);
    }
    
    /**
     * Recursive directory walk with early pruning
     * Stops traversing branches that cannot possibly contain matches
     * 
     * @param current Current directory
     * @param startDirectory Root directory
     * @param currentComponents Path components from root to current directory
     * @param discoveredFiles Accumulator for matching files
     */
    private void smartWalkRecursive(Path current, Path startDirectory, String[] currentComponents, Set<Path> discoveredFiles) {
        // Early pruning: check if current path could lead to matches
        if (!couldMatchAnyRule(currentComponents)) {
            return; // Prune this entire branch
        }
        
        try (Stream<Path> entries = Files.list(current)) {
            entries.forEach(entry -> {
                String entryName = entry.getFileName().toString();

                if (Files.isDirectory(entry)) {
                    // Build path for subdirectory
                    String[] newComponents = Arrays.copyOf(currentComponents, currentComponents.length + 1);
                    newComponents[currentComponents.length] = entryName;

                    // Recursively walk subdirectory (with pruning)
                    smartWalkRecursive(entry, startDirectory, newComponents, discoveredFiles);
                } else if (Files.isRegularFile(entry)) {
                    // Match file against whitelist rules
                    discoveredFiles.add(entry);
                    matchWhiteRules(entry, startDirectory);
                }
            });
        } catch (IOException e) {
            LOGGER.error("Error listing directory: {}", current, e);
        }
    }
    
    /**
     * Check if a directory path could possibly lead to rule matches
     * This is the key optimization - prunes directories early
     * 
     * @param dirComponents Directory path components
     * @return true if any rule could match files in or under this directory
     */
    private boolean couldMatchAnyRule(String[] dirComponents) {
        // Root directory always could match
        if (dirComponents.length == 0) {
            return true;
        }
        
        // Check against all whitelist rules
        for (ParsedRule rule : whiteListRules) {
            if (couldMatchRule(dirComponents, rule)) {
                return true;
            }
        }
        
        return false;
    }
    
    /**
     * Check if directory path is compatible with a specific rule
     * Used for early pruning to minimize I/O
     * 
     * @param dirComponents Directory path components
     * @param rule Rule to check against
     * @return true if this directory could contain files matching the rule
     */
    private boolean couldMatchRule(String[] dirComponents, ParsedRule rule) {
        // If we're already deeper than non-recursive rule allows, prune
        if (rule.maxDepth != -1 && dirComponents.length >= rule.maxDepth) {
            return false;
        }
        
        // Check if directory path matches the rule prefix
        // We check up to min(dirDepth, ruleDepth-1) because last component is the filename
        int checkDepth = Math.min(dirComponents.length, rule.components.length - 1);
        
        for (int i = 0; i < checkDepth; i++) {
            if (!matchComponent(dirComponents[i], rule.components[i])) {
                return false; // This directory doesn't match rule path
            }
        }
        
        return true; // Directory is compatible with rule
    }

    private void matchWhiteRules(Path node, Path startDirectory) {
        String formattedPath = getFormattedPath(node, startDirectory);
        if (formattedPath == null) {
            return;
        }
        
        String[] pathComponents = splitPathIntoComponents(formattedPath);
        
        for (ParsedRule rule : whiteListRules) {
            if (matchesRule(pathComponents, rule)) {
                wildcardMatches.put(formattedPath, node);
                break; // File matched, no need to check other rules
            }
        }
    }

    private void matchBlackRules() {
        Set<String> pathsToRemove = new HashSet<>();

        for (Map.Entry<String, Path> entry : new HashMap<>(wildcardMatches).entrySet()) {
            String formattedPath = entry.getKey();
            String[] pathComponents = splitPathIntoComponents(formattedPath);
            
            for (ParsedRule rule : blackListRules) {
                if (matchesRule(pathComponents, rule)) {
                    pathsToRemove.add(formattedPath);
                    break;
                }
            }
        }

        for (String path : pathsToRemove) {
            wildcardMatches.remove(path);
        }
    }
    
    /**
     * Get formatted path with caching
     */
    private String getFormattedPath(Path node, Path startDirectory) {
        return formattedPathCache.computeIfAbsent(node, 
            n -> formatPath(n, startDirectory));
    }
    
    /**
     * Split path into components with caching
     * e.g., "/config/bar/fool/config.txt" -> ["config", "bar", "fool", "config.txt"]
     */
    private String[] splitPathIntoComponents(String formattedPath) {
        return pathComponentsCache.computeIfAbsent(formattedPath, path -> {
            String[] components = path.split("/");
            List<String> componentList = new ArrayList<>();
            for (String comp : components) {
                if (!comp.isEmpty()) {
                    componentList.add(comp);
                }
            }
            return componentList.toArray(new String[0]);
        });
    }

    /**
     * Check if path components match a rule
     * Supports multiple wildcards per component
     * Examples:
     * - {@code "/config/b*"} matches {@code "/config/bar"}
     * - {@code "/config/*"} matches {@code "/config/anything"}
     * - {@code "/config/*//*/*.txt"} matches {@code "/config/bar/fool/config.txt"}
     * - {@code "/foo/**"} matches any file under {@code "/foo"}
     */
    private boolean matchesRule(String[] pathComponents, ParsedRule rule) {
        String[] ruleComponents = rule.components;
        
        // Handle recursive rules (**) 
        if (rule.isRecursive) {
            // Rule must be a prefix of the path
            if (pathComponents.length < ruleComponents.length) {
                return false;
            }
            
            // Check if rule components match the beginning of path
            for (int i = 0; i < ruleComponents.length; i++) {
                if (!matchComponent(pathComponents[i], ruleComponents[i])) {
                    return false;
                }
            }
            return true; // Matches - rule is a prefix and /** allows any subdirectories
        }
        
        // Non-recursive: exact component count match required
        if (pathComponents.length != ruleComponents.length) {
            return false;
        }
        
        // Match each component
        for (int i = 0; i < ruleComponents.length; i++) {
            if (!matchComponent(pathComponents[i], ruleComponents[i])) {
                return false;
            }
        }
        
        return true;
    }
    
    /**
     * Match a single path component against a rule component
     * Supports multiple wildcards in a component
     * Examples:
     * - "config*" matches "config.txt", "config-mod.json"
     * - "b*r" matches "bar", "beer"
     * - "*" matches anything
     * - "a*b*c" matches "abc", "aXbYc"
     */
    private boolean matchComponent(String pathComponent, String ruleComponent) {
        // Exact match
        if (pathComponent.equals(ruleComponent)) {
            return true;
        }
        
        // No wildcard - must be exact match
        if (!ruleComponent.contains("*")) {
            return false;
        }
        
        // Single wildcard that matches everything
        if (ruleComponent.equals("*")) {
            return true;
        }
        
        // Multiple wildcards - split and match segments
        String[] segments = ruleComponent.split("\\*", -1);
        
        int pos = 0;
        for (int i = 0; i < segments.length; i++) {
            String segment = segments[i];
            
            if (i == 0) {
                // First segment - must match at start
                if (!pathComponent.startsWith(segment)) {
                    return false;
                }
                pos = segment.length();
            } else if (i == segments.length - 1) {
                // Last segment - must match at end
                if (!pathComponent.endsWith(segment)) {
                    return false;
                }
                // Check that we haven't already passed this position
                if (pos > pathComponent.length() - segment.length()) {
                    return false;
                }
            } else {
                // Middle segment - must appear after current position
                int index = pathComponent.indexOf(segment, pos);
                if (index == -1) {
                    return false;
                }
                pos = index + segment.length();
            }
        }
        
        return true;
    }
}
