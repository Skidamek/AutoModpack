package pl.skidam.automodpack_core.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public class FileInspection {

    private static final Gson GSON = new Gson();
    private static final String LOADER = Constants.LOADER;

    public record HashPathPair(String hash, Path path) {}

    public record Mod(Set<String> IDs, String hash, String version, Path path, Set<String> deps, Set<Mod> nestedMods) implements Serializable {

        // Magic to de/serialize Path properly

        @Serial
        private Object writeReplace() {
            return new SerializationProxy(this);
        }

        private record SerializationProxy(Set<String> IDs, String hash, String version, String pathString, Set<String> deps, Set<Mod> nestedMods) implements Serializable {

            public SerializationProxy(Mod mod) {
                this(mod.IDs(), mod.hash(), mod.version(), mod.path() == null ? null : mod.path().toAbsolutePath().normalize().toString(), mod.deps(), mod.nestedMods());
            }

            @Serial
            private Object readResolve() {
                return new Mod(IDs, hash, version, pathString == null ? null : Path.of(pathString), deps, nestedMods);
            }
        }
    }

    private record ModMetadata(String modId, String version, Set<String> provides, Set<String> deps, LoaderManagerService.EnvironmentType environment) {}

    public static Mod getMod(Path file, FileMetadataCache cache) {
        if (isJarInvalid(file)) return null;

        String hash = cache != null ? cache.getHashOrNull(file) : SmartFileUtils.getHash(file);
        if (hash == null) {
            LOGGER.error("Failed to get hash for file: {}", file);
            return null;
        }

        try (FileSystem fs = FileSystems.newFileSystem(file)) {
            ModMetadata meta = getModMetadata(fs);

            if (meta != null && meta.modId() != null) {
                Set<String> ids = new HashSet<>(meta.provides());
                ids.add(meta.modId());

                Set<Mod> nestedMods = scanForNestedMods(fs);

                if (meta.version() != null) {
                    return new Mod(ids, hash, meta.version(), file, meta.deps(), nestedMods);
                }
                LOGGER.error("Incomplete mod info for file: {} (ID: {}, Ver: {})", file, meta.modId(), meta.version());
            }
        } catch (IOException e) {
            LOGGER.debug("Failed to inspect mod file: {}", file);
        }
        return null;
    }

    public static boolean isMod(Path file) {
        if (isJarInvalid(file)) return false;
        try (FileSystem fs = FileSystems.newFileSystem(file)) {
            return getModMetadata(fs) != null || hasSpecificServices(fs);
        } catch (IOException e) {
            return false;
        }
    }

    public static boolean isModCompatible(Path file) {
        if (isJarInvalid(file)) return false;

        try (FileSystem fs = FileSystems.newFileSystem(file)) {
            Path metaPath = getMetadataPath(fs);
            if (metaPath != null) return true;

            if ("forge".equals(LOADER) || "neoforge".equals(LOADER)) {
                return hasSpecificServices(fs);
            }
        } catch (IOException e) {
            LOGGER.error("Error examining JarJar in {}", e);
        }
        return false;
    }

    public static String getModVersion(Path file) {
        return extractBasicInfo(file, ModMetadata::version);
    }

    public static String getModID(Path file) {
        return extractBasicInfo(file, ModMetadata::modId);
    }

    public static LoaderManagerService.EnvironmentType getModEnvironment(Path file) {
        return extractBasicInfo(file, ModMetadata::environment);
    }

    private static boolean isJarInvalid(Path file) {
        return file == null || !Files.exists(file) || !file.getFileName().toString().endsWith(".jar");
    }

    private static <T> T extractBasicInfo(Path file, java.util.function.Function<ModMetadata, T> extractor) {
        if (isJarInvalid(file)) return null;
        try (FileSystem fs = FileSystems.newFileSystem(file)) {
            ModMetadata meta = getModMetadata(fs);
            return meta != null ? extractor.apply(meta) : null;
        } catch (IOException e) {
            LOGGER.error("Error reading mod file {}: {}", file, e.getMessage());
        }
        return null;
    }

    // TODO optimize it by caching and scanning only defined paths
    private static Set<Mod> scanForNestedMods(FileSystem parentFs) {
        Set<Mod> nestedMods = new HashSet<>();
        try (Stream<Path> walk = Files.walk(parentFs.getPath("/"))) {
            for (Path path : walk.toList()) {
                if (path.toString().endsWith(".jar") && !path.equals(parentFs.getPath("/"))) {
                    try (InputStream is = Files.newInputStream(path)) {
                        Mod nested = readModFromStream(path, is);
                        if (nested != null) nestedMods.add(nested);
                    } catch (IOException e) {
                        LOGGER.debug("Skipping unreadable nested jar: {}", path);
                    }
                }
            }
        } catch (IOException e) {
            LOGGER.error("Error scanning nested mods: {}", e.getMessage());
        }
        return nestedMods;
    }

    /**
     * Reads a JAR from an InputStream (recursively) without mounting it as a FileSystem.
     */
    private static Mod readModFromStream(Path virtualPath, InputStream is) {
        // ZipInputStream must NOT close the underlying stream if it's a child stream
        ZipInputStream zis = new ZipInputStream(is);
        ZipEntry entry;
        ModMetadata metadata = null;
        Set<Mod> nestedChildren = new HashSet<>();

        try {
            while ((entry = zis.getNextEntry()) != null) {
                String name = entry.getName();

                if (isMetadataFilename(name)) {
                    // Prevent reader from closing the ZipInputStream
                    BufferedReader reader = new BufferedReader(new InputStreamReader(new FilterInputStream(zis) {
                        @Override public void close() {}
                    }));

                    if (name.endsWith(".toml")) metadata = parseTomlMetadata(reader);
                    else metadata = parseJsonMetadata(reader);
                }
                else if (name.endsWith(".jar")) {
                    // Wrap ZIS to protect current stream position
                    Mod child = readModFromStream(virtualPath.resolve(name), new FilterInputStream(zis) {
                        @Override public void close() {}
                    });
                    if (child != null) nestedChildren.add(child);
                }
            }
        } catch (IOException e) {
            LOGGER.debug("Error processing stream for {}", virtualPath);
        }

        if (metadata != null && metadata.modId() != null) {
            Set<String> ids = new HashSet<>(metadata.provides());
            ids.add(metadata.modId());
            // Investigate if we need hash or not
            return new Mod(ids, null, metadata.version(), virtualPath, metadata.deps(), nestedChildren);
        }
        return null;
    }

    private static ModMetadata getModMetadata(FileSystem fs) {
        Path metaPath = getMetadataPath(fs);
        if (metaPath == null) return null;

        try (BufferedReader reader = Files.newBufferedReader(metaPath)) {
            if (metaPath.toString().endsWith(".toml")) {
                return parseTomlMetadata(reader);
            } else {
                return parseJsonMetadata(reader);
            }
        } catch (IOException e) {
            LOGGER.error("Error parsing metadata {}: {}", metaPath, e.getMessage());
        }
        return null;
    }

    private static ModMetadata parseTomlMetadata(BufferedReader reader) {
        try {
            TomlParseResult result = Toml.parse(reader);
            TomlArray mods = result.getArray("mods");
            if (mods == null || mods.isEmpty()) return null;

            String modId = null;
            String version = "1";
            Set<String> provides = new HashSet<>();
            Set<String> deps = new HashSet<>();
            LoaderManagerService.EnvironmentType env = LoaderManagerService.EnvironmentType.UNIVERSAL;

            for (int i = 0; i < mods.size(); i++) {
                TomlTable modTable = mods.getTable(i);
                if (modTable == null) continue;

                if (modId == null) modId = modTable.getString("modId");

                String v = modTable.getString("version");
                if (v != null && !v.equals("${file.jarVersion}")) version = v;

                TomlArray prov = modTable.getArray("provides");
                if (prov != null) {
                    for (int j = 0; j < prov.size(); j++) provides.add(prov.getString(j));
                }
            }

            if (modId != null) {
                TomlArray depArray = result.getArray("deps.\"" + modId + "\"");
                if (depArray != null) {
                    for (int i = 0; i < depArray.size(); i++) {
                        TomlTable depTable = depArray.getTable(i);
                        String depId = depTable.getString("modId");
                        if (depId == null) continue;

                        deps.add(depId);

                        // Determine Environment based on Minecraft/Forge side requirement
                        if (isPlatformId(depId)) {
                            String side = depTable.getString("side");
                            if ("client".equalsIgnoreCase(side)) env = LoaderManagerService.EnvironmentType.CLIENT;
                            else if ("server".equalsIgnoreCase(side)) env = LoaderManagerService.EnvironmentType.SERVER;
                        }
                    }
                }
            }
            return new ModMetadata(modId, version, provides, deps, env);
        } catch (Exception e) {
            LOGGER.error("TOML Parse Error: {}", e.getMessage());
            return null;
        }
    }

    private static ModMetadata parseJsonMetadata(BufferedReader reader) {
        try {
            JsonObject json = GSON.fromJson(reader, JsonObject.class);
            JsonObject root = json;

            if (json.has("quilt_loader")) {
                root = json.getAsJsonObject("quilt_loader");
            }

            String modId = getJsonString(root, "id");
            String version = getJsonString(root, "version");
            Set<String> provides = new HashSet<>();
            Set<String> deps = new HashSet<>();
            LoaderManagerService.EnvironmentType env = LoaderManagerService.EnvironmentType.UNIVERSAL;

            if (root.has("provides")) {
                for (JsonElement e : root.get("provides").getAsJsonArray()) {
                    if (e.isJsonObject()) provides.add(e.getAsJsonObject().get("id").getAsString());
                    else provides.add(e.getAsString());
                }
            }

            if (root.has("depends")) {
                JsonElement depends = root.get("depends");
                if (depends.isJsonObject()) {
                    deps.addAll(depends.getAsJsonObject().keySet());
                } else if (depends.isJsonArray()) {
                    for (JsonElement e : depends.getAsJsonArray()) {
                        if (e.isJsonObject()) deps.add(e.getAsJsonObject().get("id").getAsString());
                        else deps.add(e.getAsString());
                    }
                }
            }

            if (root.has("environment")) {
                String envStr = root.get("environment").getAsString();
                if ("client".equalsIgnoreCase(envStr)) env = LoaderManagerService.EnvironmentType.CLIENT;
                else if ("server".equalsIgnoreCase(envStr)) env = LoaderManagerService.EnvironmentType.SERVER;
            }

            return new ModMetadata(modId, version, provides, deps, env);
        } catch (Exception e) {
            LOGGER.error("JSON Parse Error: {}", e.getMessage());
            return null;
        }
    }

    private static boolean isPlatformId(String id) {
        return "minecraft".equals(id) || "neoforge".equals(id) || "forge".equals(id);
    }

    private static String getJsonString(JsonObject obj, String key) {
        return obj.has(key) ? obj.get(key).getAsString() : null;
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
            Path p = fs.getPath(preferredEntry);
            if (Files.exists(p)) return p;
        }

        for (String fallback : List.of("META-INF/neoforge.mods.toml", "fabric.mod.json", "META-INF/mods.toml", "quilt.mod.json")) {
            if (fallback.equals(preferredEntry)) continue;
            Path p = fs.getPath(fallback);
            if (Files.exists(p)) return p;
        }
        return null;
    }

    private static boolean isMetadataFilename(String name) {
        return name.endsWith("mods.toml") || name.endsWith("mod.json");
    }

    private static final Set<String> KNOWN_SERVICES = Set.of(
            "META-INF/services/net.minecraftforge.forgespi.locating.IModLocator",
            "META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator",
            "META-INF/services/net.minecraftforge.forgespi.language.IModLanguageProvider",
            "META-INF/services/net.neoforged.neoforgespi.locating.IModLocator",
            "META-INF/services/net.neoforged.neoforgespi.locating.IDependencyLocator",
            "META-INF/services/net.neoforged.neoforgespi.locating.IModLanguageLoader",
            "META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator",
            "META-INF/services/net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper"
    );

    public static boolean hasSpecificServices(FileSystem fs) {
        // Fast Check
        for (String service : KNOWN_SERVICES) {
            if (Files.exists(fs.getPath(service))) return true;
        }

        return false;
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