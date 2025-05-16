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

    private final List<String> whiteListRules = new ArrayList<>();
    private final List<String> blackListRules = new ArrayList<>();

    public void separateRules(List<String> rules) {
        for (String rule : rules) {
            if (rule.startsWith("!")) {
                blackListRules.add(rule.substring(1));
            } else {
                whiteListRules.add(rule);
            }
        }
    }

    public WildCards(List<String> rules, Set<Path> startDirectories) {
        try {
            separateRules(rules);
            Map<String, List<String>> composedWhiteRules = composeRules(whiteListRules);
            Map<String, List<String>> composedBlackRules = composeRules(blackListRules);

            for (Path startDirectory : startDirectories) {
                try (Stream<Path> paths = Files.walk(startDirectory)) {
                    paths.forEach(node -> matchWhiteRules(node, startDirectory, composedWhiteRules));
                }

                matchBlackRules(startDirectory, composedBlackRules);
            }
        } catch (IOException e) {
            LOGGER.error("Failed to walk directories: {}", startDirectories, e);
        }
    }

    public void matchWhiteRules(Path node, Path startDirectory, Map<String, List<String>> composedWhiteRules) {
        if (!node.toFile().isFile()) {
            return;
        }

        String formattedPath = matchesRules(node, startDirectory, composedWhiteRules);
        if (formattedPath != null) {
            wildcardMatches.put(formattedPath, node);
        }
    }

    public void matchBlackRules(Path startDirectory, Map<String, List<String>> composedBlackRules) {
        Set<String> pathsToRemove = new HashSet<>();

        for (Path node : getWildcardMatches().values()) {
            if (!node.toFile().isFile()) {
                continue;
            }

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
        final String formattedPath = formatPath(node, startDirectory);

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
                if (wildcardMatch(directoryPart, ruleDirectory)) {
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

                        if (wildcardMatch(fileNamePart, rulePath)) {
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

    private boolean wildcardMatch(String target, String rule) {
        if (target.equals(rule)) {
            return true;
        }

        // Only one * in the rule path is allowed
        if (rule.indexOf("*") != rule.lastIndexOf("*")) {
            LOGGER.error("Only one * in the rule path is allowed: {}", rule);
            return false;
        }

        int targetLayers = target.split("/").length;
        int ruleLayers = rule.split("/").length;

        if (targetLayers != ruleLayers) {
            return false;
        }

        String[] ruleParts = rule.split("\\*");
        String partOne;
        String partTwo = "";
        if (ruleParts.length == 1) {
            partOne = ruleParts[0];
        } else if (ruleParts.length == 2) {
            partOne = ruleParts[0];
            partTwo = ruleParts[1];
        } else {
            LOGGER.error("Invalid rule path: {}", rule);
            return false;
        }

        return target.startsWith(partOne) && target.endsWith(partTwo);
    }

    public Map<String, List<String>> composeRules(List<String> rules) {
        Map<String, List<String>> directoryRulePathsMap = new HashMap<>(rules.size());

        for (String rule : rules) {
            int lastSlashIndex = rule.lastIndexOf("/");
            if (lastSlashIndex == -1) {
                continue;
            }

            String directoryPart = rule.substring(0, lastSlashIndex);
            String rulePath = rule.substring(lastSlashIndex);

            directoryRulePathsMap.computeIfAbsent(directoryPart, k -> new ArrayList<>()).add(rulePath);
        }

        return directoryRulePathsMap;
    }
}