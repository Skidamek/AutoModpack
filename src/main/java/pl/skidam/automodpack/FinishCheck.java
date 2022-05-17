package pl.skidam.automodpack;

import static pl.skidam.automodpack.AutoModpack.AutoModpackUpdated;
import static pl.skidam.automodpack.AutoModpack.ModpackUpdated;

public class FinishCheck implements Runnable {

    @Override
    public void run() {
        AutoModpack.Checking = true;
        while (true) {
            if (AutoModpackUpdated != null && ModpackUpdated != null) {
                new Finished(true, AutoModpackUpdated, ModpackUpdated);
                AutoModpack.Checking = false;
                break;
            }

            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                throw new RuntimeException(e);
            }
        }
    }
}
