package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpackClient;

public class Error {

    public Error() {

        AutoModpackClient.AutoModpackUpdated = null;
        AutoModpackClient.ModpackUpdated = null;

        new ToastExecutor(5);

        AutoModpackClient.LOGGER.error("Error! Download server may be down or AutoModpack is wrongly configured!");
        AutoModpackClient.LOGGER.error("Error! Download server may be down or AutoModpack is wrongly configured!");
        AutoModpackClient.LOGGER.error("Error! Download server may be down or AutoModpack is wrongly configured!");
    }
}
