package pl.skidam.automodpack_core.protocol;

public enum ModpackConnectionMode {
	HOLEPUNCH,
	MAGIC_PACKET,
	DIRECT;

	public static ModpackConnectionMode defaultFor() {
		return HOLEPUNCH;
	}
}
