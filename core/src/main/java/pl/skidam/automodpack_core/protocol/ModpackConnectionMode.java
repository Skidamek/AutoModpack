package pl.skidam.automodpack_core.protocol;

public enum ModpackConnectionMode {
	HOLEPUNCH,
	MAGIC_PACKET,
	DIRECT;

	public static ModpackConnectionMode defaultFor(String minecraftVersion, String loader) {
		if (minecraftVersion == null || minecraftVersion.isBlank()) return MAGIC_PACKET;
		return isHolepunchAvailable(minecraftVersion, loader) ? HOLEPUNCH : MAGIC_PACKET;
	}

	public static boolean isHolepunchAvailable(String minecraftVersion, String loader) {
		String[] parts = minecraftVersion.split("\\.", 3);
		try {
			int major = Integer.parseInt(parts[0]);
			int minor = parts.length > 1 ? Integer.parseInt(parts[1]) : 0;
			int patch = parts.length > 2 ? Integer.parseInt(parts[2]) : 0;
			if (major > 1 || major == 1 && (minor > 21 || minor == 21 && patch >= 1)) return true;
			return major == 1 && minor == 20 && patch >= 1 && "fabric".equalsIgnoreCase(loader);
		} catch (NumberFormatException e) {
			return false;
		}
	}
}
