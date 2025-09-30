package pl.skidam.automodpack.modpack;

import com.mojang.authlib.GameProfile;
import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

/*? if >= 1.21.9 {*/
import net.minecraft.server.players.NameAndId;
import net.minecraft.server.players.UserNameToIdResolver;
/*?} else {*/
/*import net.minecraft.server.players.GameProfileCache;
*//*?}*/

import static pl.skidam.automodpack.init.Common.server;

public class GameHelpers {

    // Simpler version of `PlayerManager.checkCanJoin`
    public static boolean isPlayerAuthorized(SocketAddress address, GameProfile profile) {
        AtomicBoolean isAuthorized = new AtomicBoolean(false);
        server.submit(() -> {
            var playerManager = server.getPlayerList();
            var playerId = /*? if >= 1.21.9 {*/new NameAndId(profile);/*?} else {*//*profile;*//*?}*/
            if (playerManager.getBans().isBanned(playerId)) {
                return;
            }
            if (!playerManager.isWhiteListed(playerId)) {
                return;
            }
            if (playerManager.getIpBans().isBanned(address)) {
                return;
            }

            isAuthorized.set(true);
        }).join();

        return isAuthorized.get();
    }

    /*? if >= 1.21.9 {*/
    public static String getPlayerName(NameAndId nameAndId) {
        return nameAndId.name();
    }
    /*?}*/

    public static String getPlayerName(GameProfile profile) {
        /*? if >= 1.21.9 {*/
        return profile.name();
        /*?} else {*/
        /*return profile.getName();
        *//*?}*/
    }

    /*? if >= 1.21.9 {*/
    public static UUID getPlayerUUID(NameAndId nameAndId) {
        return nameAndId.id();
    }
    /*?}*/

    public static UUID getPlayerUUID(GameProfile profile) {
        /*? if >= 1.21.9 {*/
        return profile.id();
        /*?} else {*/
        /*return profile.getId();
        *//*?}*/
    }

    /*? if >= 1.21.9 {*/
    // Method to get GameProfile from UUID with accounting for a fact that this player may not be on the server right now
    public static GameProfile getPlayerProfile(String id) {
        UUID uuid = UUID.fromString(id);
        String playerName = "Player"; // mock name, name matters less than UUID anyway
        NameAndId nameAndId = new NameAndId(uuid, playerName);

        UserNameToIdResolver userCache = server.services().nameToIdCache();
        nameAndId = userCache.get(uuid).orElse(nameAndId);

        return new GameProfile(nameAndId.id(), nameAndId.name());
    }
    /*?} else {*/
    /*public static GameProfile getPlayerProfile(String id) {
        UUID uuid = UUID.fromString(id);
        String playerName = "Player"; // mock name, name matters less than UUID anyway
        GameProfile profile = new GameProfile(uuid, playerName);

        GameProfileCache userCache = server.getProfileCache();
        if (userCache != null) {
            profile = userCache.get(uuid).orElse(profile);
        }

        return profile;
    }
    *//*?}*/
}
