package pl.skidam.automodpack_core.modpack;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadPoolExecutor;

import static pl.skidam.automodpack_core.GlobalVariables.*;
import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class ModpackContent {
    public final Set<Jsons.ModpackContentFields.ModpackContentItem> list = Collections.synchronizedSet(new HashSet<>());
    public final ObservableMap<String, Path> pathsMap = new ObservableMap<>();
    private final String MODPACK_NAME;
    private final WildCards SYNCED_FILES_CARDS;
    private final WildCards EDITABLE_CARDS;
    private final Path CWD;
    private final Path MODPACK_DIR;
    private final ThreadPoolExecutor CREATION_EXECUTOR;
    private final Map<String, String> sha1MurmurMapPreviousContent = new HashMap<>();

    public ModpackContent(String modpackName, Path cwd, Path modpackDir, List<String> syncedFiles, List<String> allowEditsInFiles, ThreadPoolExecutor CREATION_EXECUTOR) {
        this.MODPACK_NAME = modpackName;
        this.CWD = cwd;
        this.MODPACK_DIR = modpackDir;
        Set<Path> directoriesToSearch = new HashSet<>(2);
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
            sha1MurmurMapPreviousContent.clear();
            getPreviousContent().ifPresent(previousContent -> previousContent.list.forEach(item -> sha1MurmurMapPreviousContent.put(item.sha1, item.murmur)));

            List<CompletableFuture<Void>> creationFutures = Collections.synchronizedList(new ArrayList<>());

            // host-modpack generation
            if (MODPACK_DIR != null) {
                LOGGER.info("Syncing {}...", MODPACK_DIR.getFileName());
                creationFutures.addAll(generateAsync(Files.walk(MODPACK_DIR).toList()));

                // Wait till finish
                creationFutures.forEach((CompletableFuture::join));
                creationFutures.clear();
            }

            // synced files generation
            creationFutures.addAll(generateAsync(SYNCED_FILES_CARDS.getWildcardMatches().values().stream().toList()));

            // Wait till finish
            creationFutures.forEach((CompletableFuture::join));
            creationFutures.clear();

            if (list.isEmpty()) {
                LOGGER.warn("Modpack is empty!");
                return false;
            }

            // Remove duplicates
            Set<String> dupeSet = new HashSet<>();
            list.removeIf(item -> !dupeSet.add(item.file));

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

    public Optional<Jsons.ModpackContentFields> getPreviousContent() {
        var optionalModpackContentFile = ModpackContentTools.getModpackContentFile(MODPACK_DIR);
        return optionalModpackContentFile.map(ConfigTools::loadModpackContent);
    }


    public boolean loadPreviousContent() {
        var optionalModpackContent = getPreviousContent();
        if (optionalModpackContent.isEmpty()) return false;
        Jsons.ModpackContentFields modpackContent = optionalModpackContent.get();

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

        // set all new variables
        saveModpackContent();

        return true;
    }

    // This is important to make it synchronized otherwise it could corrupt the file and crash
    public synchronized void saveModpackContent() {
        synchronized (list) {
            Jsons.ModpackContentFields modpackContent = new Jsons.ModpackContentFields(list);

            modpackContent.automodpackVersion = AM_VERSION;
            modpackContent.mcVersion = MC_VERSION;
            modpackContent.loaderVersion = LOADER_VERSION;
            modpackContent.loader = LOADER;
            modpackContent.modpackName = MODPACK_NAME;

            ConfigTools.saveModpackContent(hostModpackContentFile, modpackContent);
        }
    }

    // For every 6 files we generate content in parallel
    private List<CompletableFuture<Void>> generateAsync(List<Path> files) {
        List<CompletableFuture<Void>> futures = new ArrayList<>();
        for (int i = 0; i < files.size(); i += 6) {
            List<Path> subList = files.subList(i, Math.min(files.size(), i + 6));
            futures.add(CompletableFuture.runAsync(() -> subList.forEach(this::generate), CREATION_EXECUTOR));
        }

        return futures;
    }

    private void generate(Path file) {
        try {
            Jsons.ModpackContentFields.ModpackContentItem item = generateContent(file);
            if (item != null) {
                LOGGER.info("generated content for {}", item.file);
                synchronized (list) {
                    list.add(item);
                }
                pathsMap.put(item.sha1, file);
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

    // check if file is hostModpackContentFile, serverConfigFile or serverCoreConfigFile
    private boolean isInnerFile(Path file) {
        Path normalizedFilePath = file.toAbsolutePath().normalize();
        return normalizedFilePath.equals(hostModpackContentFile.toAbsolutePath().normalize()) ||
                normalizedFilePath.equals(serverConfigFile.toAbsolutePath().normalize()) ||
                normalizedFilePath.equals(serverCoreConfigFile.toAbsolutePath().normalize());
    }

    private Jsons.ModpackContentFields.ModpackContentItem generateContent(final Path file) throws Exception {
        if (!Files.isRegularFile(file)) return null;

        if (serverConfig == null) {
            LOGGER.error("Server config is null!");
            return null;
        }

        if (isInnerFile(file)) {
            return null;
        }

        String formattedFile = CustomFileUtils.formatPath(file, MODPACK_DIR);

        // modpackFile is relative path to ~/.minecraft/ (content format) so if it starts with /automodpack/ we dont want it
        if (formattedFile.startsWith("/automodpack/")) {
            return null;
        }

        final String size = String.valueOf(Files.size(file));

        if (serverConfig.autoExcludeUnnecessaryFiles) {
            if (size.equals("0")) {
                LOGGER.info("Skipping file {} because it is empty", formattedFile);
                return null;
            }

            if (file.getFileName().toString().startsWith(".")) {
                LOGGER.info("Skipping file {} is hidden", formattedFile);
                return null;
            }

            if (formattedFile.endsWith(".tmp")) {
                LOGGER.info("File {} is temporary! Skipping...", formattedFile);
                return null;
            }

            if (formattedFile.endsWith(".disabled")) {
                LOGGER.info("File {} is disabled! Skipping...", formattedFile);
                return null;
            }

            if (formattedFile.endsWith(".bak")) {
                LOGGER.info("File {} is backup file, unnecessary on client! Skipping...", formattedFile);
                return null;
            }
        }

        String type;

        if (FileInspection.isMod(file)) {
            type = "mod";
            if (serverConfig.autoExcludeServerSideMods && isServerMod(LOADER_MANAGER.getModList(), file)) {
                LOGGER.info("File {} is server mod! Skipping...", formattedFile);
                return null;
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

        // Exclude automodpack mod
        if (type.equals("mod") && (MOD_ID + "-bootstrap").equals(FileInspection.getModID(file))) {
            return null;
        }

        String sha1 = CustomFileUtils.getHash(file, "SHA-1").orElseThrow();

        // For CF API
        String murmur = null;
        if (type.equals("mod") || type.equals("shader") || type.equals("resourcepack")) {
            // get murmur hash from previousContent.list of item with same sha1
            murmur = sha1MurmurMapPreviousContent.get(sha1);
            if (murmur == null) {
                murmur = CustomFileUtils.getHash(file, "murmur").orElseThrow();
            }
        }

        boolean isEditable = false;
        if (EDITABLE_CARDS.fileMatches(formattedFile, file)) {
            isEditable = true;
            LOGGER.info("File {} is editable!", formattedFile);
        }

        return new Jsons.ModpackContentFields.ModpackContentItem(formattedFile, size, type, isEditable, sha1, murmur);

    }

    private boolean isServerMod(Collection<LoaderManagerService.Mod> modList, Path path) {
        if (modList == null) {
            return Objects.equals(FileInspection.getModEnvironment(path), LoaderManagerService.EnvironmentType.SERVER);
        }

        for (var mod : modList) {
            if (!mod.modPath().toAbsolutePath().normalize().equals(path.toAbsolutePath().normalize())) {
                continue;
            }

            if (mod.environmentType() == LoaderManagerService.EnvironmentType.SERVER) {
                return true;
            }
        }

        return false;
    }
}