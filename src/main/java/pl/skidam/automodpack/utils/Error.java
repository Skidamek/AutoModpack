package pl.skidam.automodpack.utils;

import pl.skidam.automodpack.AutoModpackClient;

public class Error {

    public Error() {

        AutoModpackClient.AutoModpackUpdated = "false";
        AutoModpackClient.ModpackUpdated = "false";
        AutoModpackClient.Checking = false;

        new ToastExecutor(5);

        AutoModpackClient.LOGGER.error("Error! Download server may be down, AutoModpack is wrongly configured or you just don't have internet connection!");
        AutoModpackClient.LOGGER.error("Error! Download server may be down, AutoModpack is wrongly configured or you just don't have internet connection!");
        AutoModpackClient.LOGGER.error("Error! Download server may be down, AutoModpack is wrongly configured or you just don't have internet connection!");
    }
}
