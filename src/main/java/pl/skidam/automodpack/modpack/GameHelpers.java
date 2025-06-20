package pl.skidam.automodpack.modpack;

import com.mojang.authlib.GameProfile;
import java.net.SocketAddress;
import java.util.UUID;
import net.minecraft.server.players.GameProfileCache;
import net.minecraft.server.players.PlayerList;

import static pl.skidam.automodpack.init.Common.server;

public class GameHelpers {

    // Simpler version of `PlayerManager.checkCanJoin`
    public static boolean isPlayerAuthorized(SocketAddress address, GameProfile profile) {
        var playerManager = server.getPlayerList();
        if (playerManager.getBans().isBanned(profile)) {
            return false;
        }
        if (!playerManager.isWhiteListed(profile)) {
            return false;
        }
        if (playerManager.getIpBans().isBanned(address)) {
            return false;
        }

        return true;
    }

    // Method to get GameProfile from UUID with accounting for a fact that this player may not be on the server right now
    public static GameProfile getPlayerProfile(String id) {
        UUID uuid = UUID.fromString(id);
        String playerName = "Player"; // mock name, name matters less than UUID anyway
        GameProfile profile = new GameProfile(uuid, playerName);

        GameProfileCache userCache = server.getProfileCache();
        if (userCache != null) {
            profile = userCache.get(uuid).orElse(profile);
        }

        return profile;
    }
}
