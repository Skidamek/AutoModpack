package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.config.*;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class FullServerPackContent {

    public static Jsons.FullServerPackContentFields buildFullServerPackContent(List<Path> files) {
        Set<Jsons.FullServerPackContentFields.FullServerPackContentItem> contentList = Collections.synchronizedSet(new HashSet<>());
        ThreadPoolExecutor executor = (ThreadPoolExecutor) Executors.newFixedThreadPool(4);
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < files.size(); i += 6) {
            List<Path> subList = files.subList(i, Math.min(files.size(), i + 6));
            futures.add(CompletableFuture.runAsync(() -> subList.forEach(file -> generate(file, contentList)), executor));
        }

        futures.forEach(CompletableFuture::join);
        executor.shutdown();

        return new Jsons.FullServerPackContentFields(contentList);
    }

    private static void generate(Path file, Set<Jsons.FullServerPackContentFields.FullServerPackContentItem> contentList) {
        try {
            if (!Files.isRegularFile(file)) return;

            String formattedFile = CustomFileUtils.formatPath(file, CustomFileUtils.getPathFromCWD(""));
            if (formattedFile.startsWith("/automodpack/")) return;

            String size = String.valueOf(Files.size(file));

            //exclude invalid files
            if (size.equals("0") || file.getFileName().toString().startsWith(".") || formattedFile.endsWith(".tmp") || formattedFile.endsWith(".bak") || formattedFile.endsWith(".disabled")) {
                LOGGER.info("Skipping file {}, because they are invalid and not needed in fullserverModpack Content", formattedFile);
                return;
            }

            String type;
            if (FileInspection.isMod(file)) {
                type = "mod";
                if ((MOD_ID + "-bootstrap").equals(FileInspection.getModID(file))) {
                    return;
                }
            } else if (formattedFile.contains("/config/")) {
                type = "config";
            } else if (formattedFile.contains("/shaderpacks/")) {
                type = "shader";
            } else if (formattedFile.contains("/resourcepacks/")) {
                type = "resourcepack";
            } else if (formattedFile.endsWith("/options.txt")) {
                type = "mc_options";
            } else {
                type = "other";
            }

            String sha1 = CustomFileUtils.getHash(file);
            String murmur = null;
            if (type.equals("mod") || type.equals("shader") || type.equals("resourcepack")) {
                murmur = CustomFileUtils.getCurseforgeMurmurHash(file);
            }

            var item = new Jsons.FullServerPackContentFields.FullServerPackContentItem("/" + formattedFile, size, type, sha1, murmur);
            contentList.add(item);

        } catch (Exception e) {
            LOGGER.warn("Could not add file for FullServerPackContent: {}", file, e);
        }
    }
    public static void generateFromServerConfig() {
        LOGGER.info("start generating server pack content file");

        Path automodpackserverConfig = CustomFileUtils.getPathFromCWD("mods/automodpack/automodpack-server.json");

        //if file is deleted from user, stop
        if (!Files.exists(automodpackserverConfig)) {
            LOGGER.info("automodpack-server.json is missing? did you delete the file?");
            return;
        }

        //load config
        Jsons.ServerConfigFields serverConfig = ConfigTools.load(automodpackserverConfig, Jsons.ServerConfigFields.class);
        //if config null or false, stop
        if (serverConfig == null || !serverConfig.enableFullServerPack) {
            LOGGER.info("Fullserverpack creation on default disabled.");
            return;
        }
        Set<String> excludedFiles = ConfigTools.loadFullServerPackExclude(automodpackserverConfig);
        List<Path> filesToInclude = new ArrayList<>();

        //check synced files
        List<String> syncedFilePaths = serverConfig.syncedFiles != null ? serverConfig.syncedFiles : new ArrayList<>();

        for (String relativePath : syncedFilePaths) {
            Path path = CustomFileUtils.getPathFromCWD(relativePath);
            if (!Files.exists(path) || Files.isDirectory(path)) continue;
            String formatted = "/" + relativePath.replace("\\", "/");

            boolean isExcluded = excludedFiles.stream().anyMatch(rule -> {
                if (rule.startsWith("!")) rule = rule.substring(1);
                return rule.equalsIgnoreCase(formatted);
            });

            if (isExcluded) {
                LOGGER.info("excluded from syncedFiles: {}", formatted);
                continue;
            }

            LOGGER.info("included from syncedFiles: {}", formatted);
            filesToInclude.add(path);
        }

        // look for paths on default folders
        List<Path> includeDefaultDirs = List.of(
                CustomFileUtils.getPathFromCWD("mods"),
                CustomFileUtils.getPathFromCWD("config"),
                CustomFileUtils.getPathFromCWD("resourcepacks"),
                CustomFileUtils.getPathFromCWD("shaderpacks")
                //adding library or other folders like kubejs?
        );
        //Check default folders if not in synced files declared
        for (Path dir : includeDefaultDirs) {
            if (!Files.exists(dir)) continue;

            try (Stream<Path> files = Files.walk(dir)) {
                files.filter(Files::isRegularFile).forEach(path -> {
                    // check if already included
                    if (filesToInclude.contains(path)) return;

                    String relative = CustomFileUtils.getPathFromCWD("").relativize(path).toString().replace("\\", "/");
                    String formatted = "/" + relative;

                    boolean isExcluded = excludedFiles.stream().anyMatch(rule -> {
                        if (rule.startsWith("!")) rule = rule.substring(1);
                        return rule.equalsIgnoreCase(formatted);
                    });

                    if (isExcluded) {
                        LOGGER.info("excluded from defaultDir: {}", formatted);
                        return;
                    }
                    LOGGER.info("included from defaultDir: {}", formatted);
                    filesToInclude.add(path);
                });
            } catch (Exception e) {
                LOGGER.error("Error while walking through folder: {}", dir, e);
            }
        }
        // automodpack server config import
        if (Files.exists(automodpackserverConfig)) {
            LOGGER.info("automodpack server config import: {}", automodpackserverConfig);
            filesToInclude.add(automodpackserverConfig);
        }

        // adding client mod folders from automodpack host
        Path automodpackHostDir = CustomFileUtils.getPathFromCWD("mods/automodpack-host");
        if (Files.exists(automodpackHostDir) && Files.isDirectory(automodpackHostDir)) {
            try (Stream<Path> files = Files.walk(automodpackHostDir)) {
                files.filter(Files::isRegularFile).forEach(path -> {
                    String fileName = path.getFileName().toString().toLowerCase();
                    if (fileName.equals("fullserverpack-content.json") || fileName.equals("automodpack-content.json")) {
                        LOGGER.info("skipped content files from automodpack-host: {}", fileName);
                        return;
                    }

                    String relative = CustomFileUtils.getPathFromCWD("").relativize(path).toString().replace("\\", "/");
                    String formatted = "/" + relative;

                    LOGGER.info("included from automodpack-host: {}", formatted);
                    filesToInclude.add(path);
                });
            } catch (Exception e) {
                LOGGER.error("Error while walking through automodpack-host folder: {}", automodpackHostDir, e);
            }
        }

        try {
            Jsons.FullServerPackContentFields fullServerContent = FullServerPackContent.buildFullServerPackContent(filesToInclude);

            Path outputPath = CustomFileUtils.getPathFromCWD("mods/automodpack-host/fullserverpack-content.json");
            ConfigTools.saveFullServerPackContent(outputPath, fullServerContent);

            LOGGER.info("servermodpack content file saved under: {}", outputPath);
        } catch (Exception e) {
            LOGGER.error("error on creation from fullserverpack-content.json", e);
        }
    }

}
