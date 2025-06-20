package pl.skidam.automodpack.networking;

import pl.skidam.automodpack.init.Common;

import static pl.skidam.automodpack_core.GlobalVariables.MOD_ID;

import net.minecraft.resources.ResourceLocation;

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

    public static ResourceLocation getIdentifier(LoginNetworkingIDs ID) {
        return Common.id(ID.toString().toLowerCase());
    }

    public static Integer getByKey(ResourceLocation key) {
        if (key.getNamespace().equalsIgnoreCase(MOD_ID)) {
            for (var ID : LoginNetworkingIDs.values()) {
                if (ID.name().equalsIgnoreCase(key.getPath())) {
                    return ID.getValue();
                }
            }
        }
        return null;
    }

    public static ResourceLocation getByValue(int value) {
        for (var ID : LoginNetworkingIDs.values()) {
            if (ID.getValue() == value) {
                return getIdentifier(ID);
            }
        }
        return null;
    }
}
