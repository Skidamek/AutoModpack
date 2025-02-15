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

public class FileInspection {

    public static boolean isMod(Path file) {
        if (!file.getFileName().toString().endsWith(".jar")) {
            return false;
        }

        if (getModID(file) != null) {
            return true;
        }

        if (hasSpecificServices(file)) {
            return true;
        }

        return false;
    }

    public static Path getAutoModpackJar() {
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

    // Checks for neo/forge mod locators
    public static boolean hasSpecificServices(Path file) {
        if (!file.getFileName().toString().endsWith(".jar")) {
            return false;
        }

        if (!Files.exists(file)) {
            return false;
        }

        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry forgeIModLocator = zipFile.getEntry("META-INF/services/net.minecraftforge.forgespi.locating.IModLocator");
            ZipEntry forgeIDependencyLocator = zipFile.getEntry("META-INF/services/net.minecraftforge.forgespi.locating.IDependencyLocator");
            ZipEntry neoforgeIModLocator = zipFile.getEntry("META-INF/services/net.neoforged.neoforgespi.locating.IModLocator");
            ZipEntry neoforgeIDependencyLocator = zipFile.getEntry("META-INF/services/net.neoforged.neoforgespi.locating.IDependencyLocator");
            ZipEntry neoforgeIModFileCandidateLocator = zipFile.getEntry("META-INF/services/net.neoforged.neoforgespi.locating.IModFileCandidateLocator");
            ZipEntry neoforgeGraphicsBootstrapper = zipFile.getEntry("META-INF/services/net.neoforged.neoforgespi.earlywindow.GraphicsBootstrapper");
            zipFile.close();
            return forgeIModLocator != null || forgeIDependencyLocator != null || neoforgeIModLocator != null || neoforgeIDependencyLocator != null || neoforgeIModFileCandidateLocator != null || neoforgeGraphicsBootstrapper != null;
        } catch (IOException e) {
            e.printStackTrace();
        }

        return false;
    }

    public static String getModID(Path file) {
        if (!file.getFileName().toString().endsWith(".jar")) {
            return null;
        }

        if (!Files.exists(file)) {
            return null;
        }

        String modID = null;

        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
            } else if (zipFile.getEntry("quilt.mod.json") != null) {
                entry = zipFile.getEntry("quilt.mod.json");
            } else if (zipFile.getEntry("META-INF/mods.toml") != null) {
                entry = zipFile.getEntry("META-INF/mods.toml");
            } else if (zipFile.getEntry("META-INF/neoforge.mods.toml") != null) {
                entry = zipFile.getEntry("META-INF/neoforge.mods.toml");
            }

            if (entry == null) {
                zipFile.close();
                return null;
            }

            Gson gson = new Gson();
            InputStream stream = zipFile.getInputStream(entry);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

            if (entry.getName().equals("META-INF/mods.toml") || entry.getName().equals("META-INF/neoforge.mods.toml")) {
                TomlParseResult result = Toml.parse(reader);
                result.errors().forEach(error -> GlobalVariables.LOGGER.error(error.toString()));

                TomlArray array = result.getArray("mods");
                if (array != null) {
                    for (Object o : array.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod != null) {
                            modID = mod.getString("modId");
                        }
                    }
                }
            } else {
                JsonObject json = gson.fromJson(reader, JsonObject.class);

                if (entry.getName().equals("fabric.mod.json")) {
                    if (json.has("id")) {
                        modID = json.get("id").getAsString();
                    }

                } else if (entry.getName().equals("quilt.mod.json") && json.has("quilt_loader")) {
                    JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
                    if (quiltLoader.has("id")) {
                        modID = quiltLoader.get("id").getAsString();
                    }
                }
            }

            // close everything
            reader.close();
            stream.close();
            zipFile.close();

        } catch (ZipException ignored) {

        } catch (IOException e) {
            e.printStackTrace();
        }


        return modID;
    }

    public static Set<String> getModDependencies(Path file) {
        if (!file.getFileName().toString().endsWith(".jar")) {
            return Set.of();
        }

        if (!Files.exists(file)) {
            return Set.of();
        }

        Set<String> dependencies = new HashSet<>();

        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
            } else if (zipFile.getEntry("quilt.mod.json") != null) {
                entry = zipFile.getEntry("quilt.mod.json");
            } else if (zipFile.getEntry("META-INF/mods.toml") != null) {
                entry = zipFile.getEntry("META-INF/mods.toml");
            } else if (zipFile.getEntry("META-INF/neoforge.mods.toml") != null) {
                entry = zipFile.getEntry("META-INF/neoforge.mods.toml");
            }

            if (entry == null) {
                zipFile.close();
                return null;
            }

            Gson gson = new Gson();
            InputStream stream = zipFile.getInputStream(entry);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

            if (entry.getName().equals("META-INF/mods.toml") || entry.getName().equals("META-INF/neoforge.mods.toml")) {
                TomlParseResult result = Toml.parse(reader);
                result.errors().forEach(error -> GlobalVariables.LOGGER.error(error.toString()));

                String modID = getModID(file);
                TomlArray array = result.getArray("dependencies.\"" + modID + "\"");
                if (array != null) {
                    for (Object o : array.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod != null) {
                            dependencies.add(mod.getString("modId"));
                        }
                    }
                }
            } else {
                JsonObject json = gson.fromJson(reader, JsonObject.class);

                if (entry.getName().equals("fabric.mod.json")) {
                    if (json.has("depends")) {
                        JsonObject depends = json.get("depends").getAsJsonObject();
                        if (depends != null) {
                            // Dont use asMap() since its only on gson 2.10^ - forge 1.18
                            dependencies.addAll(depends.entrySet().stream().map(Map.Entry::getKey).toList());
                        }
                    }

                } else if (entry.getName().equals("quilt.mod.json") && json.has("quilt_loader")) {
                    JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
                    if (quiltLoader.has("depends")) {
                        JsonObject depends = quiltLoader.get("depends").getAsJsonObject();
                        if (depends != null) {
                            // Dont use asMap() since its only on gson 2.10^ - forge 1.18
                            dependencies.addAll(depends.entrySet().stream().map(Map.Entry::getKey).toList());
                        }
                    }
                }
            }

            // close everything
            reader.close();
            stream.close();
            zipFile.close();

        } catch (ZipException ignored) {
        } catch (IOException e) {
            e.printStackTrace();
        }


        return dependencies;
    }

    public static String getModVersion(Path file) {
        if (!file.getFileName().toString().endsWith(".jar")) {
            return null;
        }

        if (!Files.exists(file)) {
            return null;
        }

        String modVersion = null;

        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
            } else if (zipFile.getEntry("quilt.mod.json") != null) {
                entry = zipFile.getEntry("quilt.mod.json");
            } else if (zipFile.getEntry("META-INF/mods.toml") != null) {
                entry = zipFile.getEntry("META-INF/mods.toml");
            } else if (zipFile.getEntry("META-INF/neoforge.mods.toml") != null) {
                entry = zipFile.getEntry("META-INF/neoforge.mods.toml");
            }

            if (entry == null) {
                zipFile.close();
                return null;
            }

            Gson gson = new Gson();
            InputStream stream = zipFile.getInputStream(entry);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

            if (entry.getName().equals("META-INF/mods.toml") || entry.getName().equals("META-INF/neoforge.mods.toml")) {

                TomlParseResult result = Toml.parse(reader);
                result.errors().forEach(error -> GlobalVariables.LOGGER.error(error.toString()));


                TomlArray array = result.getArray("mods");
                if (array != null) {
                    for (Object o : array.toList()) {
                        TomlTable mod = (TomlTable) o;
                        if (mod != null) {
                            modVersion = mod.getString("version");
                        }
                    }
                }

                if ("${file.jarVersion}".equals(modVersion)) {
                    ZipEntry manifestEntry = zipFile.getEntry("META-INF/MANIFEST.MF");
                    if (manifestEntry == null) {
                        reader.close();
                        stream.close();
                        zipFile.close();
                        return null;
                    }

                    InputStream fileStream = zipFile.getInputStream(manifestEntry);
                    modVersion = ManifestReader.readForgeModVersion(fileStream);
                }
            } else {

                JsonObject json = gson.fromJson(reader, JsonObject.class);

                if (entry.getName().equals("fabric.mod.json")) {
                    if (json.has("version")) {
                        modVersion = json.get("version").getAsString();
                    }
                } else if (entry.getName().equals("quilt.mod.json") && json.has("quilt_loader")) {
                    JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
                    if (quiltLoader.has("version")) {
                        modVersion = quiltLoader.get("version").getAsString();
                    }
                }
            }

            // close everything
            reader.close();
            stream.close();
            zipFile.close();

        } catch (ZipException ignored) {

        } catch (IOException e) {
            e.printStackTrace();
        }

        return modVersion;
    }

    public static Set<String> getAllProvidedIDs(Path file) {
        if (!file.getFileName().toString().endsWith(".jar")) {
            return null;
        }

        if (!Files.exists(file)) {
            return null;
        }

        Set<String> providedIDs = new HashSet<>();

        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
            } else if (zipFile.getEntry("quilt.mod.json") != null) {
                entry = zipFile.getEntry("quilt.mod.json");
            } else if (zipFile.getEntry("META-INF/mods.toml") != null) {
                entry = zipFile.getEntry("META-INF/mods.toml");
            } else if (zipFile.getEntry("META-INF/neoforge.mods.toml") != null) {
                entry = zipFile.getEntry("META-INF/neoforge.mods.toml");
            }

            if (entry == null) {
                zipFile.close();
                return null;
            }

            Gson gson = new Gson();
            InputStream stream = zipFile.getInputStream(entry);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

            if (entry.getName().equals("META-INF/mods.toml") || entry.getName().equals("META-INF/neoforge.mods.toml")) {

                TomlParseResult result = Toml.parse(reader);
                result.errors().forEach(error -> GlobalVariables.LOGGER.error(error.toString()));

                TomlArray modsArray = result.getArray("mods");
                if (modsArray != null) {
                    for (int i = 0; i < modsArray.size(); i++) {
                        TomlTable mod = modsArray.getTable(i);
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
                }
            } else {

                JsonObject json = gson.fromJson(reader, JsonObject.class);

                if (entry.getName().equals("fabric.mod.json")) {
                    if (json.has("provides")) {
                        for (JsonElement provides : json.get("provides").getAsJsonArray()) {
                            providedIDs.add(provides.getAsString());
                        }
                    }
                } else if (entry.getName().equals("quilt.mod.json") && json.has("quilt_loader")) {
                    JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
                    if (quiltLoader.has("provides")) {
                        for (JsonElement provides : quiltLoader.get("provides").getAsJsonArray()) {
                            JsonObject providesObject = provides.getAsJsonObject();
                            String id = providesObject.get("id").getAsString();
                            providedIDs.add(id);
                        }
                    }
                }
            }

            // close everything
            reader.close();
            stream.close();
            zipFile.close();

        } catch (ZipException ignored) {

        } catch (IOException e) {
            e.printStackTrace();
        }

        return providedIDs;
    }

    public static LoaderManagerService.EnvironmentType getModEnvironment(Path file) {
        if (!file.getFileName().toString().endsWith(".jar")) {
            return null;
        }

        LoaderManagerService.EnvironmentType environmentType = LoaderManagerService.EnvironmentType.UNIVERSAL;

        if (!Files.exists(file)) {
            return environmentType;
        }

        try {
            ZipFile zipFile = new ZipFile(file.toFile());
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
            } else if (zipFile.getEntry("quilt.mod.json") != null) {
                entry = zipFile.getEntry("quilt.mod.json");
            } else if (zipFile.getEntry("META-INF/mods.toml") != null) {
                entry = zipFile.getEntry("META-INF/mods.toml");
            } else if (zipFile.getEntry("META-INF/neoforge.mods.toml") != null) {
                entry = zipFile.getEntry("META-INF/neoforge.mods.toml");
            }

            if (entry == null) {
                zipFile.close();
                return null;
            }

            Gson gson = new Gson();
            InputStream stream = zipFile.getInputStream(entry);
            BufferedReader reader = new BufferedReader(new InputStreamReader(stream));

            if (entry.getName().equals("META-INF/mods.toml") || entry.getName().equals("META-INF/neoforge.mods.toml")) {
                // Forges doesnt seem to have a way to specify environment in mods.toml
            } else {

                JsonObject json = gson.fromJson(reader, JsonObject.class);

                if (entry.getName().equals("fabric.mod.json")) {
                    if (json.has("environment")) {
                        String environment = json.get("environment").getAsString();
                        // switch (environment) set environmentType
                        environmentType = switch (environment) {
                            case "client" -> LoaderManagerService.EnvironmentType.CLIENT;
                            case "server" -> LoaderManagerService.EnvironmentType.SERVER;
                            default -> environmentType;
                        };
                    }
                } else if (entry.getName().equals("quilt.mod.json") && json.has("quilt_loader")) {
                    JsonObject quiltLoader = json.get("minecraft").getAsJsonObject();
                    if (quiltLoader.has("environment")) {
                        String environment = quiltLoader.get("environment").getAsString();

                        environmentType = switch (environment) {
                            case "client" -> LoaderManagerService.EnvironmentType.CLIENT;
                            case "dedicated_server" -> LoaderManagerService.EnvironmentType.SERVER;
                            default -> environmentType;
                        };
                    }
                }
            }

            // close everything
            reader.close();
            stream.close();
            zipFile.close();

        } catch (ZipException ignored) {

        } catch (IOException e) {
            e.printStackTrace();
        }

        return environmentType;
    }

    private static final String forbiddenChars = "\\/:*\"<>|!?.";

    public static boolean isInValidFileName(String fileName) {
        // Check for each forbidden character in the file name
        for (char c : forbiddenChars.toCharArray()) {
            if (fileName.indexOf(c) != -1) {
                return true;
            }
        }

        // Check if the file name is empty or just contains whitespace
        return fileName.trim().isEmpty();
    }

    public static String fixFileName(String fileName) {
        // Replace forbidden characters with underscores
        for (char c : forbiddenChars.toCharArray()) {
            fileName = fileName.replace(c, '-');
        }

        // Remove leading and trailing whitespace
        fileName = fileName.trim();

        return fileName;
    }
}
