package pl.skidam.automodpack.fabric;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
import com.mojang.logging.LogUtils;
import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;
import org.slf4j.Logger;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.Download;
import pl.skidam.automodpack.Platform;
import pl.skidam.automodpack.ReLauncher;
import pl.skidam.automodpack.client.ui.AutoModpackToast;
import pl.skidam.automodpack.ui.Windows;
import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.ModrinthAPI;

import java.awt.*;
import java.io.*;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Optional;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import static pl.skidam.automodpack.AutoModpack.clientConfig;
import static pl.skidam.automodpack.AutoModpack.modsPath;
import static pl.skidam.automodpack.Platform.ModPlatform.FABRIC;

public class PlatformImpl {
    static final Logger LOGGER = LogUtils.getLogger();

    public static Platform.ModPlatform getPlatformType() {
        return FABRIC;
    }

    public static boolean isModLoaded(String modid) {
        return FabricLoader.getInstance().isModLoaded(modid);
    }

    public static Collection getModList() {
        Collection<ModContainer> modsList = FabricLoader.getInstance().getAllMods();

        return modsList;
    }

    public static void downloadDependencies() {
        if (!Platform.isModLoaded("fabric-api") && !Platform.isModLoaded("fabric")) { // FAPI

            LOGGER.warn("Dependency (FAPI) was not found");

            if (Platform.getEnvironmentType().equals("SERVER")) {
                if (!AutoModpack.serverConfig.updateCheck) {
                    AutoModpack.LOGGER.warn("AutoModpack update check is disabled, you need to manually install fabric api!");
                    return;
                }
            }

            if (Platform.getEnvironmentType().equals("CLIENT")) {
                if (!AutoModpack.clientConfig.updateCheck) {
                    AutoModpack.LOGGER.warn("AutoModpack update check is disabled, you need to manually install fabric api!");
                    return;
                }
            }

            ModrinthAPI fapi = new ModrinthAPI("P7dR8mSH");

            if (fapi == null) return;

            LOGGER.info("Installing latest Fabric API (FAPI)! " + fapi.modrinthAPIversion);
            LOGGER.info("Download URL: " + fapi.modrinthAPIdownloadUrl);
            try {
                Download downloadInstance = new Download();

                File file = new File(modsPath.toFile() + File.separator + fapi.modrinthAPIfileName);

                downloadInstance.download(fapi.modrinthAPIdownloadUrl, file);

                String localChecksum = CustomFileUtils.getHash(file, "SHA-512");


                if (!localChecksum.equals(fapi.modrinthAPISHA512Hash)) {
                    AutoModpack.LOGGER.error("Checksums are not the same! Downloaded file is corrupted!");
                    AutoModpackToast.add(5);
                    return;
                }
            } catch (Exception e) {
                e.printStackTrace();
                LOGGER.error("Failed to download FAPI!");
                return;
            }
            LOGGER.info("Successfully installed latest version of Fabric API (FAPI)!");

            if (Platform.getEnvironmentType().equals("CLIENT")) {
                if (clientConfig.autoRelaunchWhenUpdated) {
                    if (!Platform.Forge) ReLauncher.run(null);
                } else if (!GraphicsEnvironment.isHeadless()) {
                    new Windows().restartWindow(("Successfully installed latest FAPI!"));
                }
            } else {
                LOGGER.info("Restart your server!");
                System.exit(0);
            }
        }
    }

    public static File getModPath(String modid) {
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            Optional<ModContainer> container = FabricLoader.getInstance().getModContainer(modid);

            if (container.isPresent()) {
                ModContainer modContainer = container.get();
                Path jarPath = modContainer.getRootPaths().stream().findFirst().isPresent() ? modContainer.getRootPaths().stream().findFirst().get() : null;

                if (jarPath == null) {
                    AutoModpack.LOGGER.error("Could not find jar file for " + modid);
                    return null;
                }

                FileSystem fileSystem = jarPath.getFileSystem();

                return new File(fileSystem.toString());
            }
        }
        return null;
    }

    public static String getEnvironmentType() {
        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.CLIENT) {
            return "CLIENT";
        } else if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) {
            return "SERVER";
        } else {
            return "UNKNOWN";
        }
    }

    public static String getModEnvironmentFromNotLoadedJar(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
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

                if (json.has("environment")) {
                    return json.get("environment").getAsString().toUpperCase();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return "UNKNOWN";
    }

    public static String getModVersion(String modid) {
        return FabricLoader.getInstance().getModContainer(modid).isPresent() ? FabricLoader.getInstance().getModContainer(modid).get().getMetadata().getVersion().getFriendlyString() : null;
    }

    public static String getModVersion(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
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

                if (json.has("version")) {
                    return json.get("version").getAsString();
                }
            }
        } catch (IOException | JsonSyntaxException e) {
            e.printStackTrace();
        }

        return null;
    }

    public static String getConfigDir() {
        return FabricLoader.getInstance().getConfigDir().toString();
    }

    public static boolean isDevelopmentEnvironment() {
        return FabricLoader.getInstance().isDevelopmentEnvironment();
    }

    public static String getModEnvironment(String modid) {
        return FabricLoader.getInstance().getModContainer(modid).isPresent() ?  FabricLoader.getInstance().getModContainer(modid).get().getMetadata().getEnvironment().toString().toUpperCase() : "*";
    }

    public static String getModIdFromLoadedJar(File file, boolean checkAlsoOutOfContainer) {
        for (ModContainer modContainer : FabricLoader.getInstance().getAllMods()) {
            FileSystem fileSys = modContainer.getRootPaths().get(0).getFileSystem();
            File modFile = new File(fileSys.toString());
            if (modFile.getName().equals(file.getName())) {
                return modContainer.getMetadata().getId();
            }
        }

        if (checkAlsoOutOfContainer) return getModIdFromNotLoadedJar(file);

        return null;
    }

    public static String getModIdFromNotLoadedJar(File file) {
        try {
            ZipFile zipFile = new ZipFile(file);
            ZipEntry entry = null;
            if (zipFile.getEntry("fabric.mod.json") != null) {
                entry = zipFile.getEntry("fabric.mod.json");
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

                if (json.has("id")) {
                    return json.get("id").getAsString();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        return null;
    }
}
