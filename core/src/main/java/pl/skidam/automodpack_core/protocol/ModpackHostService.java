package pl.skidam.automodpack_core.protocol;

import pl.skidam.automodpack_core.utils.ObservableMap;

import java.nio.file.Path;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface ModpackHostService {
    boolean start();

    boolean stop();

    boolean isRunning();

    void setPaths(ObservableMap<String, Path> paths);

    void removePaths(ObservableMap<String, Path> paths);

    Optional<Path> getPath(String hash);

    Map<String, Integer> getConnectionCountsByEndpoint();

    String getIrohEndpointId();

    default List<InetSocketAddress> getIrohDirectAddresses() {
        return List.of();
    }

    boolean isIrohEnabled();

    default boolean shouldHost() {
        return isRunning();
    }
}
