package pl.skidam.automodpack.modpack;

import com.mojang.authlib.GameProfile;

import java.net.SocketAddress;

import static pl.skidam.automodpack.init.Common.server;

public class GameHelpers {

    // Simpler version of `PlayerManager.checkCanJoin`
    public static boolean isPlayerAuthorized(SocketAddress address, GameProfile profile) {
        var playerManager = server.getPlayerManager();
        if (playerManager.getUserBanList().contains(profile)) {
            return false;
        } else if (!playerManager.isWhitelisted(profile)) {
            return false;
        } else if (playerManager.getIpBanList().isBanned(address)) {
            return false;
        }

        return true;
    }
}
