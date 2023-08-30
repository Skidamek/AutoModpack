package pl.skidam.automodpack_common.utils;

import com.google.gson.Gson;
import com.google.gson.JsonObject;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipFile;

public class FileInspection {

    public static String getModID(Path file) {
        if (!file.getFileName().toString().endsWith(".jar")) {
            return null;
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
            }

            if (entry != null) {
                Gson gson = new Gson();
                InputStream stream = zipFile.getInputStream(entry);
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                JsonObject json = gson.fromJson(reader, JsonObject.class);

                // close everything
                reader.close();
                stream.close();
                zipFile.close();

                if (entry.getName().equals("fabric.mod.json")) {
                    if (json.has("id")) {
                        return json.get("id").getAsString();
                    }
                } else if (entry.getName().equals("quilt.mod.json") && json.has("quilt_loader")) {
                    JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
                    if (quiltLoader.has("id")) {
                        return quiltLoader.get("id").getAsString();
                    }
                } else {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("side")) {
                            String[] split = line.split("=");
                            if (split.length > 1) {
                                return split[1].replaceAll("\"", "").trim();
                            }
                        }
                    }
                }
            }
        } catch (ZipException ignored) {

        } catch (IOException e) {
            e.printStackTrace();
        }


        return null;
    }

    public static String getModVersion(Path file) {
        if (!file.getFileName().toString().endsWith(".jar")) {
            return null;
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
            }

            if (entry != null) {
                Gson gson = new Gson();
                InputStream stream = zipFile.getInputStream(entry);
                BufferedReader reader = new BufferedReader(new InputStreamReader(stream));
                JsonObject json = gson.fromJson(reader, JsonObject.class);

                // close everything
                reader.close();
                stream.close();
                zipFile.close();

                if (entry.getName().equals("fabric.mod.json")) {
                    if (json.has("version")) {
                        return json.get("version").getAsString();
                    }
                } else if (entry.getName().equals("quilt.mod.json") && json.has("quilt_loader")) {
                    JsonObject quiltLoader = json.get("quilt_loader").getAsJsonObject();
                    if (quiltLoader.has("version")) {
                        return quiltLoader.get("version").getAsString();
                    }
                } else {
                    String line;
                    while ((line = reader.readLine()) != null) {
                        if (line.startsWith("version")) {
                            String[] split = line.split("=");
                            if (split.length > 1) {
                                String version = split[1].substring(0, split[1].lastIndexOf("\""));
                                version = version.replaceAll("\"", "").trim();

                                if ("${file.jarVersion}".equals(version)) {
                                    ZipEntry manifestEntry = zipFile.getEntry("META-INF/MANIFEST.MF");
                                    if (manifestEntry != null) {
                                        BufferedReader manifestReader = new BufferedReader(new InputStreamReader(zipFile.getInputStream(manifestEntry)));
                                        String manifestLine;
                                        while ((manifestLine = manifestReader.readLine()) != null) {
                                            if (manifestLine.startsWith("Implementation-Version")) {
                                                String[] manifestSplit = manifestLine.split(":");
                                                if (manifestSplit.length > 1) {
                                                    version = manifestSplit[1].trim();
                                                }
                                            }
                                        }
                                    }
                                }

                                return version;
                            }
                        }
                    }
                }
            }
        } catch (ZipException ignored) {

        } catch (IOException e) {
            e.printStackTrace();
        }


        return null;
    }
}
