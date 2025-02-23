package pl.skidam.automodpack.loader;

import com.mojang.authlib.GameProfile;
import net.minecraft.util.UserCache;
import pl.skidam.automodpack.init.Common;
import pl.skidam.automodpack_core.loader.GameCallService;

import java.net.SocketAddress;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class GameCall implements GameCallService {

    @Override
    public boolean canPlayerJoin(SocketAddress address, String id) {
        UUID uuid = UUID.fromString(id);
        String playerName = "Player"; // mock name, name matters less than UUID anyway
        GameProfile profile = new GameProfile(uuid, playerName);

        UserCache userCache = Common.server.getUserCache();
        if (userCache != null) {
            profile = userCache.getByUuid(uuid).orElse(profile);
        }

        if (Common.server == null) {
            LOGGER.error("Server is null?");
            return true;
        }

        AtomicBoolean canJoin = new AtomicBoolean(false);
        GameProfile finalProfile = profile;
        Common.server.submitAndJoin(() -> canJoin.set(Common.server.getPlayerManager().checkCanJoin(address, finalProfile) == null));

        return canJoin.get();
    }
}
