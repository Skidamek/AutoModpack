package pl.skidam.automodpack;

import org.apache.commons.io.FileDeleteStrategy;

import java.io.File;
import java.io.IOException;

public class Finished {

    public Finished() {
        Thread.currentThread().setName("AutoModpack");
        Thread.currentThread().setPriority(10);

        // Delete the file
        try {
            FileDeleteStrategy.FORCE.delete(new File("./delmods/"));
        } catch (IOException e) { // ignore it
        }

        System.out.println("AutoModpack -- Here you are!");

        new ToastExecutor(5);
    }
}
