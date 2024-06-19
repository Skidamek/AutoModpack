package pl.skidam.automodpack_core.utils;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import org.tomlj.Toml;
import org.tomlj.TomlArray;
import org.tomlj.TomlParseResult;
import org.tomlj.TomlTable;
import pl.skidam.automodpack_core.GlobalVariables;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class FileInspection {

    public static boolean isMod(Path file) {
        if (!file.getFileName().toString().endsWith(".jar")) {
            return false;
        }

        try (ZipFile zipFile = new ZipFile(file.toFile())) {
            zipFile.close();
            return true;
        } catch (ZipException ignored) {
            // Handle the exception as necessary
        } catch (IOException e) {
            e.printStackTrace();
        }

        return false;
    }

    public static String getModID(Path file) {
        if (!file.getFileName().toString().endsWith(".jar")) {
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

    public static List<String> getModDependencies(Path file) {
        if (!file.getFileName().toString().endsWith(".jar")) {
            return List.of();
        }

        List<String> dependencies = new ArrayList<>();

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
                        dependencies.addAll(json.get("depends").getAsJsonObject().asMap().keySet());
                    }

                } else if (entry.getName().equals("quilt.mod.json") && json.has("quilt_loader")) {
                    JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
                    if (quiltLoader.has("depends")) {
                        dependencies.addAll(quiltLoader.get("depends").getAsJsonObject().asMap().keySet());
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
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("version")) {
                        continue;
                    }

                    String[] split = line.split("=");
                    if (split.length <= 1) {
                        continue;
                    }

                    String version = split[1].substring(0, split[1].lastIndexOf("\""));
                    version = version.replaceAll("\"", "").trim();

                    if ("${file.jarVersion}".equals(version)) {

                        ZipEntry manifestEntry = zipFile.getEntry("META-INF/MANIFEST.MF");
                        if (manifestEntry == null) {
                            reader.close();
                            stream.close();
                            zipFile.close();
                            return null;
                        }

                        BufferedReader manifestReader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(manifestEntry)));
                        String manifestLine;
                        while ((manifestLine = manifestReader.readLine()) != null) {
                            if (!manifestLine.startsWith("Implementation-Version")) {
                                continue;
                            }

                            String[] manifestSplit = manifestLine.split(":");
                            if (manifestSplit.length > 1) {
                                version = manifestSplit[1].trim();
                            }
                        }

                    }

                    version = version.split(" ")[0]; // get first string before any # comments starts
                    modVersion = version;
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

    public static boolean isInValidFileName(String fileName) {
        // Define a list of characters that are not allowed in file names
        String forbiddenChars = "\\/:*?\"<>|";

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
        // Define a list of characters that are not allowed in file names
        String forbiddenChars = "\\/:*?\"<>|";

        // Replace forbidden characters with underscores
        for (char c : forbiddenChars.toCharArray()) {
            fileName = fileName.replace(c, '-');
        }

        // Remove leading and trailing whitespace
        fileName = fileName.trim();

        return fileName;
    }
}
