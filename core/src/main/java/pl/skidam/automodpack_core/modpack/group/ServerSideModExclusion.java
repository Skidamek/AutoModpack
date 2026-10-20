package pl.skidam.automodpack_core.modpack.group;

/** Best-effort drop of jars a joining client must not run. */
public enum ServerSideModExclusion {
	DISABLED,
	METADATA,
	PLATFORMS;

	/** Drop Fabric {@code environment: server} and Forge {@code IGNORE_SERVER_VERSION}. */
	public boolean dropsJarMetadata() {
		return this != DISABLED;
	}

	/** Ask Modrinth about leftover universal jars for {@code dedicated_server_only}. */
	public boolean asksPlatforms() {
		return this == PLATFORMS;
	}

	public static ServerSideModExclusion orDefault(ServerSideModExclusion value) {
		return value == null ? METADATA : value;
	}
}
