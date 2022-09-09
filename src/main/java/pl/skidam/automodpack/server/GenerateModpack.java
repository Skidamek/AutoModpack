package pl.skidam.automodpack.server;

import net.fabricmc.loader.api.FabricLoader;
import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.utils.JarUtilities;
import pl.skidam.automodpack.utils.Zipper;
import pl.skidam.automodpack.utils.GenerateContentList;

import java.io.*;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

import static org.apache.commons.lang3.ArrayUtils.contains;
import static pl.skidam.automodpack.AutoModpackMain.*;
import static pl.skidam.automodpack.utils.JarUtilities.correctName;
import static pl.skidam.automodpack.AutoModpackServer.*;

public class GenerateModpack {

    public GenerateModpack() { // TODO optimization

        long beforeModpackSize = -1;
        if (out.exists()) {
            beforeModpackSize = out.length();
        }

        if (!Config.SYNC_MODS) {
            autoExcludeMods();
            clientMods();
        }

        if (Config.SYNC_MODS) {
            LOGGER.info("Synchronizing mods from server to modpack");

            oldMods = modpackModsDir.list();
            deleteAllMods();
            cloneMods();
            autoExcludeMods();
            clientMods();
            newMods = modpackModsDir.list();

            // client mods list
            clientMods = modpackClientModsDir.list();

            // Changelog generation
            SimpleDateFormat ft = new SimpleDateFormat("yyyy-MM-dd");
            String date = ft.format(new Date());

            // Check how many files is in the changelogsDir containing the date
            String[] changelogs = changelogsDir.list();
            int changelogsCount = 1;
            assert changelogs != null;
            for (String changelog : changelogs) {
                if (changelog.contains(date)) {
                    changelogsCount++;
                }
            }

            // Create changelog file
            File changelog = new File(changelogsDir + "/" + "changelog-" + date + "-" + changelogsCount + ".txt");
            try {
                changelog.createNewFile();
            } catch (IOException e) {
                e.printStackTrace();
            }

            assert newMods != null;
            for (String mod : newMods) {
                if (!contains(oldMods, mod)) {
                    // Added mod
                    try {
                        FileUtils.writeStringToFile(changelog, " + " + mod + "\n", Charset.defaultCharset(), true);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            // Compare new to old mods and generate delmods.txt
            assert oldMods != null;
            for (String mod : oldMods) {
                if (!contains(newMods, mod) && !contains(clientMods, mod) && !mod.equals(correctName)) { // fix to #34
                    try {
                        // Check if mod is not already in delmods.txt
                        if (!FileUtils.readLines(modpackDeleteTxt, Charset.defaultCharset()).contains(mod)) {
                            LOGGER.info("Writing " + mod + " to delmods.txt");
                            FileUtils.writeStringToFile(modpackDeleteTxt, mod + "\n", Charset.defaultCharset(), true);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    // Deleted mod
                    try {
                        FileUtils.writeStringToFile(changelog, " - " + mod + "\n", Charset.defaultCharset(), true);
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                }
            }

            // Check if changelog is empty
            try {
                if (FileUtils.readLines(changelog, Charset.defaultCharset()).isEmpty()) {
                    changelog.delete();
                }
            } catch (IOException e) {
                e.printStackTrace();
            }

            // Check if in delmods.txt there are not mods which are in serverModsDir
            try {
                for (String delMod : FileUtils.readLines(modpackDeleteTxt, Charset.defaultCharset())) {
                    for (File file : Objects.requireNonNull(modsPath.toFile().listFiles())) {
                        if (file.getName().endsWith(".jar") && !file.getName().equals(correctName)) {
                            if (file.getName().equals(delMod)) {
                                LOGGER.error("Removing " + delMod + " from delmods.txt");
                                Scanner sc = new Scanner(modpackDeleteTxt);
                                StringBuilder sb = new StringBuilder();
                                while (sc.hasNextLine()) {
                                    sb.append(sc.nextLine()).append("\n");
                                }
                                sc.close();
                                String result = sb.toString();
                                result = result.replace(delMod, "");
                                PrintWriter writer = new PrintWriter(modpackDeleteTxt);
                                writer.append(result);
                                writer.flush();
                                writer.close();
                            }
                        }
                    }

                    if (delMod.equals(correctName)) {
                        Scanner sc = new Scanner(modpackDeleteTxt);
                        StringBuilder sb = new StringBuilder();
                        while (sc.hasNextLine()) {
                            sb.append(sc.nextLine()).append("\n");
                        }
                        sc.close();
                        String result = sb.toString();
                        result = result.replace(delMod, "");
                        PrintWriter writer = new PrintWriter(modpackDeleteTxt);
                        writer.append(result);
                        writer.flush();
                        writer.close();
                    }
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        // Remove blank lines from delmods.txt
        try {
            Scanner file = new Scanner(modpackDeleteTxt);
            PrintWriter writer = new PrintWriter(modpackDeleteTxt + ".tmp");

            while (file.hasNext()) {
                String line = file.nextLine();
                if (!line.isEmpty()) {
                    writer.write(line);
                    writer.write("\n");
                }
            }

            file.close();
            writer.close();
        } catch (Exception e) {
            e.printStackTrace();
        }

        FileUtils.deleteQuietly(modpackDeleteTxt);
        if (!new File(modpackDeleteTxt + ".tmp").renameTo(modpackDeleteTxt)) {
            LOGGER.error("Failed to rename " + modpackDeleteTxt + ".tmp to " + modpackDeleteTxt);
        }

        try {
            File blacklistModsTxt = new File("./AutoModpack/blacklistMods.txt");
            if (!FileUtils.readLines(blacklistModsTxt, Charset.defaultCharset()).isEmpty()) {
                for (String mod : FileUtils.readLines(blacklistModsTxt, Charset.defaultCharset())) {
                    try {
                        if (!mod.equals(correctName)) {
                            FileUtils.writeStringToFile(modpackDeleteTxt, mod + "\n", Charset.defaultCharset(), true);
                        }
                    } catch (IOException e) {
                        e.printStackTrace();
                    }
                    File modFile = new File(modpackModsDir + "/" + mod);
                    if (modFile.exists()) {
                        if (modFile.delete()) {
                            LOGGER.info("Excluded " + modFile.getName() + " from modpack");
                        } else {
                            LOGGER.error("Could not delete blacklisted mod " + modFile.getName() + " from modpack mods");
                        }
                    }
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        LOGGER.info("Creating modpack");
        if (modpackZip.exists()) {
            FileUtils.deleteQuietly(modpackZip);
        }

        try {
            new Zipper(modpackDir, modpackZip);
        } catch (IOException e) {
            e.printStackTrace();
            LOGGER.error(e.getMessage());
        }

        try {
            FileUtils.deleteQuietly(HostModpack.MODPACK_CONTENT_FILE.toFile());
            List<String> contentList = GenerateContentList.generateContentList(HostModpack.MODPACK_FILE.toFile());
            int size = contentList.size();
            String contentOfContentFile = FileUtils.readFileToString(HostModpack.MODPACK_CONTENT_FILE.toFile(), Charset.defaultCharset());
            for (int i = 0; i < size; i++) {
                if (!contentOfContentFile.contains(contentList.get(i))) {
                    FileUtils.writeStringToFile(HostModpack.MODPACK_CONTENT_FILE.toFile(), contentList.get(i) + "\n", Charset.defaultCharset(), true);
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }

        if (beforeModpackSize == -1) {
            LOGGER.error("beforeModpackSize is -1");
            try {
                Files.setAttribute(out.toPath(), "automodpack/time-edit", System.currentTimeMillis());
                Files.setAttribute(out.toPath(), "automodpack/size", out.length());
            } catch (Exception e) {
                e.printStackTrace();
            }
        } else if (beforeModpackSize != out.length()) {
            LOGGER.error("beforeModpackSize {} is different than new one {}", beforeModpackSize, out.length());
            try {
                Files.setAttribute(out.toPath(), "automodpack/time-edit", System.currentTimeMillis());
                Files.setAttribute(out.toPath(), "automodpack/size", out.length());
            } catch (Exception e) {
                e.printStackTrace();
            }
        }

        LOGGER.info("Modpack created");

        // TODO GDRIVE UPLOAD
//        if (Config.EXTERNAL_MODPACK_HOST.startsWith("https://drive.google.com/drive/")) {
//            LOGGER.warn("Uploading modpack to Google Drive");
//            try {
//                GoogleDriveUpload.uploadModpack();
//            } catch (IOException | GeneralSecurityException e) {
//                LOGGER.error("Failed upload modpack to Google Drive\n" + e);
//            }
//        }
    }
    private static void cloneMods() {
        for (File file : Objects.requireNonNull(modsPath.toFile().listFiles())) {
            if (file.getName().endsWith(".jar") && !file.getName().toLowerCase().contains("automodpack")) {
                try {
                    FileUtils.copyFileToDirectory(file, modpackModsDir);
                } catch (IOException e) {
                    LOGGER.error("Error while cloning mods from server to modpack");
                    e.printStackTrace();
                }
            }
        }
    }

    private static void autoExcludeMods() {
        if (!Config.AUTO_EXCLUDE_SERVER_SIDE_MODS) return;
        LOGGER.info("Excluding server-side mods from modpack");
        Collection modList = JarUtilities.getListOfModsIDS();

        // make for loop for each mod in modList
        for (Object mod : modList) {
            String modId = mod.toString().split(" ")[0];
            String environment = FabricLoader.getInstance().getModContainer(modId).isPresent() ? FabricLoader.getInstance().getModContainer(modId).get().getMetadata().getEnvironment().toString().toLowerCase() : "null";
            if (environment.equals("server")) {
                String modName = FabricLoader.getInstance().getModContainer(modId).isPresent() ? FabricLoader.getInstance().getModContainer(modId).get().getMetadata().getName() : "null";
                File serverSideModInModpack = new File(modpackModsDir + "/" + modName);
                if (serverSideModInModpack.exists()) {
                    FileUtils.deleteQuietly(serverSideModInModpack);
                    for (String oldMod : oldMods) { // log to console if this mod was in modpack before
                        if (oldMod.equals(serverSideModInModpack.getName())) {
                            LOGGER.info(modName + " is server-side mod and has been auto excluded from modpack");
                        }
                    }
                }
            }
        }
    }

    private static void clientMods() {
        for (File file : Objects.requireNonNull(modpackClientModsDir.listFiles())) {
            if (file.getName().endsWith(".jar") && !file.getName().toLowerCase().contains("automodpack")) {
                try {
                    FileUtils.copyFileToDirectory(file, modpackModsDir);
                } catch (IOException e) {
                    LOGGER.error("Error while cloning mods from client to modpack");
                    e.printStackTrace();
                }
            }
        }
    }

    private static void deleteAllMods() {
        for (File file : Objects.requireNonNull(modpackModsDir.listFiles())) {
            if (!file.delete()) {
                LOGGER.error("Error while deleting the file: " + file);
            }
        }
    }
}
