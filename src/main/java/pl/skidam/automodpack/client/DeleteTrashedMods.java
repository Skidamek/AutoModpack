package pl.skidam.automodpack.client;

import org.apache.commons.io.FileDeleteStrategy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import static org.apache.commons.lang3.ArrayUtils.add;
import static pl.skidam.automodpack.AutoModpackMain.LOGGER;

public class DeleteTrashedMods {

    public DeleteTrashedMods() {

        // Read ./AutoModpack/trashed-mods.txt and add lines from it to array
        String[] trashedModsNames = new String[0];
        String trashedModsTxt = "./AutoModpack/trashed-mods.txt";
        try {
            FileReader fr = new FileReader(trashedModsTxt);
            BufferedReader br = new BufferedReader(fr);
            String line;
            while ((line = br.readLine()) != null) {
                trashedModsNames = add(trashedModsNames, line);
            }
            br.close();
            fr.close();
        } catch (Exception e) { // ignore
        }

        // For trashedModsNames array, delete file with same name in ./mods/ folder
        for (String trashedModName : trashedModsNames) {
            File trashedModFile = new File("./mods/" + trashedModName);
            if (trashedModFile.exists()) {
                try {
                    FileDeleteStrategy.FORCE.delete(trashedModFile);
                    LOGGER.info("Successfully deleted trashed mod: " + trashedModName);
                } catch (Exception e) { // ignore
                    e.printStackTrace();
                }
            }
        }
        // Delete trashed-mods.txt file
        try {
            FileDeleteStrategy.FORCE.delete(new File(trashedModsTxt));
            LOGGER.info("Successfully deleted trashed-mods.txt file");
        } catch (Exception e) { // ignore
        }
    }
}