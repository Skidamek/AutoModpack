package pl.skidam.automodpack.loader;

import com.mojang.authlib.GameProfile;
import pl.skidam.automodpack.modpack.GameHelpers;
import pl.skidam.automodpack_core.loader.GameCallService;

import java.net.SocketAddress;

import static pl.skidam.automodpack.init.Common.server;
import static pl.skidam.automodpack_core.GlobalVariables.*;

public class GameCall implements GameCallService {

    @Override
    public boolean isPlayerAuthorized(SocketAddress address, String id) {
        var profile = GameHelpers.getPlayerProfile(id);

        if (server == null) {
            LOGGER.error("Server is null?");
            return true;
        }

        return GameHelpers.isPlayerAuthorized(address, profile);
    }
}
