package pl.skidam.automodpack.modpack;

import com.mojang.authlib.GameProfile;
import java.net.SocketAddress;
import java.util.UUID;

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
        if (server.isSameThread()) {
            return checkPlayerAuthorizedInternal(address, profile);
        }

        return server.submit(() -> checkPlayerAuthorizedInternal(address, profile)).join();
    }

    private static boolean checkPlayerAuthorizedInternal(SocketAddress address, GameProfile profile) {
        var playerManager = server.getPlayerList();
        var playerId = /*? if >= 1.21.9 {*/new NameAndId(profile);/*?} else {*//*profile;*//*?}*/
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

    // Method to get GameProfile from UUID with accounting for a fact that this player may not be on the server right now
    public static GameProfile getPlayerProfile(String id) {
        UUID uuid = UUID.fromString(id);
        String playerName = "Player"; // mock name, name matters less than UUID anyway
        if (server.isSameThread()) {
            return getProfile(uuid, playerName);
        }

        return server.submit(() -> getProfile(uuid, playerName)).join();
    }

    private static GameProfile getProfile(UUID uuid, String playerName) {
        /*? if >= 1.21.9 {*/
        NameAndId nameAndId = new NameAndId(uuid, playerName);
        UserNameToIdResolver userCache = server.services().nameToIdCache();
        nameAndId = userCache.get(uuid).orElse(nameAndId);
        return new GameProfile(nameAndId.id(), nameAndId.name());
        /*?} else {*/
        /*GameProfile profile = new GameProfile(uuid, playerName);
        GameProfileCache userCache = server.getProfileCache();
        if (userCache != null) profile = userCache.get(uuid).orElse(profile);
        return profile;
        *//*?}*/
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
