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

    public WildCards(List<String> wildcardsList, List<Path> directoriesToSearch) {

        wildcardsList = new ArrayList<>(wildcardsList);

        // sort the files, that files starting with `!` are last to then exclude them
        wildcardsList.sort(Comparator.comparing(s -> s.startsWith("!")));

        for (String wildcard : wildcardsList) {
            boolean blackListed = wildcard.startsWith("!");
            wildcard = blackListed ? wildcard.replaceFirst("!", "") : wildcard;
            String wildcardPathStr = wildcard.replace(File.separator, "/");

            // this isn't filesystem related yet, must be in modpack content format
            if (wildcard.contains("/")) {
                if (wildcard.lastIndexOf('/') == 0) {
                    wildcard = wildcard.replace("/", "");
                    wildcardPathStr = wildcard;
                } else {
                    wildcardPathStr = wildcard.substring(0, wildcard.lastIndexOf('/'));
                }
            }

            if (wildcardPathStr.contains("*")) {
                LOGGER.warn("Wildcard: \"{}\" contains '*' in a directory, which is not supported. Wildcards only works with filenames not directories.", wildcard);
                continue;
            }

            wildcardPathStr = wildcardPathStr.replace("/", File.separator);

            for (Path pathToSearch : directoriesToSearch) {
                Path wildcardPath = Path.of(wildcardPathStr);
                boolean startsWithSlash;

                if (!Files.exists(wildcardPath)) {
                    startsWithSlash = wildcardPathStr.charAt(0) == File.separatorChar;
                    wildcardPathStr = startsWithSlash ? wildcardPathStr.replaceFirst(Matcher.quoteReplacement(File.separator), "") : wildcardPathStr;
                    wildcardPath = Path.of(wildcardPathStr);
                } else {
                    startsWithSlash = false;
                }

                Path path;
                if (!wildcardPath.startsWith(pathToSearch)) {
                    path = pathToSearch.resolve(wildcardPath);
                } else {
                    path = wildcardPath;
                }

                System.out.println("Path: " + path + " wildcard: " + wildcard + " startsWithSlash: " + startsWithSlash + " blackListed: " + blackListed);

                if (Files.isDirectory(path)) {
                    try (var files = Files.list(path)) {
                        String finalWildcardPathStr = wildcardPathStr;
                        String finalWildcard = wildcard;
                        files.forEach(childPath -> processFile(childPath, finalWildcardPathStr, finalWildcard, startsWithSlash, blackListed));
                    } catch (IOException e) {
                        LOGGER.error("Error occurred while processing directory for wildcard: {} path: {}", wildcard, path, e);
                    }
                } else {
                    processFile(path, wildcardPathStr, wildcard, startsWithSlash, blackListed);
                }
            }
        }
    }


    private void processFile(Path file, String pathStr, String finalWildcard, boolean startsWithSlash, boolean blackListed) {
        int index = file.toString().replace(File.separator, "/").indexOf(pathStr);
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
        String formatedPath = path.toString().replace(File.separator, "/");
        String matchFileStr = startsWithSlash ? "/" + formatedPath : formatedPath;
        if (fileMatches(matchFileStr, finalWildcard)) {
            if (blackListed) {
                wildcardMatches.remove(formattedPath, path);
                wildcardBlackListed.put(formattedPath, path);
//                LOGGER.info("File {} is excluded! Skipping...", formattedPath);
                System.out.println("File " + formattedPath + " is excluded! Skipping...");
            } else if (!wildcardMatches.containsKey(formattedPath)) {
                wildcardMatches.put(formattedPath, path);
//                LOGGER.info("File {} matches!", formattedPath);
                System.out.println("File " + formattedPath + " matches!");
            }
        }
    }


    private boolean fileMatches(String file, String wildCardString) {
        if (!wildCardString.contains("*")) {
            System.out.println("* NOT found in wildcard: " + wildCardString + " file: " + file);
            return file.endsWith(wildCardString);
        } else {
            System.out.println("* found in wildcard: " + wildCardString + " file: " + file);
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
