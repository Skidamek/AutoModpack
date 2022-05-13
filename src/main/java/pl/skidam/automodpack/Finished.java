package pl.skidam.automodpack;

public class Finished {

    public Finished() {
        Thread.currentThread().setPriority(10);

        AutoModpack.LOGGER.info("Here you are!");

        new ToastExecutor(7);
    }
}
