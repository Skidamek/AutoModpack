package pl.skidam.automodpack_core.utils;

/** Compatibility rules for the unchanged AutoModpack login handshake. */
public final class AutoModpackProtocol {

    private static final int LEGACY_COMPATIBLE_MAJOR = 4;
    private static final int LEGACY_COMPATIBLE_MINOR = 0;

    private AutoModpackProtocol() {
    }

    /** Returns whether the client version is valid for this server version. */
    public static boolean acceptsClient(String serverVersion, String clientVersion) {
        return (serverVersion != null && serverVersion.equals(clientVersion))
                || (isLegacyCompatibleVersion(serverVersion)
                && isLegacyCompatibleVersion(clientVersion));
    }

    /**
     * Returns the version to put in {@code amVersion}. Stable 4.0.x clients
     * advertise the server's version so old servers accept newer clients.
     */
    public static String getHandshakeVersion(String serverVersion, String clientVersion) {
        if (isLegacyCompatibleVersion(serverVersion)
                && isLegacyCompatibleVersion(clientVersion)) {
            return serverVersion;
        }

        return clientVersion;
    }

    /**
     * Returns whether a version belongs to the unchanged legacy 4.0.x family.
     */
    public static boolean isLegacyCompatibleVersion(String version) {
        try {
            SemanticVersion parsed = SemanticVersion.parse(version);
            return parsed.isStable()
                    && parsed.major() == LEGACY_COMPATIBLE_MAJOR
                    && parsed.minor() == LEGACY_COMPATIBLE_MINOR;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }
}
