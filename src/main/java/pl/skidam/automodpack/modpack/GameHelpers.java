package pl.skidam.automodpack.modpack;

import com.mojang.authlib.GameProfile;
import java.net.SocketAddress;
import java.util.UUID;

/*? if >= 1.21.9 {*/
import net.minecraft.server.players.NameAndId;
/*?}*/

import static pl.skidam.automodpack.init.Common.server;

public class GameHelpers {

	// Simpler version of `PlayerManager.checkCanJoin`; always runs against the exact identity the login presented
	public static boolean isPlayerAuthorized(SocketAddress address, UUID playerUuid, String playerName) {
		if (server.isSameThread()) {
			return checkPlayerAuthorizedInternal(address, playerUuid, playerName);
		}

		return server.submit(() -> checkPlayerAuthorizedInternal(address, playerUuid, playerName)).join();
	}

	private static boolean checkPlayerAuthorizedInternal(SocketAddress address, UUID playerUuid, String playerName) {
		var playerManager = server.getPlayerList();
		var playerId = /*? if >= 1.21.9 {*/new NameAndId(playerUuid, playerName);/*?} else {*//*new GameProfile(playerUuid, playerName);*//*?}*/
		if (playerManager.getBans().isBanned(playerId)) {
			return false;
		}
		if (!playerManager.isWhiteListed(playerId)) {
			return false;
		}
		if (playerManager.getIpBans().isBanned(address)) {
			return false;
		}

		return true;
	}

	public static String getPlayerName(GameProfile profile) {
		/*? if >= 1.21.9 {*/
		return profile.name();
		/*?} else {*/
		/*return profile.getName();
		*//*?}*/
	}

	public static UUID getPlayerUUID(GameProfile profile) {
		/*? if >= 1.21.9 {*/
		return profile.id();
		/*?} else {*/
		/*return profile.getId();
		*//*?}*/
	}
}
