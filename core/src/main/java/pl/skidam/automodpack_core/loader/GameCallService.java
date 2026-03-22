package pl.skidam.automodpack_core.loader;

import java.net.SocketAddress;

public interface GameCallService {
    boolean isPlayerAuthorized(SocketAddress address, String id);

    default boolean isPlayerAuthorized(String id) {
        return isPlayerAuthorized(null, id);
    }
}
