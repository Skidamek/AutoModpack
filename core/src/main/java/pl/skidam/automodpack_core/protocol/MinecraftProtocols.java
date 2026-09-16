package pl.skidam.automodpack_core.protocol;

/**
 * Vanilla protocol numbers of the Minecraft versions AutoModpack ships for, sent in the holepunch
 * Handshake. The holepunch login dialect itself is version independent.
 */
public final class MinecraftProtocols {
	private MinecraftProtocols() {}

	public static int forVersion(String minecraftVersion) {
		return switch (minecraftVersion) {
			case "1.18.2" -> 758;
			case "1.19.2" -> 760;
			case "1.19.4" -> 762;
			case "1.20", "1.20.1" -> 763;
			case "1.20.2" -> 764;
			case "1.20.3", "1.20.4" -> 765;
			case "1.20.5", "1.20.6" -> 766;
			case "1.21", "1.21.1" -> 767;
			case "1.21.2", "1.21.3" -> 768;
			case "1.21.4" -> 769;
			case "1.21.5" -> 770;
			case "1.21.6", "1.21.7" -> 771;
			case "1.21.8" -> 772;
			case "1.21.9", "1.21.10" -> 773;
			case "1.21.11" -> 774;
			case "26.1", "26.1.1", "26.1.2" -> 775;
			case "26.2" -> 776;
			default -> throw new IllegalArgumentException("Unsupported Minecraft version: " + minecraftVersion);
		};
	}
}
