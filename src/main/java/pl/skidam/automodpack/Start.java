package pl.skidam.automodpack;

import pl.skidam.automodpack.Modpack.Modpack;

public class Start implements Runnable {
    @Override
    public void run() {
        // 5 sec delay
        wait(5000);

        new Thread(new SelfUpdater()).start();

        new Thread(new Modpack()).start();
    }

    public static void wait(int ms) {
        try
        {
            Thread.sleep(ms);
        }
        catch(InterruptedException ex)
        {
            Thread.currentThread().interrupt();
        }
    }
}
