package pl.skidam.automodpack.utils;

public class Wait {
    public Wait(int ms) {
        try {
            Thread.sleep(ms);
        }
        catch(InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }
}
