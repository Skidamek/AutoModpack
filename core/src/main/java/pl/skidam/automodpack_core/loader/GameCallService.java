package pl.skidam.automodpack_core.loader;

import java.net.SocketAddress;

public interface GameCallService {
    boolean isPlayerAuthorized(SocketAddress address, String id);
}