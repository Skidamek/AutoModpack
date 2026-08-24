package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.MC_VERSION;

import pl.skidam.mcholepunch.MinecraftLoginStartLayout;
import pl.skidam.mcholepunch.MinecraftProtocol;

public enum ModpackConnectionMode {
	HOLEPUNCH,
	MAGIC_PACKET,
	DIRECT;

	public static ModpackConnectionMode defaultFor() {
		if (MC_VERSION == null || MC_VERSION.isBlank()) return HOLEPUNCH;
		return isHolepunchAvailable(MC_VERSION) ? HOLEPUNCH : MAGIC_PACKET;
	}

	public static boolean isHolepunchAvailable(String minecraftVersion) {
		if (minecraftVersion == null || minecraftVersion.isBlank()) return false;
		try {
			return MinecraftProtocol.forMinecraftVersion(minecraftVersion).loginStartLayout() != MinecraftLoginStartLayout.USERNAME_ONLY;
		} catch (IllegalArgumentException e) {
			return false;
		}
	}
}
