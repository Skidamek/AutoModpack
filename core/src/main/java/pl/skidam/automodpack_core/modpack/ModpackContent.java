package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.LoaderService;
import pl.skidam.automodpack_core.utils.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class ModpackContent {
    public final List<Jsons.ModpackContentFields.ModpackContentItem> list = Collections.synchronizedList(new ArrayList<>());
    public final ObservableMap<String, Path> pathsMap = new ObservableMap<>();
    private final List<CompletableFuture<Void>> creationFutures = Collections.synchronizedList(new ArrayList<>());
    private final String MODPACK_NAME;
    private final WildCards SYNCED_FILES_CARDS;
    private final WildCards EDITABLE_CARDS;
    private final Path CWD;
    private final Path MODPACK_DIR;
    private final ThreadPoolExecutor CREATION_EXECUTOR;

    public ModpackContent(String modpackName, Path cwd, Path modpackDir, List<String> syncedFiles, List<String> allowEditsInFiles, ThreadPoolExecutor CREATION_EXECUTOR) {
        this.MODPACK_NAME = modpackName;
        this.CWD = cwd;
        this.MODPACK_DIR = modpackDir;
        List<Path> directoriesToSearch = new ArrayList<>(2);
        if (CWD != null) directoriesToSearch.add(CWD);
        if (MODPACK_DIR != null) directoriesToSearch.add(MODPACK_DIR);
        this.SYNCED_FILES_CARDS = new WildCards(syncedFiles, directoriesToSearch);
        this.EDITABLE_CARDS = new WildCards(allowEditsInFiles, directoriesToSearch);
        this.CREATION_EXECUTOR = CREATION_EXECUTOR;
    }

    public String getModpackName() {
        return MODPACK_NAME;
    }

    public boolean create() {
        try {
            pathsMap.clear();

            // host-modpack generation
            if (MODPACK_DIR != null) {
                LOGGER.info("Syncing {}...", MODPACK_DIR.getFileName());
                Files.list(MODPACK_DIR).forEach(path ->  creationFutures.add(generateAsync(path)));

                // Wait till finish
                creationFutures.forEach((CompletableFuture::join));
                creationFutures.clear();
            }

            // synced files generation
            SYNCED_FILES_CARDS.getWildcardMatches().values().forEach(path -> creationFutures.add(generateAsync(path)));

            // Wait till finish
            creationFutures.forEach((CompletableFuture::join));
            creationFutures.clear();

            if (list.isEmpty()) {
                LOGGER.warn("Modpack is empty!");
                return false;
            }
        } catch (Exception e) {
            LOGGER.error("Error while generating modpack!", e);
            return false;
        }

        saveModpackContent();
        if (httpServer != null) {
            httpServer.addPaths(pathsMap);
        }

        return true;
    }

    public boolean loadPreviousContent() {
        var optionalModpackContentFile = ModpackContentTools.getModpackContentFile(MODPACK_DIR);

        if (optionalModpackContentFile.isEmpty()) return false;

        Jsons.ModpackContentFields modpackContent = ConfigTools.loadModpackContent(optionalModpackContentFile.get());

        if (modpackContent == null) return false;

        synchronized (list) {
            list.addAll(modpackContent.list);

            for (Jsons.ModpackContentFields.ModpackContentItem modpackContentItem : list) {
                Path file = Path.of(MODPACK_DIR + modpackContentItem.file);
                if (!Files.exists(file)) file = Path.of(CWD + modpackContentItem.file);
                if (!Files.exists(file)) {
                    LOGGER.warn("File {} does not exist!", file);
                    continue;
                }

                pathsMap.put(modpackContentItem.sha1, file);
            }
        }

        if (httpServer != null) {
            httpServer.addPaths(pathsMap);
        }

        return true;
    }

    // This is important to make it synchronized otherwise it could corrupt the file and crash
    public synchronized void saveModpackContent() {
        synchronized (list) {
            Jsons.ModpackContentFields modpackContent = new Jsons.ModpackContentFields(null, list);

            modpackContent.automodpackVersion = AM_VERSION;
            modpackContent.mcVersion = MC_VERSION;
            modpackContent.loaderVersion = LOADER_VERSION;
            modpackContent.loader = LOADER;
            modpackContent.modpackName = MODPACK_NAME;

            ConfigTools.saveModpackContent(hostModpackContentFile, modpackContent);
        }
    }

    private CompletableFuture<Void> generateAsync(Path file) {
        return CompletableFuture.runAsync(() -> generate( file), CREATION_EXECUTOR);
    }

    private void generate(Path file) {
        try {
            Jsons.ModpackContentFields.ModpackContentItem item = generateContent(file);
            if (item != null && !list.contains(item)) {
                LOGGER.info("generated content for {}", item.file);
                list.add(item);
            }
        } catch (Exception e) {
            LOGGER.error("Error while generating content for: " + file + " generated from: " + MODPACK_DIR, e);
        }
    }

    public CompletableFuture<Void> replaceAsync(Path file) {
        return CompletableFuture.runAsync(() -> replace(file), CREATION_EXECUTOR);
    }

    public void replace(Path file) {
        remove(file);
        generate(file);
    }

    public void remove(Path file) {

        String modpackFile = CustomFileUtils.formatPath(file, MODPACK_DIR);

        synchronized (list) {
            for (Jsons.ModpackContentFields.ModpackContentItem item : this.list) {
                if (item.file.equals(modpackFile)) {
                    this.pathsMap.remove(item.sha1);
                    this.list.remove(item);
                    LOGGER.info("Removed content for {}", modpackFile);
                    break;
                }
            }
        }
    }

    private Jsons.ModpackContentFields.ModpackContentItem generateContent(final Path file) throws Exception {
        Path absoluteModpackDir = MODPACK_DIR;
        if (MODPACK_DIR != null) {
            absoluteModpackDir = MODPACK_DIR.toAbsolutePath().normalize();
        }

        if (Files.isDirectory(file)) {
            if (file.getFileName().toString().startsWith(".")) {
                LOGGER.info("Skipping " + file.getFileName() + " because it starts with a dot");
                return null;
            }

            List<Path> childFiles = Files.list(file).toList();

            for (Path childFile : childFiles) {
                var generated = generateContent(childFile);
                if (generated != null && !list.contains(generated)) {
                    list.add(generated);
                }
            }
        } else if (Files.isRegularFile(file)) {
            String modpackFile = CustomFileUtils.formatPath(file, MODPACK_DIR);

            boolean isEditable = false;

            final String size = String.valueOf(Files.size(file));

            if (size.equals("0")) {
                LOGGER.info("Skipping file {} because it is empty", modpackFile);
                return null;
            }

            // modpackFile is relative path to ~/.minecraft/ (content format) so if it starts with /automodpack/ something is wrong
            if (modpackFile.startsWith("/automodpack/")) {
                return null;
            }

            if (!hostContentModpackDir.equals(absoluteModpackDir)) {
                if (file.toString().startsWith(".")) {
                    LOGGER.info("Skipping file {} is hidden", modpackFile);
                    return null;
                }

                if (modpackFile.endsWith(".tmp")) {
                    LOGGER.info("File {} is temporary! Skipping...", modpackFile);
                    return null;
                }

                if (modpackFile.endsWith(".disabled")) {
                    LOGGER.info("File {} is disabled! Skipping...", modpackFile);
                    return null;
                }

                if (modpackFile.endsWith(".bak")) {
                    LOGGER.info("File {} is backup file, unnecessary on client! Skipping...", modpackFile);
                    return null;
                }
            }

            String type;

            if (FileInspection.isMod(file)) {
                type = "mod";
                if (serverConfig.autoExcludeServerSideMods && isServerMod(LOADER_MANAGER.getModList(), file)) {
                    LOGGER.info("File {} is server mod! Skipping...", modpackFile);
                    return null;
                }
            } else if (modpackFile.contains("/config/")) {
                type = "config";
            } else if (modpackFile.contains("/shaderpacks/")) {
                type = "shader";
            } else if (modpackFile.contains("/resourcepacks/")) {
                type = "resourcepack";
            } else if (modpackFile.endsWith("/options.txt")) {
                type = "mc_options";
            } else {
                type = "other";
            }

            // Exclude automodpack mod
            if (type.equals("mod") && (MOD_ID + "-bootstrap").equals(FileInspection.getModID(file))) {
                return null;
            }

            String sha1 = CustomFileUtils.getHash(file, "SHA-1").orElseThrow();

            // For CF API
            String murmur = null;
            if (type.equals("mod") || type.equals("shader") || type.equals("resourcepack")) {
                murmur = CustomFileUtils.getHash(file, "murmur").orElseThrow();
            }

            if (EDITABLE_CARDS.fileMatches(modpackFile, file)) {
                isEditable = true;
                LOGGER.info("File {} is editable!", modpackFile);
            }

            pathsMap.put(sha1, file);

            return new Jsons.ModpackContentFields.ModpackContentItem(modpackFile, size, type, isEditable, sha1, murmur);
        }

        return null;
    }

    private boolean isServerMod(Collection<LoaderService.Mod> modList, Path path) {
        if (modList == null) {
            return Objects.equals(FileInspection.getModEnvironment(path), LoaderService.EnvironmentType.SERVER);
        }

        for (var mod : modList) {
            if (!mod.modPath().toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize())) {
                continue;
            }

            if (mod.environmentType() == LoaderService.EnvironmentType.SERVER) {
                return true;
            }
        }

        return false;
    }
}