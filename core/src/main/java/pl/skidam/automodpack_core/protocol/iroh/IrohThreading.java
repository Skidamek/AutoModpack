package pl.skidam.automodpack_core.protocol.iroh;

import java.util.concurrent.ThreadFactory;

public final class IrohThreading {
    private IrohThreading() {
    }

    public static ThreadFactory daemonThreadFactory(String name) {
        return runnable -> {
            Thread thread = new Thread(runnable, name);
            thread.setDaemon(true);
            return thread;
        };
    }
}
