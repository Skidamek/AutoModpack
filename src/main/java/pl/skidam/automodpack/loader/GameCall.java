package pl.skidam.automodpack.loader;

import com.mojang.authlib.GameProfile;
import net.minecraft.util.UserCache;
import pl.skidam.automodpack.modpack.GameHelpers;
import pl.skidam.automodpack_core.loader.GameCallService;

import java.net.SocketAddress;
import java.util.UUID;

import static pl.skidam.automodpack.init.Common.server;
import static pl.skidam.automodpack_core.GlobalVariables.*;

public class GameCall implements GameCallService {

    @Override
    public boolean canPlayerJoin(SocketAddress address, String id) {
        UUID uuid = UUID.fromString(id);
        String playerName = "Player"; // mock name, name matters less than UUID anyway
        GameProfile profile = new GameProfile(uuid, playerName);

        UserCache userCache = server.getUserCache();
        if (userCache != null) {
            profile = userCache.getByUuid(uuid).orElse(profile);
        }

        if (server == null) {
            LOGGER.error("Server is null?");
            return true;
        }

        return GameHelpers.isPlayerAuthorized(address, profile);
    }
}
