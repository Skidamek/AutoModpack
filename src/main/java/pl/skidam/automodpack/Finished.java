package pl.skidam.automodpack;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class Finished {

    public static final Logger LOGGER = LoggerFactory.getLogger("AutoModpack");

    public Finished() {
        Thread.currentThread().setName("AutoModpack");
        Thread.currentThread().setPriority(10);

        LOGGER.info("AutoModpack -- Here you are!");

        new ToastExecutor(5);
    }
}
