package pl.skidam.automodpack_core.loader;

import java.net.SocketAddress;

public class NullGameCall implements GameCallService {
    @Override
    public boolean canPlayerJoin(SocketAddress address, String id) {
        return true;
    }
}
