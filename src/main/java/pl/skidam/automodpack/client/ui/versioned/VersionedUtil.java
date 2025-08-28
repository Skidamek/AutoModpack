package pl.skidam.automodpack.client.ui.versioned;

import java.util.concurrent.Executor;

public class VersionedUtil {
    public static Executor getMainWorkerExecutor() {
        /*? if >=1.19 {*/
        return net.minecraft.Util.backgroundExecutor();
        /*?} else {*/
        return net.minecraft.Util.ioPool();
        /*?}*/
    }
}