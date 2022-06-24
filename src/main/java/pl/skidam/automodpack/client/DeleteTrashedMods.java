package pl.skidam.automodpack.client;

import org.apache.commons.io.FileDeleteStrategy;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileReader;

import static org.apache.commons.lang3.ArrayUtils.add;
import static pl.skidam.automodpack.AutoModpackMain.LOGGER;

public class DeleteTrashedMods {

    public DeleteTrashedMods() {

        // read ./AutoModpack/modpack-link.txt and add lines from it to array
        String[] trashedModsNames = new String[0];
        try {
            FileReader fr = new FileReader("./AutoModpack/modpack-link.txt");
            BufferedReader br = new BufferedReader(fr);
            String line;
            while ((line = br.readLine()) != null) {
                trashedModsNames = add(trashedModsNames, line);
            }
        } catch (Exception e) { // ignore
        }

        // for trashedModsNames array, delete file with same name in ./mods/ folder
        for (String trashedModName : trashedModsNames) {
            File trashedModFile = new File("./mods/" + trashedModName);
            if (trashedModFile.exists()) {
                try {
                    if (trashedModFile.exists()) {
                        FileDeleteStrategy.FORCE.delete(trashedModFile);
                    }
                } catch (Exception e) { // ignore
                }
            }
            LOGGER.info("Successfully deleted trashed mod: " + trashedModName);
        }

        // delete trashedModFile
        new File("./AutoModpack/TrashMod/").delete();
    }
}
