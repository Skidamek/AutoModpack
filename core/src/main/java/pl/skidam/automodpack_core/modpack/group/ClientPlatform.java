package pl.skidam.automodpack_core.modpack.group;

import java.util.List;
import java.util.Locale;

import pl.skidam.automodpack_core.utils.PlatformUtils;

/**
 * A platform by its canonical lowercase id. The three desktop platforms are the only ones detection can ever
 * produce, but a server admin may declare any other name in a group's {@code compatiblePlatforms}, and a client
 * may then explicitly select that group. Instances are value-equal by id: built-in ids always yield the built-in
 * constant, while admin-declared names parse to fresh instances.
 */
public final class ClientPlatform implements Comparable<ClientPlatform> {
	public static final ClientPlatform WINDOWS = new ClientPlatform("windows");
	public static final ClientPlatform LINUX = new ClientPlatform("linux");
	public static final ClientPlatform MACOS = new ClientPlatform("macos");
	/** Everything outside the first-class desktop trio - mobile launchers, the BSDs, anything new. */
	public static final ClientPlatform OTHER = new ClientPlatform("other");

	private static final List<ClientPlatform> BUILT_INS = List.of(WINDOWS, LINUX, MACOS, OTHER);

	private ClientPlatform(String id) {
		this.id = id;
	}

	private final String id;

	public static ClientPlatform current() {
		return switch (PlatformUtils.operatingSystem()) {
			case WINDOWS -> WINDOWS;
			case MACOS -> MACOS;
			case LINUX -> LINUX;
			case OTHER -> OTHER;
		};
	}

	/** The saved selection's platform override when present, otherwise the detected platform. */
	public static ClientPlatform effective(SelectionIntent savedSelection) {
		return savedSelection != null && savedSelection.platform() != null ? savedSelection.platform() : current();
	}

	/** The platforms auto-detection can ever produce; admin-declared names outside this list are still legal. */
	public static List<ClientPlatform> builtIns() {
		return BUILT_INS;
	}

	public static ClientPlatform parse(String value) {
		if (value == null) throw new IllegalArgumentException("Platform is null");
		String id = value.strip().toLowerCase(Locale.ROOT);
		if (id.isEmpty()) throw new IllegalArgumentException("Platform name is blank");
		for (ClientPlatform builtIn : BUILT_INS) if (builtIn.id.equals(id)) return builtIn;
		return new ClientPlatform(id);
	}

	public String id() {
		return id;
	}

	@Override
	public int compareTo(ClientPlatform other) {
		return id.compareTo(other.id);
	}

	@Override
	public boolean equals(Object other) {
		return other instanceof ClientPlatform platform && id.equals(platform.id);
	}

	@Override
	public int hashCode() {
		return id.hashCode();
	}

	@Override
	public String toString() {
		return id;
	}
}
