package pl.skidam.automodpack.client;

import org.apache.commons.io.FileDeleteStrategy;
import org.apache.commons.io.FileUtils;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import static org.apache.commons.lang3.ArrayUtils.add;
import static pl.skidam.automodpack.AutoModpackMain.LOGGER;

public class DeleteTrashedMods {

    public DeleteTrashedMods() {

        // Read ./AutoModpack/trashed-mods.txt and add lines from it to array
        String trashedModsTxt = "./AutoModpack/trashed-mods.txt";
        if (new File (trashedModsTxt).exists()) {
            String[] trashedModsNames = new String[0];

            try {
                FileReader fr = new FileReader(trashedModsTxt);
                BufferedReader br = new BufferedReader(fr);
                String line;
                while ((line = br.readLine()) != null) {
                    trashedModsNames = add(trashedModsNames, line);
                }
                br.close();
                fr.close();
            } catch (Exception e) {
                LOGGER.error("Could not read trashed-mods.txt file\n" + e.getMessage());
            }


            // For trashedModsNames array, delete file with same name in ./mods/ folder
            for (String trashedModName : trashedModsNames) {
                File trashedModFile = new File("./mods/" + trashedModName);
                if (trashedModFile.exists()) {
                    try {
                        FileDeleteStrategy.FORCE.delete(trashedModFile);
                        LOGGER.info("Successfully deleted trashed mod: " + trashedModName);
                    } catch (Exception ignored) {
                    }
                }
            }

            // Delete trashed-mods.txt file
            FileUtils.deleteQuietly(new File(trashedModsTxt));
        }
    }
}