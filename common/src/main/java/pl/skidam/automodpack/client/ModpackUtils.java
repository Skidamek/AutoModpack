package pl.skidam.automodpack.client;

import pl.skidam.automodpack.config.ConfigTools;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.utils.CustomFileUtils;
import pl.skidam.automodpack.utils.MinecraftUserName;
import pl.skidam.automodpack.utils.ModpackContentTools;

import java.io.File;
import java.io.IOException;
import java.net.ConnectException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;

import static pl.skidam.automodpack.StaticVariables.LOGGER;
import static pl.skidam.automodpack.StaticVariables.VERSION;
import static pl.skidam.automodpack.config.ConfigTools.GSON;

public class ModpackUtils {


    // If update to modpack found, returns true else false
    public static Boolean isUpdate(Jsons.ModpackContentFields serverModpackContent, File modpackDir) {
        if ( modpackDir.toString() == null) {
            LOGGER.error("Modpack directory is null");
            return false;
        }

        if (serverModpackContent == null || serverModpackContent.list == null) {
            LOGGER.error("Server modpack content list is null");
            return false;
        }

        // get client modpack content
        File clientModpackContentFile = ModpackContentTools.getModpackContentFile(modpackDir);
        if (clientModpackContentFile != null && clientModpackContentFile.exists()) {

            Jsons.ModpackContentFields clientModpackContent = ConfigTools.loadConfig(clientModpackContentFile, Jsons.ModpackContentFields.class);

            if (clientModpackContent == null) {
                return true;
            }

            if (clientModpackContent.modpackHash == null) {
                LOGGER.error("Modpack hash is null");
                return true;
            }

            if (clientModpackContent.modpackHash.equals(serverModpackContent.modpackHash)) {
                LOGGER.info("Modpack hash is the same as server modpack hash");
                return false;
            }

            else {
                LOGGER.warn("Modpack hash is different than server modpack hash");
                return true;
            }
        } else {
            return true;
        }
    }

    public static void copyModpackFilesFromModpackDirToRunDir(File modpackDir, Jsons.ModpackContentFields serverModpackContent) throws IOException {
        List<Jsons.ModpackContentFields.ModpackContentItems> contents = serverModpackContent.list;

        for (Jsons.ModpackContentFields.ModpackContentItems contentItem : contents) {
            String fileName = contentItem.file;
            File sourceFile = new File(modpackDir + File.separator + fileName);

            if (sourceFile.exists()) {
                File destinationFile = new File("." + fileName);

                if (destinationFile.exists()) {
                    CustomFileUtils.forceDelete(destinationFile, false);
                }

                CustomFileUtils.copyFile(sourceFile, destinationFile);
//                LOGGER.info("Copied " + fileName + " to running directory");
            }
        }
    }


    public static void copyModpackFilesFromRunDirToModpackDir(File modpackDir, Jsons.ModpackContentFields serverModpackContent) throws Exception {
        List<Jsons.ModpackContentFields.ModpackContentItems> contents = serverModpackContent.list;

        for (Jsons.ModpackContentFields.ModpackContentItems contentItem : contents) {
            File sourceFile = new File("./" + contentItem.file);

            if (sourceFile.exists()) {

                // check hash
                String serverChecksum = contentItem.hash;
                String localChecksum = CustomFileUtils.getHashWithRetry(sourceFile, "SHA-256");

                if (!serverChecksum.equals(localChecksum) && !contentItem.isEditable) {
                    continue;
                }

                File destinationFile = new File(modpackDir + File.separator + contentItem.file);

                if (destinationFile.exists()) {
                    CustomFileUtils.forceDelete(destinationFile, false);
                }

                CustomFileUtils.copyFile(sourceFile, destinationFile);
//                LOGGER.info("Copied " + sourceFile.getName() + " to modpack directory");
            }
        }
    }

    public static Jsons.ModpackContentFields getServerModpackContent(String link) {
        try {
            HttpRequest getContent = HttpRequest.newBuilder()
                    .timeout(Duration.ofSeconds(3))
                    .setHeader("Minecraft-Username", MinecraftUserName.get())
                    .setHeader("User-Agent", "github/skidamek/automodpack/" + VERSION)
                    .uri(new URI(link))
                    .build();

            HttpClient httpClient = HttpClient.newHttpClient();
            HttpResponse<String> contentResponse = httpClient.send(getContent, HttpResponse.BodyHandlers.ofString());
            Jsons.ModpackContentFields serverModpackContent = GSON.fromJson(contentResponse.body(), Jsons.ModpackContentFields.class);

            if (serverModpackContent.list.size() < 1) {
                LOGGER.error("Modpack content is empty!");
                return null;
            }
            // check if modpackContent is valid/isn't malicious
            for (Jsons.ModpackContentFields.ModpackContentItems modpackContentItem : serverModpackContent.list) {
                String file = modpackContentItem.file.replace("\\", "/");
                String url = modpackContentItem.link.replace("\\", "/");
                if (file.contains("/../") || url.contains("/../")) {
                    LOGGER.error("Modpack content is invalid, it contains /../ in file name or url");
                    return null;
                }
            }

            return serverModpackContent;
        } catch (ConnectException e) {
            LOGGER.error("Couldn't connect to modpack server " + link);
        } catch (Exception e) {
            LOGGER.error("Error while getting server modpack content");
            e.printStackTrace();
        }
        return null;
    }
}
