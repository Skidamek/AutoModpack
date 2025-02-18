package pl.skidam.automodpack_core.loader;

import java.net.SocketAddress;

public interface GameCallService {
    boolean canPlayerJoin(SocketAddress address, String id);
}