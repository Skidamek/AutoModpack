package pl.skidam.automodpack_core.modpack.group;

/** How a group decides that a jar is server-only and should stay off clients. */
public enum ServerSideModExclusion {
	DISABLED,
	METADATA,
	PLATFORMS;

	/** Drop jars whose own metadata declares a server environment. */
	public boolean dropsJarMetadata() {
		return this != DISABLED;
	}

	/** Ask Modrinth about leftover universal jars. */
	public boolean asksPlatforms() {
		return this == PLATFORMS;
	}

	public static ServerSideModExclusion orDefault(ServerSideModExclusion value) {
		return value == null ? METADATA : value;
	}
}
