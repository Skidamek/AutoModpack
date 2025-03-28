package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.config.*;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.FileInspection;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class FullServerPackContent {

    public final Set<Jsons.FullServerPackContentFields.FullServerPackContentItem> list = Collections.synchronizedSet(new HashSet<>());
    private final String MODPACK_NAME;
    private final Path MODPACK_DIR;
    private final ThreadPoolExecutor CREATION_EXECUTOR;
    private final Map<String, String> sha1MurmurMapPreviousContent = new HashMap<>();

    public FullServerPackContent(String modpackName, Path modpackDir, ThreadPoolExecutor executor) {
        this.MODPACK_NAME = modpackName;
        this.MODPACK_DIR = modpackDir;
        this.CREATION_EXECUTOR = executor;
    }

    public boolean create() {
        try {
            LOGGER.info("Creating Full Server Pack for: {}", MODPACK_NAME);

            Path serverConfigFile = CustomFileUtils.getPathFromCWD("automodpack/automodpack-server.json");
            if (!Files.exists(serverConfigFile)) {
                LOGGER.warn("Serverconfigfile is missing, did you delete it?: {}", serverConfigFile);
                return false;
            }

            Jsons.ServerConfigFields serverConfig = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFields.class);
            if (serverConfig == null || !serverConfig.enableFullServerPack) {
                LOGGER.info("FullServerPack creation is disabled or config invalid.");
                return false;
            }

            List<Path> filesToInclude = new ArrayList<>();

            //check synced files but not excluded, because we will get all files
            List<String> syncedFilePaths = serverConfig.syncedFiles != null ? serverConfig.syncedFiles : new ArrayList<>();
            for (String relativePath : syncedFilePaths) {
                Path path = CustomFileUtils.getPathFromCWD(relativePath);
                if (!Files.exists(path) || Files.isDirectory(path)) continue;

                String formatted = "/" + relativePath.replace("\\", "/");
                LOGGER.info("included from syncedFiles: {}", formatted);
                filesToInclude.add(path);
            }

            //adding default folders
            List<Path> includeDefaultDirs = List.of(
                    CustomFileUtils.getPathFromCWD("mods"),
                    CustomFileUtils.getPathFromCWD("config"),
                    CustomFileUtils.getPathFromCWD("resourcepacks"),
                    CustomFileUtils.getPathFromCWD("shaderpacks")
                    //add more folders like KubeJS? or something whats needed, what not to be generated automaticly?
            );

            for (Path dir : includeDefaultDirs) {
                if (!Files.exists(dir)) continue;

                try (Stream<Path> files = Files.walk(dir)) {
                    files.filter(Files::isRegularFile).forEach(path -> {
                        if (filesToInclude.contains(path)) return;

                        String relative = CustomFileUtils.getPathFromCWD("").relativize(path).toString().replace("\\", "/");
                        String formatted = "/" + relative;

                        LOGGER.info("included from default folders: {}", formatted);
                        filesToInclude.add(path);
                    });
                } catch (Exception e) {
                    LOGGER.error("Error while looking in folders: {}", dir, e);
                }
            }
            // automodpack-host folders and files adding
            Path automodpackHostFolder = CustomFileUtils.getPathFromCWD("automodpack/automodpack-host");
            if (Files.exists(automodpackHostFolder) && Files.isDirectory(automodpackHostFolder)) {
                try (Stream<Path> files = Files.walk(automodpackHostFolder)) {
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
                    LOGGER.error("Error while looking in the automodpack-host folder: {}", automodpackHostFolder, e);
                }
            }

            // add server config itself
            LOGGER.info("automodpack server config import: {}", serverConfigFile);
            filesToInclude.add(serverConfigFile);

            // exclude files from loadFullServerPackExclude config defined
            Set<String> excludedFullPackFiles = ConfigTools.loadFullServerPackExclude(serverConfigFile);

            filesToInclude.removeIf(path -> {
                String formatted = "/" + CustomFileUtils.getPathFromCWD("").relativize(path).toString().replace("\\", "/");
                boolean isExcluded = excludedFullPackFiles.stream().anyMatch(rule -> {
                    if (rule.startsWith("!")) rule = rule.substring(1);
                    return rule.equalsIgnoreCase(formatted);
                });

                if (isExcluded) {
                    LOGGER.info("excluded files from config defined: {}", formatted);
                }
                return isExcluded;
            });

            // generate content and save it
            Jsons.FullServerPackContentFields fullServerContent = buildFullServerPackContent(filesToInclude);

            Path outputPath = MODPACK_DIR.resolve("fullserverpack-content.json");
            ConfigTools.saveFullServerPackContent(outputPath, fullServerContent);

            LOGGER.info("FullServerPack content saved to: {}", outputPath);
            return true;

        } catch (Exception e) {
            LOGGER.error("Error during FullServerPack creation", e);
            return false;
        }
    }

    private List<Path> collectFiles(Jsons.ServerConfigFields serverConfig) {
        List<Path> filesToInclude = new ArrayList<>();

        return filesToInclude;
    }

    public Jsons.FullServerPackContentFields buildFullServerPackContent(List<Path> files) {
        Set<Jsons.FullServerPackContentFields.FullServerPackContentItem> contentList =
                Collections.synchronizedSet(new HashSet<>());

        //ADDED ATOMICInteger to read, how much files are processed
        AtomicInteger processed = new AtomicInteger();

        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (int i = 0; i < files.size(); i += 6) {
            List<Path> subList = files.subList(i, Math.min(files.size(), i + 6));
            futures.add(CompletableFuture.runAsync(() -> {
                for (Path file : subList) {
                    generate(file, contentList);
                    int done = processed.incrementAndGet();
                    LOGGER.info("Files processed {} / {}", done, files.size());
                }
            }, CREATION_EXECUTOR)); // use class defined executer
        }

        futures.forEach(CompletableFuture::join);

        return new Jsons.FullServerPackContentFields(contentList);
    }

    private void generate(Path file, Set<Jsons.FullServerPackContentFields.FullServerPackContentItem> contentList) {
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

    // if creation the full pack work, generate from server config is not needed anymore
    public static void generateFromServerConfig() {
        try {
            LOGGER.info("start generating server pack content file");
            Path automodpackserverConfig = CustomFileUtils.getPathFromCWD("automodpack/automodpack-server.json");

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

                        LOGGER.info("included from defaultdir: {}", formatted);
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
            Path automodpackHostDir = CustomFileUtils.getPathFromCWD("automodpack/automodpack-host");
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
                FullServerPackContent fullServerPack = new FullServerPackContent("FullServer", CustomFileUtils.getPathFromCWD("automodpack/automodpack-host"), (ThreadPoolExecutor) Executors.newFixedThreadPool(4));
                Jsons.FullServerPackContentFields fullServerContent = fullServerPack.buildFullServerPackContent(filesToInclude);

                Path outputPath = CustomFileUtils.getPathFromCWD("automodpack/automodpack-host/fullserverpack-content.json");
                ConfigTools.saveFullServerPackContent(outputPath, fullServerContent);

                LOGGER.info("servermodpack content file saved under: {}", outputPath);
            } catch (Exception e) {
            LOGGER.error("error on creation from fullserverpack-content.json", e);
            }
        } catch (Exception e) {
            LOGGER.error("ERROR ON CREATION?", e);
        }
    }

}
