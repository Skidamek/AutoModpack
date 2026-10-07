package pl.skidam.automodpack_core.loader;

import java.util.Locale;

/** The side a mod's own metadata declares it runs on, as a fact about that file rather than about this JVM. */
public enum ModEnvironment {
	CLIENT, SERVER, UNIVERSAL, UNKNOWN;

	/** The side declared for a metadata string, or {@link #UNKNOWN} when the file declares none. */
	public static ModEnvironment of(String declared) {
		if (declared == null || declared.isBlank()) return UNKNOWN;
		return switch (declared.trim().toLowerCase(Locale.ROOT)) {
			case "client" -> CLIENT;
			case "server" -> SERVER;
			case "universal" -> UNIVERSAL;
			default -> UNKNOWN;
		};
	}
}
