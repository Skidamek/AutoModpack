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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class FileInspection {

    public static boolean isMod(Path file) {
        return getModID(file) != null || hasSpecificServices(file);
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

        String modId = FileInspection.getModID(file);
        if (modId != null) { // If mod id is null dont need to check for other info
            String modVersion = FileInspection.getModVersion(file);
            LoaderManagerService.EnvironmentType environmentType = FileInspection.getModEnvironment(file);
            Set<String> dependencies = FileInspection.getModDependencies(file);
            Set<String> providesIDs = FileInspection.getAllProvidedIDs(file);

            if (modVersion != null && dependencies != null) {
                var mod = new Mod(modId, hash, providesIDs, modVersion, file, environmentType, dependencies);
                modCache.put(hashPathPair, mod);
                return mod;
            }

            LOGGER.error("Not enough mod information for file: {} modId: {}, modVersion: {}, dependencies: {}", file, modId, modVersion, dependencies);
        }

        LOGGER.debug("Failed to get mod info for file: {}", file);
        return null;
    }

    public static Path getThizJar() { // CodeSource class not wonky on forge...
        try {
            // TODO find better way to parse that path
            URI uri = FileInspection.class.getProtectionDomain().getCodeSource().getLocation().toURI();
            // Example: union:/home/skidam/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/1.18.2/.minecraft/mods/automodpack-forge-4.0.0-beta0-1.18.2.jar%2354!/
            // Format it into proper path like: /home/skidam/.var/app/org.prismlauncher.PrismLauncher/data/PrismLauncher/instances/1.18.2/.minecraft/mods/automodpack-forge-4.0.0-beta0-1.18.2.jar

            String path = uri.getPath();
            int index = path.indexOf('!');
            if (index != -1) {
                path = path.substring(0, index);
            }

            index = path.indexOf('#');
            if (index != -1) {
                path = path.substring(0, index);
            }

            // check for windows
            if (System.getProperty("os.name").toLowerCase().contains("win")) {
                if (path.startsWith("/")) {
                    path = path.substring(1);
                }
            }

            return Path.of(path).toAbsolutePath().normalize();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
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
    // TODO: check nested jars recursively if needed
    public static boolean hasSpecificServices(Path file) {
        if (!file.getFileName().toString().endsWith(".jar") || !Files.exists(file)) {
            return false;
        }

        try (ZipFile zipFile = new ZipFile(file.toFile())) {
            // Direct lookup for known service entries
            for (String service : services) {
                ZipEntry entry = zipFile.getEntry(service);
                if (entry != null) {
                    return true;
                }
            }

            String jarjarPrefix = "META-INF/jarjar/";
            ZipEntry jarjarEntry = zipFile.getEntry(jarjarPrefix);
            if (jarjarEntry == null) {
                return false;
            }

            // Check nested JARs in META-INF/jarjar/
            for (ZipEntry entry : Collections.list(zipFile.entries())) {
                String entryName = entry.getName();

                if (!entry.isDirectory() && entryName.startsWith(jarjarPrefix) && entryName.endsWith(".jar")) {
                    try (InputStream inputStream = zipFile.getInputStream(entry);
                         ZipInputStream zipInputStream = new ZipInputStream(inputStream)) {

                        ZipEntry nestedEntry;
                        while ((nestedEntry = zipInputStream.getNextEntry()) != null) {
                            if (services.contains(nestedEntry.getName())) {
                                return true;
                            }
                        }
                    } catch (IOException e) {
                        LOGGER.error("Error reading nested JAR in {}: {}", file, e.getMessage());
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error examining JAR file {}: {}", file, e.getMessage());
        }

        return false;
    }

    public static boolean isModCompatible(Path file) {
        if (!file.getFileName().toString().endsWith(".jar") || !Files.exists(file)) {
            return false;
        }

        try (ZipFile zipFile = new ZipFile(file.toFile())) {
            String loader = GlobalVariables.LOADER;
            String entryName = switch (loader) {
                case "fabric" -> "fabric.mod.json";
                case "quilt" -> "quilt.mod.json";
                case "forge" -> "META-INF/mods.toml";
                case "neoforge" -> "META-INF/neoforge.mods.toml";
                default -> null;
            };

            if (loader.equals("forge") || loader.equals("neoforge")) {
                if (hasSpecificServices(file)) {
                    return true;
                }
            }

            return entryName != null && zipFile.getEntry(entryName) != null;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return false;
    }


    public static ZipEntry getMetadataEntry(ZipFile zipFile) {
        var currentLoader = GlobalVariables.LOADER;

        // first get preferred metadata for current loader if exits
        ZipEntry entry = switch (currentLoader) {
            case "fabric" -> zipFile.getEntry("fabric.mod.json");
            case "quilt" -> zipFile.getEntry("quilt.mod.json");
            case "forge" -> zipFile.getEntry("META-INF/mods.toml");
            case "neoforge" -> zipFile.getEntry("META-INF/neoforge.mods.toml");
            default -> null;
        };

        if (entry != null) {
            return entry;
        }

        // get any existing
        String[] entriesToCheck = {
                "fabric.mod.json",
                "META-INF/neoforge.mods.toml",
                "META-INF/mods.toml",
                "quilt.mod.json",
        };

        for (String entryName : entriesToCheck) {
            entry = zipFile.getEntry(entryName);
            if (entry != null) {
                return entry;
            }
        }

        return null;
    }

    public static String getModVersion(Path file) {
        return (String) getModInfo(file, "version");
    }

    public static String getModID(Path file) {
        return (String) getModInfo(file, "modId");
    }

    @SuppressWarnings("unchecked")
    public static Set<String> getAllProvidedIDs(Path file) {
        return (Set<String>) getModInfo(file, "provides");
    }

    @SuppressWarnings("unchecked")
    public static Set<String> getModDependencies(Path file) {
        return (Set<String>) getModInfo(file, "dependencies");
    }

    public static LoaderManagerService.EnvironmentType getModEnvironment(Path file) {
        return (LoaderManagerService.EnvironmentType) getModInfo(file, "environment");
    }

    private static Object getModInfo(Path file, String infoType) {
        if (!file.getFileName().toString().endsWith(".jar") || !Files.exists(file)) {
            return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
        }

        try (ZipFile zipFile = new ZipFile(file.toFile())) {
            ZipEntry entry = getMetadataEntry(zipFile);
            if (entry == null) {
                return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
            }

            Gson gson = new Gson();
            try (InputStream stream = zipFile.getInputStream(entry);
                 BufferedReader reader = new BufferedReader(new InputStreamReader(stream))) {

                if (entry.getName().endsWith("mods.toml")) {
                    return getModInfoFromToml(reader, infoType, file);
                } else {
                    return getModInfoFromJson(reader, gson, infoType);
                }
            }
        } catch (ZipException ignored) {
        } catch (IOException e) {
            e.printStackTrace();
        }

        return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
    }

    private static Object getModInfoFromToml(BufferedReader reader, String infoType, Path file) {
        try {
            TomlParseResult result = Toml.parse(reader);
            result.errors().forEach(error -> LOGGER.error(error.toString()));

            TomlArray modsArray = result.getArray("mods");
            if (modsArray == null) {
                return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
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
                    String modID = getModID(file);
                    TomlArray dependenciesArray = result.getArray("dependencies.\"" + modID + "\"");
                    Set<String> dependencies = new HashSet<>();
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
                    String modID = getModID(file);
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
            e.printStackTrace();
        }

        return infoType.equals("version") || infoType.equals("modId") || infoType.equals("environment") ? null : Set.of();
    }

    private static Object getModInfoFromJson(BufferedReader reader, Gson gson, String infoType) {
        JsonObject json = gson.fromJson(reader, JsonObject.class);

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
                } else if (json.has("quilt_loader") && json.get("minecraft").getAsJsonObject().has("environment")) {
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
        // Check for each forbidden character in the file name
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

        // Check if the file name is empty or just contains whitespace
        return fileName.trim().isEmpty();
    }

    public static String fixFileName(String fileName) {
        // Replace forbidden characters with underscores
        for (char c : fileName.toCharArray()) {
            if (c < 32 || c == 127) {
                fileName = fileName.replace(c, '-');
            }

            if (forbiddenChars.indexOf(c) != -1) {
                fileName = fileName.replace(c, '-');
            }
        }

        // Remove leading and trailing whitespace
        fileName = fileName.trim();

        return fileName;
    }
}
