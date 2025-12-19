package pl.skidam.automodpack_core.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.loader.LoaderManagerService;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class FileInspection {

    private static final Gson GSON = new Gson();
    private static final String LOADER = GlobalVariables.LOADER;

    public static boolean isMod(Path file) {
        if (!file.getFileName().toString().endsWith(".jar") || !Files.exists(file)) {
            return false;
        }

        try (FileSystem fs = FileSystems.newFileSystem(file)) {
            return getModID(fs) != null || hasSpecificServices(fs);
        } catch (IOException e) {
            return false;
        }
    }

    public record Mod(String modID, String hash, Collection<String> providesIDs, String modVersion, Path modPath, LoaderManagerService.EnvironmentType environmentType, Collection<String> dependencies) {}
    public record HashPathPair(String hash, Path path) { }
    private static final Map<HashPathPair, Mod> modCache = new HashMap<>();

    public static Mod getMod(Path file) {
        if (!Files.isRegularFile(file)) return null;
        if (!file.getFileName().toString().endsWith(".jar")) return null;

        String hash = CustomFileUtils.getHash(file);
        if (hash == null) {
            LOGGER.error("Failed to get hash for file: {}", file);
            return null;
        }

        HashPathPair hashPathPair = new HashPathPair(hash, file);
        if (modCache.containsKey(hashPathPair)) {
            return modCache.get(hashPathPair);
        }

        for (Mod mod : GlobalVariables.LOADER_MANAGER.getModList()) {
            if (hash.equals(mod.hash)) {
                modCache.put(hashPathPair, mod);
                return mod;
            }
        }

        // Open FS once for all metadata extractions
        try (FileSystem fs = FileSystems.newFileSystem(file)) {
            String modId = (String) getModInfo(fs, "modId");

            if (modId != null) {
                String modVersion = (String) getModInfo(fs, "version");
                LoaderManagerService.EnvironmentType environmentType = (LoaderManagerService.EnvironmentType) getModInfo(fs, "environment");
                Set<String> dependencies = getModDependencies(fs);
                Set<String> providesIDs = getProvidedIDs(fs);

                if (modVersion != null && dependencies != null) {
                    var mod = new Mod(modId, hash, providesIDs, modVersion, file, environmentType, dependencies);
                    modCache.put(hashPathPair, mod);
                    return mod;
                }

                LOGGER.error("Not enough mod information for file: {} modId: {}, modVersion: {}, dependencies: {}", file, modId, modVersion, dependencies);
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to get mod info for file: {}", file);
        }

        return null;
    }

    private static final Set<String> services = Set.of(
            "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator",
            "META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator",
            "META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider",
            "META-INF/services/net.neoforged.neoforgespi.locating.IModLocator",
            "META-INF/services/net.neoforged.neoforgespi.locating.IDependencyLocator",
            "META-INF/services/net.neoforged.neoforgespi.locating.IModLanguageLoader",
            "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
            "META-INF/services/net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper"
    );

    // Checks for neo/forge mod locators
    public static boolean isModCompatible(Path file) {
        if (!file.getFileName().toString().endsWith(".jar") || !Files.exists(file)) {
            return false;
        }

        try (FileSystem fs = FileSystems.newFileSystem(file)) {
            String entryPathString = switch (LOADER) {
                case "neoforge" -> "META-INF/neoforge.mods.toml";
                case "fabric" -> "fabric.mod.json";
                case "forge" -> "META-INF/mods.toml";
                case "quilt" -> "quilt.mod.json";
                default -> null;
            };

            if (entryPathString != null && Files.exists(fs.getPath(entryPathString))) {
                return true;
            }

            if ("forge".equals(LOADER) || "neoforge".equals(LOADER)) {
                if (hasSpecificServices(fs)) {
                    return true;
                }
            }

        } catch (IOException e) {
            // Ignore
        }

        return false;
    }

    public static boolean hasSpecificServices(Path file) {
        if (!file.getFileName().toString().endsWith(".jar") || !Files.exists(file)) {
            return false;
        }

        try (FileSystem fs = FileSystems.newFileSystem(file)) {
            return hasSpecificServices(fs);
        } catch (IOException e) {
            LOGGER.error("Error reading file {}: {}", file, e.getMessage());
        }
        return false;
    }

    public static boolean hasSpecificServices(FileSystem fs) {
        // Direct Service Lookup (Fast)
        for (String service : services) {
            if (Files.exists(fs.getPath(service))) {
                return true;
            }
        }

        // Jar-in-Jar Scan (Slower)
        Path jarJarDir = fs.getPath("META-INF", "jarjar");
        if (!Files.exists(jarJarDir)) {
            return false;
        }

        try (Stream<Path> walk = Files.walk(jarJarDir, 1)) {
            for (Path nestedJarPath : walk.toList()) {
                // Skip non-jar entries
                if (nestedJarPath.equals(jarJarDir) || !nestedJarPath.toString().endsWith(".jar")) {
                    continue;
                }

                // Optimization: Use Files.newInputStream directly for nested zip entries
                try (InputStream inputStream = Files.newInputStream(nestedJarPath);
                     ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {

                    ZipEntry nestedEntry;
                    while ((nestedEntry = zipInputStream.getNextEntry()) != null) {
                        if (services.contains(nestedEntry.getName())) {
                            return true;
                        }
                    }
                } catch (IOException e) {
                    LOGGER.error("Error reading nested JAR in {}: {}", nestedJarPath, e.getMessage());
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error examining JarJar in {}", fs, e);
        }

        return false;
    }

    public static Path getMetadataPath(FileSystem fs) {
        String preferredEntry = switch (LOADER) {
            case "neoforge" -> "META-INF/neoforge.mods.toml";
            case "fabric" -> "fabric.mod.json";
            case "forge" -> "META-INF/mods.toml";
            case "quilt" -> "quilt.mod.json";
            default -> null;
        };

        if (preferredEntry != null) {
            Path path = fs.getPath(preferredEntry);
            if (Files.exists(path)) return path;
        }

        String[] fallbackEntries = {
                "META-INF/neoforge.mods.toml",
                "fabric.mod.json",
                "META-INF/mods.toml",
                "quilt.mod.json"
        };

        for (String entryName : fallbackEntries) {
            if (entryName.equals(preferredEntry)) continue;

            Path path = fs.getPath(entryName);
            if (Files.exists(path)) return path;
        }

        return null;
    }

    public static String getModVersion(Path file) {
        return (String) getModInfo(file, "version");
    }

    public static String getModID(Path file) {
        return (String) getModInfo(file, "modId");
    }

    public static LoaderManagerService.EnvironmentType getModEnvironment(Path file) {
        return (LoaderManagerService.EnvironmentType) getModInfo(file, "environment");
    }

    private static String getModID(FileSystem fs) {
        return (String) getModInfo(fs, "modId");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> getProvidedIDs(FileSystem fs) {
        return (Set<String>) getModInfo(fs, "provides");
    }

    @SuppressWarnings("unchecked")
    private static Set<String> getModDependencies(FileSystem fs) {
        return (Set<String>) getModInfo(fs, "dependencies");
    }

    private static boolean isBasicInfo(String infoType) {
        return "version".equals(infoType) || "modId".equals(infoType) || "environment".equals(infoType);
    }

    private static Object getModInfo(Path file, String infoType) {
        if (!file.getFileName().toString().endsWith(".jar") || !Files.exists(file)) {
            return isBasicInfo(infoType) ? null : Set.of();
        }

        try (FileSystem fs = FileSystems.newFileSystem(file)) {
            return getModInfo(fs, infoType);
        } catch (IOException e) {
            LOGGER.error("Error reading mod file {}: {}", file, e.getMessage());
        }
        return isBasicInfo(infoType) ? null : Set.of();
    }

    private static Object getModInfo(FileSystem fs, String infoType) {
        Path metadataPath = getMetadataPath(fs);

        if (metadataPath == null || !Files.exists(metadataPath)) {
            return isBasicInfo(infoType) ? null : Set.of();
        }

        try (InputStream stream = Files.newInputStream(metadataPath);
             BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {

            if (metadataPath.getFileName().toString().endsWith("mods.toml")) {
                return getModInfoFromToml(reader, infoType);
            } else {
                return getModInfoFromJson(reader, infoType);
            }
        } catch (IOException e) {
            LOGGER.error("Error reading metadata {}: {}", metadataPath, e.getMessage());
        }

        return isBasicInfo(infoType) ? null : Set.of();
    }

    private static Object getModInfoFromToml(BufferedReader reader, String infoType) {
        try {
            TomlParseResult result = Toml.parse(reader);
            result.errors().forEach(error -> LOGGER.error(error.toString()));

            TomlArray modsArray = result.getArray("mods");
            if (modsArray == null) {
                return isBasicInfo(infoType) ? null : Set.of();
            }

            switch (infoType) {
                case "version" -> {
                    String modVersion = null;
                    for (Object o : modsArray.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod != null) {
                            modVersion = mod.getString("version");
                        }
                    }
                    return modVersion != null ? modVersion : "1";
                }
                case "modId" -> {
                    String modID = null;
                    for (Object o : modsArray.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod != null) {
                            modID = mod.getString("modId");
                        }
                    }
                    return modID;
                }
                case "provides" -> {
                    Set<String> providedIDs = new HashSet<>();
                    for (Object o : modsArray.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod != null) {
                            TomlArray providesArray = mod.getArray("provides");
                            if (providesArray != null) {
                                for (int j = 0; j < providesArray.size(); j++) {
                                    String id = providesArray.getString(j);
                                    if (id != null && !id.isEmpty()) {
                                        providedIDs.add(id);
                                    }
                                }
                            }
                        }
                    }
                    return providedIDs;
                }
                case "dependencies" -> {
                    Set<String> dependencies = new HashSet<>();

                    String modID = null;
                    for (Object o : modsArray.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod != null) {
                            modID = mod.getString("modId");
                        }
                    }

                    if (modID == null) {
                        return dependencies;
                    }

                    TomlArray dependenciesArray = result.getArray("dependencies.\"" + modID + "\"");
                    if (dependenciesArray == null) {
                        return dependencies;
                    }

                    for (Object o : dependenciesArray.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod == null) continue;
                        String depId = mod.getString("modId");
                        if (depId == null) continue;
                        dependencies.add(depId);
                    }

                    return dependencies;
                }
                case "environment" -> {
                    LoaderManagerService.EnvironmentType environment = LoaderManagerService.EnvironmentType.UNIVERSAL;

                    String modID = null;
                    for (Object o : modsArray.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod != null) {
                            modID = mod.getString("modId");
                        }
                    }

                    if (modID == null) {
                        return environment;
                    }

                    TomlArray dependenciesArray = result.getArray("dependencies.\"" + modID + "\"");
                    if (dependenciesArray == null) {
                        return environment;
                    }

                    for (Object o : dependenciesArray.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod == null) continue;
                        String depId = mod.getString("modId");
                        if (depId == null) continue; // we only check for minecraft, neoforge and forge
                        if (!depId.equals("minecraft") && !depId.equals("neoforge") && !depId.equals("forge")) continue;
                        String depEnv = mod.getString("side");
                        if (depEnv == null) continue;
                        switch (depEnv.toLowerCase()) {
                            case "client" -> environment = LoaderManagerService.EnvironmentType.CLIENT;
                            case "server" -> environment = LoaderManagerService.EnvironmentType.SERVER;
                        }

                        if (environment != LoaderManagerService.EnvironmentType.UNIVERSAL) {
                            return environment;
                        }
                    }

                    return environment;
                }
            }
        } catch (Exception e) {
            LOGGER.error("Error parsing TOML metadata: {}", e.getMessage());
        }

        return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
    }

    private static Object getModInfoFromJson(BufferedReader reader, String infoType) {
        JsonObject json = GSON.fromJson(reader, JsonObject.class);

        switch (infoType) {
            case "version" -> {
                if (json.has("version")) {
                    return json.get("version").getAsString();
                } else if (json.has("quilt_loader") && json.get("quilt_loader").getAsJsonObject().has("version")) {
                    return json.get("quilt_loader").getAsJsonObject().get("version").getAsString();
                }
            }
            case "modId" -> {
                if (json.has("id")) {
                    return json.get("id").getAsString();
                } else if (json.has("quilt_loader") && json.get("quilt_loader").getAsJsonObject().has("id")) {
                    return json.get("quilt_loader").getAsJsonObject().get("id").getAsString();
                }
            }
            case "provides" -> {
                Set<String> providedIDs = new HashSet<>();
                if (json.has("provides")) {
                    for (JsonElement provides : json.get("provides").getAsJsonArray()) {
                        providedIDs.add(provides.getAsString());
                    }
                } else if (json.has("quilt_loader") && json.get("quilt_loader").getAsJsonObject().has("provides")) {
                    JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
                    for (JsonElement provides : quiltLoader.get("provides").getAsJsonArray()) {
                        JsonObject providesObject = provides.getAsJsonObject();
                        String id = providesObject.get("id").getAsString();
                        providedIDs.add(id);
                    }
                }
                return providedIDs;
            }
            case "dependencies" -> {
                Set<String> dependencies = new HashSet<>();
                if (json.has("depends")) {
                    JsonObject depends = json.get("depends").getAsJsonObject();
                    if (depends != null) { // Dont use asMap() since its only on gson 2.10^ - forge 1.18
                        dependencies.addAll(depends.entrySet().stream().map(Map.Entry::getKey).toList());
                    }
                } else if (json.has("quilt_loader") && json.get("quilt_loader").getAsJsonObject().has("depends")) {
                    JsonObject depends = json.get("quilt_loader").getAsJsonObject().get("depends").getAsJsonObject();
                    if (depends != null) { // Dont use asMap() since its only on gson 2.10^ - forge 1.18
                        dependencies.addAll(depends.entrySet().stream().map(Map.Entry::getKey).toList());
                    }
                }
                return dependencies;
            }
            case "environment" -> {
                if (json.has("environment")) {
                    String environment = json.get("environment").getAsString();
                    if (environment == null) return LoaderManagerService.EnvironmentType.UNIVERSAL;
                    return switch (environment.toLowerCase()) {
                        case "client" -> LoaderManagerService.EnvironmentType.CLIENT;
                        case "server" -> LoaderManagerService.EnvironmentType.SERVER;
                        default -> LoaderManagerService.EnvironmentType.UNIVERSAL;
                    };
                } else if (json.has("quilt_loader") && json.has("minecraft") && json.get("minecraft").getAsJsonObject().has("environment")) {
                    String environment = json.get("minecraft").getAsJsonObject().get("environment").getAsString();
                    if (environment == null) return LoaderManagerService.EnvironmentType.UNIVERSAL;
                    return switch (environment.toLowerCase()) {
                        case "client" -> LoaderManagerService.EnvironmentType.CLIENT;
                        case "server" -> LoaderManagerService.EnvironmentType.SERVER;
                        default -> LoaderManagerService.EnvironmentType.UNIVERSAL;
                    };
                }
            }
        }

        return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
    }

    private static final String forbiddenChars = "\\/:*\"<>|!?&%$;=+";

    public static boolean isInValidFileName(String fileName) {
        for (char c : forbiddenChars.toCharArray()) {
            if (fileName.indexOf(c) != -1) {
                return true;
            }
        }

        for (char c : fileName.toCharArray()) {
            if (c < 32 || c == 127) {
                return true;
            }
        }
        return fileName.trim().isEmpty();
    }

    public static String fixFileName(String fileName) {
        for (char c : fileName.toCharArray()) {
            if (c < 32 || c == 127 || forbiddenChars.indexOf(c) != -1) {
                fileName = fileName.replace(c, '-');
            }
        }
        return fileName.trim();
    }
}