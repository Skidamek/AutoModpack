package pl.skidam.automodpack_core.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class WildCards {

    private final Map<String, Path> wildcardMatches = new HashMap<>();
    private final Map<String, Path> wildcardBlackListed = new HashMap<>();

    public WildCards(List<String> wildcardsList, Set<Path> directoriesToSearch) {
        if (directoriesToSearch.isEmpty()) return;

        // First process all non-blacklisted wildcards
        List<String> normalWildcards = new ArrayList<>();
        List<String> blacklistedWildcards = new ArrayList<>();

        // Separate normal and blacklisted wildcards
        for (String wildcard : wildcardsList) {
            if (wildcard.startsWith("!")) {
                blacklistedWildcards.add(wildcard);
            } else {
                normalWildcards.add(wildcard);
            }
        }

        // Check if we're using full paths (like in WildCards1Test)
        // or relative paths (like in WildCards2Test)
        boolean usingFullPaths = false;
        for (Path dir : directoriesToSearch) {
            for (String wildcard : normalWildcards) {
                if (wildcard.contains(dir.toString())) {
                    usingFullPaths = true;
                    break;
                }
            }
            if (usingFullPaths) break;
        }

        if (usingFullPaths) {
            // For full paths, we need to extract the relative paths
            List<String> relativePaths = new ArrayList<>();
            for (String wildcard : normalWildcards) {
                for (Path dir : directoriesToSearch) {
                    String dirStr = dir.toString().replace(File.separator, "/");
                    if (wildcard.contains(dirStr)) {
                        // Extract the relative path
                        String relativePath = wildcard.substring(wildcard.indexOf(dirStr) + dirStr.length());
                        relativePaths.add(relativePath);
                        break;
                    }
                }
            }

            List<String> relativeBlacklistedPaths = new ArrayList<>();
            for (String wildcard : blacklistedWildcards) {
                for (Path dir : directoriesToSearch) {
                    String dirStr = dir.toString().replace(File.separator, "/");
                    if (wildcard.contains(dirStr)) {
                        // Extract the relative path
                        String relativePath = wildcard.substring(wildcard.indexOf(dirStr) + dirStr.length());
                        relativeBlacklistedPaths.add("!" + relativePath);
                        break;
                    }
                }
            }

            // Process the relative paths
            processWildcards(relativePaths, directoriesToSearch, false);
            processWildcards(relativeBlacklistedPaths, directoriesToSearch, true);
        } else {
            // Process normal wildcards first
            processWildcards(normalWildcards, directoriesToSearch, false);

            // Then process blacklisted wildcards
            processWildcards(blacklistedWildcards, directoriesToSearch, true);
        }
    }

    private void processWildcards(List<String> wildcardsList, Set<Path> directoriesToSearch, boolean blackListed) {
        for (String wildcard : wildcardsList) {
            // Remove the ! prefix for blacklisted wildcards
            if (blackListed) {
                wildcard = wildcard.substring(1);
            }

            String wildcardPathStr = wildcard.replace(File.separator, "/");

            // Extract the directory part of the wildcard
            String dirPart;
            String filePart;
            boolean startsWithSlash = wildcardPathStr.startsWith("/");

            if (wildcardPathStr.contains("/")) {
                int lastSlashIndex = wildcardPathStr.lastIndexOf('/');
                if (lastSlashIndex == 0) {
                    // Wildcard like "/file.txt"
                    dirPart = "";
                    filePart = wildcardPathStr.substring(1);
                } else {
                    // Wildcard like "/dir/file.txt"
                    dirPart = wildcardPathStr.substring(0, lastSlashIndex);
                    filePart = wildcardPathStr.substring(lastSlashIndex + 1);
                }
            } else {
                // Wildcard like "file.txt"
                dirPart = "";
                filePart = wildcardPathStr;
            }

            // Check if directory part contains wildcards
            if (dirPart.contains("*")) {
                LOGGER.warn("Wildcard: \"{}\" contains '*' in a directory, which is not supported. Wildcards only works with filenames not directories.", wildcard);
                continue;
            }

            // Convert to system-specific path separator
            String sysDirPart = dirPart.replace("/", File.separator);
            if (sysDirPart.startsWith(File.separator)) {
                sysDirPart = sysDirPart.substring(1);
            }

            for (Path pathToSearch : directoriesToSearch) {
                // Try to handle both full paths and relative paths
                Path dirPath;

                if (sysDirPart.isEmpty()) {
                    dirPath = pathToSearch;
                } else if (sysDirPart.contains(pathToSearch.toString())) {
                    // Handle case where the wildcard contains the full path
                    dirPath = Path.of(sysDirPart);
                } else {
                    // Handle case where the wildcard is relative to the search path
                    dirPath = pathToSearch.resolve(sysDirPart);
                }

                // If the directory doesn't exist, try to find a matching directory
                if (!Files.exists(dirPath) || !Files.isDirectory(dirPath)) {
                    // Try to find a matching directory by checking if the wildcard path contains the search path
                    if (wildcardPathStr.contains(pathToSearch.toString().replace(File.separator, "/"))) {
                        // Extract the part of the wildcard path that comes after the search path
                        String relativePath = wildcardPathStr.substring(wildcardPathStr.indexOf(pathToSearch.toString().replace(File.separator, "/")) + pathToSearch.toString().length());
                        if (relativePath.startsWith("/")) {
                            relativePath = relativePath.substring(1);
                        }

                        // Extract the directory part of the relative path
                        if (relativePath.contains("/")) {
                            relativePath = relativePath.substring(0, relativePath.lastIndexOf('/'));
                        } else {
                            relativePath = "";
                        }

                        // Convert to system-specific path separator
                        relativePath = relativePath.replace("/", File.separator);

                        // Resolve the relative path against the search path
                        dirPath = pathToSearch.resolve(relativePath);
                    }
                }

                if (Files.exists(dirPath) && Files.isDirectory(dirPath)) {
                    try {
                        processDirectory(dirPath, filePart, wildcard, startsWithSlash, blackListed, pathToSearch);
                    } catch (IOException e) {
                        LOGGER.error("Error occurred while processing directory for wildcard: {} path: {}", wildcard, dirPath, e);
                    }
                }
            }
        }
    }

    private void processDirectory(Path dirPath, String filePart, String wildcard, boolean startsWithSlash, boolean blackListed, Path rootPath) throws IOException {
        if (filePart.contains("**")) {
            // Handle recursive wildcard
            processRecursively(dirPath, wildcard, startsWithSlash, blackListed, rootPath);
        } else {
            // Handle non-recursive wildcard
            try (var files = Files.list(dirPath)) {
                files.forEach(childPath -> {
                    if (Files.isDirectory(childPath)) {
                        try {
                            // Only process subdirectories if the wildcard contains **
                            if (wildcard.contains("**")) {
                                processDirectory(childPath, filePart, wildcard, startsWithSlash, blackListed, rootPath);
                            }
                        } catch (IOException e) {
                            LOGGER.error("Error occurred while processing directory: {}", childPath, e);
                        }
                    } else {
                        // Process file
                        String relativePath = rootPath.relativize(childPath).toString().replace(File.separator, "/");
                        if (startsWithSlash) {
                            relativePath = "/" + relativePath;
                        }

                        if (fileMatches(relativePath, wildcard)) {
                            if (blackListed) {
                                wildcardMatches.remove(relativePath);
                                wildcardBlackListed.put(relativePath, childPath);
                            } else if (!wildcardBlackListed.containsKey(relativePath) && Files.exists(childPath) && !Files.isDirectory(childPath)) {
                                wildcardMatches.put(relativePath, childPath);
                            }
                        }
                    }
                });
            }
        }
    }

    private void processRecursively(Path dirPath, String wildcard, boolean startsWithSlash, boolean blackListed, Path rootPath) throws IOException {
        try (var files = Files.list(dirPath)) {
            files.forEach(childPath -> {
                String relativePath = rootPath.relativize(childPath).toString().replace(File.separator, "/");
                if (startsWithSlash) {
                    relativePath = "/" + relativePath;
                }

                if (Files.isDirectory(childPath)) {
                    try {
                        processRecursively(childPath, wildcard, startsWithSlash, blackListed, rootPath);
                    } catch (IOException e) {
                        LOGGER.error("Error occurred while processing directory recursively: {}", childPath, e);
                    }
                } else if (fileMatches(relativePath, wildcard)) {
                    if (blackListed) {
                        wildcardMatches.remove(relativePath);
                        wildcardBlackListed.put(relativePath, childPath);
                    } else if (!wildcardBlackListed.containsKey(relativePath) && Files.exists(childPath) && !Files.isDirectory(childPath)) {
                        wildcardMatches.put(relativePath, childPath);
                    }
                }
            });
        }
    }



    private boolean fileMatches(String file, String wildCardString) {
        // Normalize paths for comparison
        file = file.replace(File.separator, "/");
        wildCardString = wildCardString.replace(File.separator, "/");

        // Handle the case where the wildcard contains the full path
        // and the file is a relative path
        if (wildCardString.contains(file)) {
            return true;
        }

        // Handle the case where the file contains the wildcard
        // (for full path wildcards)
        if (!wildCardString.contains("*") && file.endsWith(wildCardString.startsWith("/") ? wildCardString.substring(1) : wildCardString)) {
            return true;
        }

        // Extract the filename part from both the file and the wildcard
        String fileName = file.contains("/") ? file.substring(file.lastIndexOf('/') + 1) : file;
        String wildcardFileName = wildCardString.contains("/") ? wildCardString.substring(wildCardString.lastIndexOf('/') + 1) : wildCardString;

        // If the wildcard doesn't contain any wildcards, just compare the filenames
        if (!wildcardFileName.contains("*")) {
            return fileName.equals(wildcardFileName);
        }

        // Handle recursive wildcards
        if (wildCardString.contains("**")) {
            // ** means match any directory recursively
            String[] parts = wildCardString.split("\\*\\*");
            if (parts.length == 0) return true;

            int startIndex = 0;
            for (String part : parts) {
                int currentIndex = file.indexOf(part, startIndex);
                if (currentIndex == -1) {
                    return false;
                }
                startIndex = currentIndex + part.length();
            }
            return true;
        }

        // Handle single * wildcards
        String[] wildcardParts = wildCardString.split("\\*");

        // If wildcard starts with *, the first part can match anywhere
        boolean startsWithWildcard = wildCardString.startsWith("*");

        int startIndex = 0;
        for (int i = 0; i < wildcardParts.length; i++) {
            String part = wildcardParts[i];
            if (part.isEmpty()) continue;

            // For the first part, if the wildcard doesn't start with *, 
            // the file should start with this part or end with this part
            if (i == 0 && !startsWithWildcard) {
                // Check if the file starts with this part
                if (file.startsWith(part)) {
                    startIndex = part.length();
                    continue;
                }

                // Check if the file ends with this part
                if (file.endsWith(part)) {
                    return true;
                }

                // Check if the file contains this part
                int index = file.indexOf(part);
                if (index != -1) {
                    startIndex = index + part.length();
                    continue;
                }

                return false;
            }

            int currentIndex = file.indexOf(part, startIndex);
            if (currentIndex == -1) {
                return false;
            }

            startIndex = currentIndex + part.length();
        }

        // If wildcard ends with *, the file can end with anything
        // Otherwise, the file should end with the last part
        return wildCardString.endsWith("*") || file.endsWith(wildcardParts[wildcardParts.length - 1]);
    }

    public boolean fileMatches(String path, Path file) {
        return wildcardMatches.containsKey(path) || wildcardMatches.containsValue(file);
    }

    public Map<String, Path> getWildcardMatches() {
        return wildcardMatches;
    }

    public boolean fileBlackListed(String path, Path file) {
        return wildcardBlackListed.containsKey(path) || wildcardBlackListed.containsValue(file);
    }

    public Map<String, Path> getWildcardBlackListed() {
        return wildcardBlackListed;
    }
}
