package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.Platform;

import java.io.File;

public class SetupFiles {
    public SetupFiles() {
        File AMdir = new File("./automodpack/");
        // Check if AutoModpack path exists
        if (!AMdir.exists()) {
            AMdir.mkdirs();
        }

        if (Platform.getEnvironmentType().equals("SERVER")) {
            server();
        }

        if (Platform.getEnvironmentType().equals("CLIENT")) {
            client();
        }
    }

    private void server() {

    }

    private void client() {
        File modpacks = new File("./automodpack/modpacks/");
        if (!modpacks.exists()) {
            modpacks.mkdirs();
        }
    }
}