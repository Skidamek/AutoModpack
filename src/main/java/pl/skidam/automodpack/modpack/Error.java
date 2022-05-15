package pl.skidam.automodpack.modpack;

import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.ToastExecutor;

public class Error {

    public Error() {

        AutoModpack.AutoModpackUpdated = null;
        AutoModpack.ModpackUpdated = null;

        new ToastExecutor(8);

        AutoModpack.LOGGER.error("Error! Download server may be down or AutoModpack is wrongly configured!");
        AutoModpack.LOGGER.error("Error! Download server may be down or AutoModpack is wrongly configured!");
        AutoModpack.LOGGER.error("Error! Download server may be down or AutoModpack is wrongly configured!");
    }
}
