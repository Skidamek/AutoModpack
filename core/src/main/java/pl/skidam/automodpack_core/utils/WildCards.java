package pl.skidam.automodpack_core.utils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class WildCards {

    private final Map<String, Path> wildcardMatches = new HashMap<>();
    private final Map<String, Path> wildcardBlackListed = new HashMap<>();

    public WildCards(List<String> wildcardsList) {

        wildcardsList = new ArrayList<>(wildcardsList);

        // sort the files, that files starting with `!` are last to then exclude them
        wildcardsList.sort(Comparator.comparing(s -> s.startsWith("!")));

        for (String wildcard : wildcardsList) {
            boolean blackListed = wildcard.startsWith("!");

            wildcard = blackListed ? wildcard.replaceFirst("!", "") : wildcard;

            String pathStr = wildcard;
            // this isn't filesystem related yet, must be in modpack content format
            if (wildcard.contains("/")) {
                if (wildcard.lastIndexOf('/') == 0) {
                    wildcard = wildcard.replace("/", "");
                    pathStr = wildcard;
                } else {
                    pathStr = wildcard.substring(0, wildcard.lastIndexOf('/'));
                }
            }

            String finalWildcard = wildcard;

            boolean startsWithSlash;

            if (pathStr.contains("*")) {
                LOGGER.warn("Wildcard: \"{}\" contains '*' in a directory, which is not supported. Wildcards only works with filenames not directories.", wildcard);
                continue;
            }

            pathStr = pathStr.replace("/", File.separator);
            Path path = Path.of(pathStr);

            if (!Files.exists(path)) {
                startsWithSlash = pathStr.charAt(0) == File.separatorChar;
                pathStr = startsWithSlash ? pathStr.replaceFirst(Matcher.quoteReplacement(File.separator), "") : pathStr;
                path = Path.of(pathStr);
            } else {
                startsWithSlash = false;
            }

            if (Files.isDirectory(path)) {
                try (var files = Files.list(path)) {
                    String finalPathStr = pathStr;
                    files.forEach(childPath -> processFile(childPath, finalPathStr, finalWildcard, startsWithSlash, blackListed));
                } catch (IOException e) {
                    LOGGER.error("Error occurred while processing directory for wildcard: {} path: {}", wildcard, path, e);
                }
            } else {
                processFile(path, pathStr, finalWildcard, startsWithSlash, blackListed);
            }
        }
    }


    private void processFile(Path file, String pathStr, String finalWildcard, boolean startsWithSlash, boolean blackListed) {
        int index = file.toString().indexOf(pathStr);
        if (index != -1) {
            pathStr = pathStr + file.toString().substring(index + pathStr.length());
            pathStr = pathStr.replace(File.separator, "/");
            pathStr = pathStr.startsWith("/") ? pathStr : "/" + pathStr;
            matchFile(file, pathStr, finalWildcard, startsWithSlash, blackListed);
        } else {
            throw new IllegalStateException("File " + file + " does not match the wildcard " + pathStr + " " + finalWildcard + " " + startsWithSlash + " " + blackListed);
        }
    }

    private void matchFile(Path path, String formattedPath, String finalWildcard, boolean startsWithSlash, boolean blackListed) {
        String matchFileStr = startsWithSlash ? "/" + path.toString().replace(File.separator, "/") : path.toString().replace(File.separator, "/");
        if (fileMatches(matchFileStr, finalWildcard)) {
            if (blackListed) {
                wildcardMatches.remove(formattedPath, path);
                wildcardBlackListed.put(formattedPath, path);
                LOGGER.info("File {} is excluded! Skipping...", formattedPath);
            } else if (!wildcardMatches.containsKey(formattedPath)) {
                wildcardMatches.put(formattedPath, path);
//                LOGGER.info("File {} matches!", formattedPath);
            }
        }
    }


    private boolean fileMatches(String file, String wildCardString) {
        if (!wildCardString.contains("*")) {
            return file.startsWith(wildCardString);
        }

        // Wild card magic
        String[] excludeFileParts = wildCardString.split("\\*");
        int startIndex = 0;
        for (String excludeFilePart : excludeFileParts) {
            int currentIndex = file.indexOf(excludeFilePart, startIndex);
            if (currentIndex == -1) {
                return false;
            }

            startIndex = currentIndex + excludeFilePart.length();
        }

        return true;
    }

    public boolean fileMatches(String path, Path file) {
        if (wildcardMatches.containsKey(path)) {
            return true;
        }
        if (wildcardMatches.containsValue(file)) {
            return true;
        }
        return false;
    }
    public Map<String, Path> getWildcardMatches() {
        return wildcardMatches;
    }

    public boolean fileBlackListed(String path, Path file) {
        if (wildcardBlackListed.containsKey(path)) {
            return true;
        }
        if (wildcardBlackListed.containsValue(file)) {
            return true;
        }
        return false;
    }

    public Map<String, Path> getWildcardBlackListed() {
        return wildcardBlackListed;
    }
}
