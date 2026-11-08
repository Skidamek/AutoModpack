package pl.skidam.automodpack.loader;

import static pl.skidam.automodpack.init.Common.server;

import java.net.SocketAddress;
import java.util.UUID;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack.modpack.GameHelpers;
import pl.skidam.automodpack_core.loader.GameCallService;

public class GameCall implements GameCallService {
	private static final Logger LOGGER = LogManager.getLogger(GameCall.class);

	@Override
	public boolean isPlayerAuthorized(SocketAddress address, String id, String playerName) {
		if (server == null) {
			LOGGER.error("Server is null; rejecting authorization for {}", id);
			return false;
		}
		try {
			return GameHelpers.isPlayerAuthorized(address, UUID.fromString(id), playerName);
		} catch (IllegalArgumentException e) {
			LOGGER.error("Rejecting a secret bound to an unreadable player id: {}", id);
			return false;
		}
	}
}
