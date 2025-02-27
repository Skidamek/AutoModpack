package pl.skidam.automodpack.modpack;

import com.mojang.authlib.GameProfile;
import net.minecraft.util.UserCache;

import java.net.SocketAddress;
import java.util.UUID;

import static pl.skidam.automodpack.init.Common.server;

public class GameHelpers {

    // Simpler version of `PlayerManager.checkCanJoin`
    public static boolean isPlayerAuthorized(SocketAddress address, GameProfile profile) {
        var playerManager = server.getPlayerManager();
        if (playerManager.getUserBanList().contains(profile)) {
            return false;
        }
        if (!playerManager.isWhitelisted(profile)) {
            return false;
        }
        if (playerManager.getIpBanList().isBanned(address)) {
            return false;
        }

        return true;
    }

    // Method to get GameProfile from UUID with accounting for a fact that this player may not be on the server right now
    public static GameProfile getPlayerProfile(String id) {
        UUID uuid = UUID.fromString(id);
        String playerName = "Player"; // mock name, name matters less than UUID anyway
        GameProfile profile = new GameProfile(uuid, playerName);

        UserCache userCache = server.getUserCache();
        if (userCache != null) {
            profile = userCache.getByUuid(uuid).orElse(profile);
        }

        return profile;
    }
}
