package pl.skidam.automodpack_core.loader;

import java.net.SocketAddress;

public interface GameCallService {
	/** {@code id} and {@code playerName} are the exact identity a login presented; implementations must authorize that identity as-is, never re-derive its parts from caches. */
	boolean isPlayerAuthorized(SocketAddress address, String id, String playerName);
}
