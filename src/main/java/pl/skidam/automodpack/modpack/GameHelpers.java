package pl.skidam.automodpack.modpack;

import com.mojang.authlib.GameProfile;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import net.minecraft.server.players.GameProfileCache;

import static pl.skidam.automodpack.init.Common.server;

public class GameHelpers {

    // Simpler version of `PlayerManager.checkCanJoin`
    public static boolean isPlayerAuthorized(SocketAddress address, GameProfile profile) {
        AtomicBoolean isAuthorized = new AtomicBoolean(false);
        server.submit(() -> {
            var playerManager = server.getPlayerList();
            if (playerManager.getBans().isBanned(profile)) {
                return;
            }
            if (!playerManager.isWhiteListed(profile)) {
                return;
            }
            if (playerManager.getIpBans().isBanned(address)) {
                return;
            }

            isAuthorized.set(true);
        }).join();

        return isAuthorized.get();
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
