package pl.skidam.automodpack.networking;

import net.minecraft.util.Identifier;
import pl.skidam.automodpack.init.Common;

import static pl.skidam.automodpack_core.GlobalVariables.MOD_ID;

public enum LoginNetworkingIDs {
    // AutoModpack login query id's
    HANDSHAKE(-100),
    DATA(-101);

    private final int value;

    LoginNetworkingIDs(int value) {
        this.value = value;
    }

    public int getValue() {
        return value;
    }

    public static Identifier getIdentifier(LoginNetworkingIDs ID) {
        return Common.id(ID.toString().toLowerCase());
    }

    public static Integer getByKey(Identifier key) {
        if (key.getNamespace().equalsIgnoreCase(MOD_ID)) {
            for (var ID : LoginNetworkingIDs.values()) {
                if (ID.name().equalsIgnoreCase(key.getPath())) {
                    return ID.getValue();
                }
            }
        }
        return null;
    }

    public static Identifier getByValue(int value) {
        for (var ID : LoginNetworkingIDs.values()) {
            if (ID.getValue() == value) {
                return getIdentifier(ID);
            }
        }
        return null;
    }
}
