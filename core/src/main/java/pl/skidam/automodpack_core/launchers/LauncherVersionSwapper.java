package pl.skidam.automodpack_core.launchers;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.skidam.automodpack_core.config.ConfigTools;

/**
 * The launcher-metadata half of a modpack switch: which launcher instance this game directory belongs to, which
 * version axes (Minecraft version, modloader type, modloader version) its metadata still needs to switch, and the
 * writes that converge it onto the target pack. Only fabric/forge/neoforge are switchable targets.
 */
public class LauncherVersionSwapper {

	/** The version axes a launcher instance can switch. */
	public enum Axis {
		/** The Minecraft version the launcher launches. */
		GAME_VERSION,
		/** Which modloader the instance runs (fabric/forge/neoforge). */
		LOADER_TYPE,
		/** The modloader version within the current modloader. */
		LOADER_VERSION
	}

	/**
	 * The launcher-metadata consequence of one target pack: the axes to write, or {@code refusedReason} when the
	 * switch must refuse loudly before any content is staged. A {@code manual} plan is one this launcher cannot
	 * apply itself: the pack still syncs, and the player must set the versions in the launcher by hand. An empty
	 * plan is a no-op.
	 */
	public record SwitchPlan(EnumSet<Axis> axes, boolean manual, String refusedReason) {
		public SwitchPlan {
			axes = axes == null ? EnumSet.noneOf(Axis.class) : EnumSet.copyOf(axes);
		}

		public boolean required() {
			return !axes.isEmpty();
		}

		public boolean manual() {
			return manual;
		}

		public boolean refused() {
			return refusedReason != null;
		}

		static SwitchPlan none() {
			return new SwitchPlan(EnumSet.noneOf(Axis.class), false, null);
		}

		static SwitchPlan of(EnumSet<Axis> axes) {
			return new SwitchPlan(axes, false, null);
		}

		static SwitchPlan manual(EnumSet<Axis> axes) {
			return new SwitchPlan(axes, true, null);
		}

		static SwitchPlan refused(EnumSet<Axis> axes, String reason) {
			return new SwitchPlan(axes, false, reason);
		}
	}

	/** Modloaders a launcher instance can be switched to; quilt and vanilla packs are refused. */
	private static final Set<String> SWITCHABLE_LOADERS = Set.of("fabric", "forge", "neoforge");

	/** The Prism meta uid of the Minecraft component, and the loader uids per switchable loader. */
	static final String MC_UID = "net.minecraft";
	static final Map<String, String> LOADER_UIDS = Map.of("fabric", "net.fabricmc.fabric-loader", "forge", "net.minecraftforge", "neoforge", "net.neoforged");

	/**
	 * Decides what launcher-metadata work the target pack demands of this client. The axes come from the detected
	 * launcher's own records; without launcher metadata (vanilla launcher and other unmanaged clients) only the
	 * fatal axes are compared, and the pack goes manual: the player sets the versions in the launcher by hand.
	 */
	public static SwitchPlan planSwitch(String serverLoader, String serverLoaderVersion, String serverMcVersion, boolean syncVersions, String clientLoader,
			String clientMcVersion) {
		LauncherAdapter adapter = detect();
		if (adapter == null) {
			// Launchers without metadata manage their own loader, so a bare loader-version bump is theirs to handle
			// (the established behavior). A version/loader-type change cannot be applied here either, but the pack
			// may still sync: the plan goes manual, and the player sets the versions in the launcher by hand.
			EnumSet<Axis> axes = fatalAxes(serverLoader, serverMcVersion, clientLoader, clientMcVersion);
			if (axes.isEmpty()) return SwitchPlan.none();
			return SwitchPlan.manual(axes);
		}
		if (serverLoader == null || !SWITCHABLE_LOADERS.contains(serverLoader.toLowerCase(Locale.ROOT)))
			return SwitchPlan.refused(EnumSet.noneOf(Axis.class), "This pack uses the unsupported modloader '" + serverLoader + "'");
		EnumSet<Axis> axes;
		try {
			axes = adapter.requiredAxes(serverLoader, serverLoaderVersion, serverMcVersion);
		} catch (IOException e) {
			return SwitchPlan.refused(EnumSet.noneOf(Axis.class), e.getMessage());
		}
		if (axes.isEmpty()) return SwitchPlan.none();
		if (!syncVersions) return SwitchPlan.refused(axes, "Version syncing is disabled in the AutoModpack settings, and this pack needs the game version switched to run");
		if (serverLoaderVersion == null || serverLoaderVersion.isBlank())
			return SwitchPlan.refused(axes, "The server does not advertise the pack's version metadata, and this pack needs the game version switched to run");
		// Every written version is gated on the Prism meta server, whatever the launcher: it lags a little behind
		// releases, which is the point. A version missing there is too new or does not exist, and a server
		// advertising one must not stage pack content into a launcher that cannot resolve it.
		String loaderUid = LOADER_UIDS.get(serverLoader.toLowerCase(Locale.ROOT));
		for (Axis axis : axes) {
			String uid = axis == Axis.GAME_VERSION ? MC_UID : loaderUid;
			String version = axis == Axis.GAME_VERSION ? serverMcVersion : serverLoaderVersion;
			if (!PrismMeta.isVersionResolvable(uid, version))
				return SwitchPlan.refused(axes, "meta.prismlauncher.org does not know " + uid + " " + version + ", so this switch refuses rather than write a version that may not exist");
		}
		try {
			axes = adapter.validate(axes, serverLoader, serverLoaderVersion, serverMcVersion);
		} catch (IOException e) {
			return SwitchPlan.refused(axes, e.getMessage());
		}
		if (axes.isEmpty()) return SwitchPlan.none();
		return SwitchPlan.of(axes);
	}

	/** Writes the planned axes through the detected launcher's metadata; returns only once the metadata converged to the target. */
	public static void apply(EnumSet<Axis> axes, String serverLoader, String serverLoaderVersion, String serverMcVersion) throws IOException {
		if (axes == null || axes.isEmpty()) return;
		LauncherAdapter adapter = detect();
		if (adapter == null) throw new IOException("No supported launcher metadata found for the version switch");
		adapter.apply(axes, serverLoader, serverLoaderVersion, serverMcVersion);
	}

	private static LauncherAdapter detect() {
		List<LauncherAdapter> adapters = List.of(new MultiMCMeta(), new PandoraMeta());
		for (LauncherAdapter adapter : adapters) {
			if (adapter.detected()) return adapter;
		}
		return null;
	}

	/** The axes that leave the pack unbootable on the running game: a different Minecraft version or modloader type. */
	static EnumSet<Axis> fatalAxes(String targetLoader, String targetMcVersion, String runningLoader, String runningMcVersion) {
		EnumSet<Axis> axes = EnumSet.noneOf(Axis.class);
		if (differs(targetMcVersion, runningMcVersion)) axes.add(Axis.GAME_VERSION);
		if (differsIgnoreCase(targetLoader, runningLoader)) axes.add(Axis.LOADER_TYPE);
		return axes;
	}

	private static boolean differs(String target, String current) {
		return target != null && !target.isBlank() && !target.equals(current);
	}

	private static boolean differsIgnoreCase(String target, String current) {
		return target != null && !target.isBlank() && !target.equalsIgnoreCase(current);
	}

	static JsonObject readJson(Path path) {
		try {
			return readJsonStrict(path);
		} catch (IOException e) {
			LOGGER.error("Failed to read launcher metadata at: {}", path, e);
			return null;
		}
	}

	static JsonObject readJsonStrict(Path path) throws IOException {
		if (!Files.exists(path)) return null;
		if (!Files.isRegularFile(path)) throw new IOException("Launcher metadata is not a regular file: " + path);
		try {
			return ConfigTools.parse(Files.readString(path, StandardCharsets.UTF_8), JsonObject.class);
		} catch (RuntimeException e) {
			throw new IOException("Invalid launcher metadata JSON: " + path, e);
		}
	}

	static void writeJsonAtomic(Path path, JsonElement json) throws IOException {
		ConfigTools.writeAtomic(path, json);
	}
}
