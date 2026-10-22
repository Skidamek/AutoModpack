package pl.skidam.automodpack_core.launchers;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.EnumSet;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiPredicate;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;

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

	/** The versions a launcher instance is switched to; any of them may be blank, which leaves that part as it stands. */
	public record Versions(String loader, String loaderVersion, String mcVersion) {
		/** What a pack advertises, or null when it advertises nothing and syncs as files only. */
		public static Versions advertisedBy(ModpackJsons.ModpackContentFields pack) {
			return of(pack.loader, pack.loaderVersion, pack.mcVersion);
		}

		/** Each part {@code first} leaves blank taken from {@code fallback}, a loader version only for its own loader; null when neither names any. */
		public static Versions merge(Versions first, Versions fallback) {
			if (first == null || fallback == null) return first == null ? fallback : first;
			String loader = blank(first.loader) ? fallback.loader : first.loader;
			boolean sameLoader = loader != null && loader.equalsIgnoreCase(fallback.loader);
			return of(loader, blank(first.loaderVersion) && sameLoader ? fallback.loaderVersion : first.loaderVersion, blank(first.mcVersion) ? fallback.mcVersion : first.mcVersion);
		}

		/** The {@code key=value} words {@link #decode} reads back; blank parts are left out. */
		public String encode() {
			StringBuilder words = new StringBuilder();
			if (!blank(loader)) words.append(" loader=").append(loader);
			if (!blank(loaderVersion)) words.append(" loaderVersion=").append(loaderVersion);
			if (!blank(mcVersion)) words.append(" mcVersion=").append(mcVersion);
			return words.toString().strip();
		}

		/** Reads {@link #encode} back, skipping words it does not know; null when no part survives. */
		public static Versions decode(String encoded) {
			Map<String, String> parts = new HashMap<>();
			for (String word : encoded == null ? new String[0] : encoded.strip().split("\\s+")) {
				int separator = word.indexOf('=');
				if (separator > 0) parts.put(word.substring(0, separator), word.substring(separator + 1));
			}
			return of(parts.get("loader"), parts.get("loaderVersion"), parts.get("mcVersion"));
		}

		private static Versions of(String loader, String loaderVersion, String mcVersion) {
			if (blank(loader) && blank(loaderVersion) && blank(mcVersion)) return null;
			return new Versions(loader, loaderVersion, mcVersion);
		}
	}

	/** A launcher switch already planned: the axes it writes and the versions it writes them to. */
	public record Switch(EnumSet<Axis> axes, Versions versions) {
		public Switch {
			axes = EnumSet.copyOf(axes);
		}

		/** Whether planning this switch already checked {@code version} of the Prism meta component {@code uid}: only an axis it writes was. */
		public boolean vouchesFor(String uid, String version) {
			if (MC_UID.equals(uid)) return axes.contains(Axis.GAME_VERSION) && version.equals(versions.mcVersion());
			return (axes.contains(Axis.LOADER_TYPE) || axes.contains(Axis.LOADER_VERSION)) && !blank(versions.loader())
					&& uid.equals(LOADER_UIDS.get(versions.loader().toLowerCase(Locale.ROOT))) && version.equals(versions.loaderVersion());
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
	 * A pack that publishes no version metadata at all is files-only: no switch and no refusal.
	 */
	public static SwitchPlan planSwitch(String serverLoader, String serverLoaderVersion, String serverMcVersion, boolean syncVersions, String clientLoader,
			String clientMcVersion, LauncherAdapter adapter, BiPredicate<String, String> versionKnown) {
		if (blank(serverLoader) && blank(serverLoaderVersion) && blank(serverMcVersion)) {
			// The pack publishes no version metadata (advertise-versions-to-sync: false): the pack syncs as files and
			// the launcher's own versions stand. The unsupported-loader refusal below needs a named loader to judge.
			return SwitchPlan.none();
		}
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
		if (!syncVersions) {
			if (axes.contains(Axis.GAME_VERSION))
				return SwitchPlan.refused(axes, "Version syncing is disabled in the AutoModpack settings, and this pack needs the game version switched to run");
			return SwitchPlan.none();
		}
		if (serverLoaderVersion == null || serverLoaderVersion.isBlank()) {
			axes.remove(Axis.LOADER_TYPE);
			axes.remove(Axis.LOADER_VERSION);
			if (axes.isEmpty()) return SwitchPlan.none();
		}
		String loaderUid = LOADER_UIDS.get(serverLoader.toLowerCase(Locale.ROOT));
		Iterator<Axis> iterator = axes.iterator();
		while (iterator.hasNext()) {
			Axis axis = iterator.next();
			String uid = axis == Axis.GAME_VERSION ? MC_UID : loaderUid;
			String version = axis == Axis.GAME_VERSION ? serverMcVersion : serverLoaderVersion;
			if (versionKnown.test(uid, version)) continue;
			if (axis == Axis.GAME_VERSION)
				return SwitchPlan.refused(axes, "meta.prismlauncher.org does not know " + uid + " " + version + ", so this switch refuses rather than write a Minecraft version that may not exist");
			LOGGER.warn("Skipping launcher {} switch; meta.prismlauncher.org does not know {} {}", axis, uid, version);
			iterator.remove();
		}
		if (axes.isEmpty()) return SwitchPlan.none();
		return SwitchPlan.of(axes);
	}

	/**
	 * Writes the planned axes through the launcher metadata of the instance around {@code gameDirectory}; returns only once
	 * the metadata converged to the target, and whether a record had to change for it.
	 */
	public static boolean apply(Switch launcherSwitch, Path gameDirectory) throws IOException {
		Versions target = launcherSwitch.versions();
		return writableAdapter(gameDirectory).apply(launcherSwitch.axes(), target.loader(), target.loaderVersion(), target.mcVersion());
	}

	/** Throws unless the instance around {@code gameDirectory} holds launcher metadata this switch can be written to and read back. */
	public static void requireWritable(Switch launcherSwitch, Path gameDirectory) throws IOException {
		Versions target = launcherSwitch.versions();
		writableAdapter(gameDirectory).requiredAxes(target.loader(), target.loaderVersion(), target.mcVersion());
	}

	private static LauncherAdapter writableAdapter(Path gameDirectory) throws IOException {
		LauncherAdapter adapter = detect(gameDirectory);
		if (adapter == null) throw new IOException("No supported launcher metadata found for the version switch");
		return adapter;
	}

	/** The launcher whose instance layout holds {@code gameDirectory}, or null when no supported one does. */
	public static LauncherAdapter detect(Path gameDirectory) {
		Path instance = gameDirectory.toAbsolutePath().normalize().getParent();
		if (instance == null) return null;
		List<LauncherAdapter> adapters = List.of(new MultiMCMeta(instance.resolve("mmc-pack.json")), new PandoraMeta(instance.resolve("info_v1.json")));
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

	private static boolean blank(String value) {
		return value == null || value.isBlank();
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
