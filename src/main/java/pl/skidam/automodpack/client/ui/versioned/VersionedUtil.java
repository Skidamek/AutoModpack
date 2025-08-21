package pl.skidam.automodpack.client.ui.versioned;

import java.util.concurrent.Executor;

//try to create an util for more versions?
public class VersionedUtil {
    public static Executor getMainWorkerExecutor() {
        /*? if >=1.18 {*/
        return net.minecraft.Util.getMainWorkerExecutor();
        /*?} else {*/
        /*return net.minecraft.util.Util.getMainWorkerExecutor();*/
        /*?}*/
    }
}