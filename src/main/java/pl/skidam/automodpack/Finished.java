package pl.skidam.automodpack;

import org.apache.commons.io.FileDeleteStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;

public class Finished {

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");

    public Finished() {
        Thread.currentThread().setName("AutoModpack");
        Thread.currentThread().setPriority(10);

        // Delete the file
        try {
            FileDeleteStrategy.FORCE.delete(new File("./delmods/"));
        } catch (IOException e) { // ignore it
        }

        LOGGER.info("AutoModpack -- Here you are!");

        new ToastExecutor(5);
    }
}
