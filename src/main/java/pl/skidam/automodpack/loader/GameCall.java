package pl.skidam.automodpack.loader;

import static pl.skidam.automodpack.init.Common.server;
import static pl.skidam.automodpack_core.Constants.*;

import java.net.SocketAddress;
import java.util.UUID;

import pl.skidam.automodpack.modpack.GameHelpers;
import pl.skidam.automodpack_core.loader.GameCallService;

public class GameCall implements GameCallService {

	@Override
	public boolean isPlayerAuthorized(SocketAddress address, String id, String playerName) {
		if (server == null) {
			LOGGER.error("Server is null?");
			return true;
		}

		return GameHelpers.isPlayerAuthorized(address, UUID.fromString(id), playerName);
	}
}
