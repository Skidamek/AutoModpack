package pl.skidam.automodpack.utils;

import net.fabricmc.api.EnvType;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import org.apache.commons.io.FileUtils;

import java.io.*;

import static pl.skidam.automodpack.AutoModpackMain.LOGGER;

public class GetMinecraftUserName {
    public static String getMinecraftUserName() {

        if (FabricLoader.getInstance().getEnvironmentType() == EnvType.SERVER) return null;

        File usernameFile = new File("AutoModpack/username.txt");

        if (MinecraftClient.getInstance() != null) {
            String username = MinecraftClient.getInstance().getSession().getUsername();
            try {
                if (!usernameFile.exists()) {
                    if (!usernameFile.createNewFile()) {
                        LOGGER.info("Can not create username file.");
                    }
                }

                FileWriter fw = new FileWriter(usernameFile);
                fw.flush();
                fw.write(username);
                fw.close();

            } catch (Exception e) {
                e.printStackTrace();
            }

            return username;

        } else if (usernameFile.exists()) {

            try {
                FileReader fr = new FileReader(usernameFile);
                BufferedReader br = new BufferedReader(fr);
                String username = br.readLine();
                br.close();
                fr.close();
                return "(" + username + ")";
            } catch (IOException e) {
                e.printStackTrace();
                return "(" + System.getProperty("user.name") + ")"; // 66
            }

        } else {
            return "(" + System.getProperty("user.name") + ")"; // lol    pov admin: reads console... Jan Kowalski downloading modpack xD
        }
    }
}