package pl.skidam.automodpack_core.loader;

import java.net.SocketAddress;

public class NullGameCall implements GameCallService {
    @Override
    public boolean isPlayerAuthorized(SocketAddress address, String id) {
        return true;
    }
}
