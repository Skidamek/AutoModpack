package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.config.*;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class FullServerPackContent {

    public static void generate() {

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

        try {
            Jsons.FullServerPackContentFields fullServerContent = ModpackContentTools.buildFullServerPackContent(filesToInclude);

            Path outputPath = CustomFileUtils.getPathFromCWD("mods/automodpack-host/fullserverpack-content.json");
            ConfigTools.saveFullServerPackContent(outputPath, fullServerContent);

            LOGGER.info("servermodpack content file saved under: {}", outputPath);
        } catch (Exception e) {
            LOGGER.error("error on creation from fullserverpack-content.json", e);
        }
    }
}