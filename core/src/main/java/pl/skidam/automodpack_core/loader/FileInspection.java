package pl.skidam.automodpack_core.loader;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;
import java.util.jar.Manifest;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import pl.skidam.automodpack_core.Environment;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.JarUtils;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.tomledit.ParseResult;
import pl.skidam.tomledit.TomlEdit;
import pl.skidam.tomledit.TomlValue;

public class FileInspection {
	private static final Logger LOGGER = LogManager.getLogger(FileInspection.class);

	private static final Gson GSON = new Gson();
	private static final Set<String> LOADER_ARTIFACT_TYPES = Set.of("GAMELIBRARY", "LIBRARY", "LANGPROVIDER");

	private static String getLoader() {
		Environment environment = Environment.installed();
		return environment == null ? null : environment.loader();
	}

	/**
	 * Whether the file is a mod jar at all, the metadata when there is any, and the loader-service files this jar actually
	 * ships. A service-only jar is a mod whose metadata is absent; {@code services} is that jar's identity. A NeoForge
	 * JarJar GAMELIBRARY wrapper is a mod whose metadata may live in the nested jar. Incomplete metadata with no
	 * services, no loader artifact type, and no JarJar nested mod is not a mod.
	 */
	public record Inspection(boolean mod, Mod metadata, Set<String> services) {
		public Inspection {
			services = services == null || services.isEmpty() ? Set.of() : Set.copyOf(services);
		}

		public Inspection(boolean mod, Mod metadata) {
			this(mod, metadata, Set.of());
		}

		/** The side this file's own metadata declares; a jar that ships no readable metadata declares none. */
		public ModEnvironment environment() {
			return metadata == null ? ModEnvironment.UNKNOWN : metadata.environment();
		}

		/** What a joining client is allowed to do with this jar; metadata-free jars promise nothing, so they are {@link ClientPresence#OPTIONAL}. */
		public ClientPresence clientPresence() {
			return metadata == null ? ClientPresence.OPTIONAL : metadata.clientPresence();
		}

		/** Whether the loader would let a client run without this jar. */
		public boolean clientMayBeAbsent() {
			return clientPresence() != ClientPresence.REQUIRED;
		}

		/** Whether the jar's own metadata names it a server-only mod. */
		public boolean declaresServerOnly() {
			return clientPresence() == ClientPresence.SERVER_ONLY;
		}

		/** Whether this jar ships any of the named services. The wanted set is the caller's question, not a catalog this inspection stores. */
		public boolean providesAny(Set<String> wanted) {
			if (wanted == null || wanted.isEmpty() || services.isEmpty()) return false;
			for (String service : services) if (wanted.contains(service)) return true;
			return false;
		}
	}

	/**
	 * Legacy Forge displayTest, strictest entry wins. Only {@code META-INF/mods.toml} carries the key; NeoForge dropped it.
	 */
	private enum DisplayTest {
		IGNORE_SERVER_VERSION,
		IGNORE_ALL_VERSION,
		MATCH_VERSION,
		NONE;

		static DisplayTest byName(String name) {
			if (name == null) return null;
			String trimmed = name.trim().toUpperCase(Locale.ROOT);
			for (DisplayTest test : values()) if (test.name().equals(trimmed)) return test;
			return null;
		}

		boolean permitsClientAbsence() {
			return this == IGNORE_SERVER_VERSION || this == IGNORE_ALL_VERSION;
		}
	}

	public record Mod(Set<String> IDs, String hash, String version, Path path, Set<String> deps, Set<Mod> nestedMods, String id, ModEnvironment environment, ClientPresence clientPresence) {
		public Mod(Set<String> IDs, String hash, String version, Path path, Set<String> deps, Set<Mod> nestedMods) {
			this(IDs, hash, version, path, deps, nestedMods, null, ModEnvironment.UNIVERSAL, ClientPresence.OPTIONAL);
		}

		public Mod {
			environment = environment == null ? ModEnvironment.UNIVERSAL : environment;
			clientPresence = clientPresence == null ? ClientPresence.OPTIONAL : clientPresence;
		}

		public Mod at(Path newPath) {
			return new Mod(IDs, hash, version, newPath, deps, nestedMods, id, environment, clientPresence);
		}
	}

	private record ModMetadata(String modId, String version, Set<String> provides, Set<String> deps, ModEnvironment environment, Set<String> declaredJars, DisplayTest displayTest) {
		ModMetadata {
			declaredJars = declaredJars == null ? Set.of() : Set.copyOf(declaredJars);
		}
	}

	/** One open answers both facts about the file, so a jar is never mounted once per question. */
	public static Inspection inspect(Path file, FileCache cache) {
		if (isJarInvalid(file)) return new Inspection(false, null, Set.of());

		String hash = FileIntegrity.identityHash(file, cache);
		if (hash == null) {
			LOGGER.error("Failed to get hash for file: {}", file);
			return new Inspection(false, null, Set.of());
		}

		try (FileSystem fs = JarUtils.openJar(file)) {
			ModMetadata meta = getModMetadata(fs);
			Set<String> services = getServices(fs, LoaderServicePaths.ALL_SERVICES);
			Mod metadata = meta == null ? null : completeMod(fs, meta, hash, file);
			if (metadata != null) return new Inspection(true, metadata, services);
			List<Mod> jarJarMods = scanJarJarMods(fs);
			metadata = singleCompleteJarJarMod(jarJarMods, hash, file);
			return new Inspection(metadata != null || !services.isEmpty() || isLoaderArtifact(fs) || !jarJarMods.isEmpty(), metadata, services);
		} catch (IOException e) {
			LOGGER.debug("Failed to inspect mod file: {}", file);
			return new Inspection(false, null, Set.of());
		}
	}

	private static Mod completeMod(FileSystem fs, ModMetadata meta, String hash, Path file) {
		if (meta.modId() == null) return null;
		if (meta.version() == null) {
			LOGGER.error("Incomplete mod info for file: {} (ID: {}, Ver: {})", file, meta.modId(), meta.version());
			return null;
		}
		Set<String> ids = new HashSet<>(meta.provides());
		ids.add(meta.modId());
		boolean nested = meta.displayTest() != null && shipsNestedJars(fs);
		return new Mod(ids, hash, meta.version(), file, meta.deps(), scanForNestedMods(fs, meta.declaredJars()), meta.modId(), meta.environment(), clientPresence(meta.displayTest(), nested));
	}

	/**
	 * Whether the jar ships any {@code META-INF/jarjar} jars. Unreadable means unprovable, and unprovable means no promise.
	 */
	private static boolean shipsNestedJars(FileSystem fs) {
		Path jarJarDirectory = fs.getPath("META-INF", "jarjar");
		if (Files.notExists(jarJarDirectory)) return false;
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(jarJarDirectory, "*.jar")) {
			return stream.iterator().hasNext();
		} catch (IOException e) {
			LOGGER.error("Error examining JarJar directory in {}", fs, e);
			return true;
		}
	}

	private static boolean isLoaderArtifact(FileSystem fs) {
		Path manifestPath = fs.getPath("META-INF", "MANIFEST.MF");
		if (Files.notExists(manifestPath)) return false;
		try (InputStream in = Files.newInputStream(manifestPath)) {
			String type = new Manifest(in).getMainAttributes().getValue("FMLModType");
			if (type == null || type.isBlank()) return false;
			return LOADER_ARTIFACT_TYPES.contains(type.strip().toUpperCase(Locale.ROOT));
		} catch (IOException e) {
			LOGGER.debug("Could not read FMLModType from {}", fs, e);
			return false;
		}
	}

	private static boolean hasJarJarMod(FileSystem fs) {
		return !scanJarJarMods(fs).isEmpty();
	}

	private static List<Mod> scanJarJarMods(FileSystem fs) {
		Path jarJarDirectory = fs.getPath("META-INF", "jarjar");
		if (Files.notExists(jarJarDirectory)) return List.of();
		List<Mod> mods = new ArrayList<>();
		try (DirectoryStream<Path> stream = Files.newDirectoryStream(jarJarDirectory, "*.jar")) {
			for (Path nestedJar : stream) {
				try (InputStream is = Files.newInputStream(nestedJar)) {
					Mod nested = readModFromStream(nestedJar, is);
					if (nested != null) mods.add(nested);
				} catch (IOException e) {
					LOGGER.debug("Skipping unreadable JarJar nested jar: {}", nestedJar, e);
				}
			}
		} catch (IOException e) {
			LOGGER.error("Error examining JarJar directory in {}", fs, e);
		}
		return mods;
	}

	private static Mod singleCompleteJarJarMod(List<Mod> jarJarMods, String hash, Path file) {
		Mod complete = null;
		for (Mod nested : jarJarMods) {
			if (nested.id() == null || nested.version() == null) continue;
			if (complete != null) return null;
			complete = nested;
		}
		if (complete == null) return null;
		return new Mod(complete.IDs(), hash, complete.version(), file, complete.deps(), complete.nestedMods(), complete.id(), complete.environment(), complete.clientPresence());
	}

	/** Whether the file is a mod or an early-service jar, with no cache and no metadata: for loader call sites that only need the yes. */
	public static boolean isMod(Path file) {
		if (isJarInvalid(file)) return false;
		try (FileSystem fs = JarUtils.openJar(file)) {
			return getModMetadata(fs) != null || hasSpecificServices(fs) || isLoaderArtifact(fs) || hasJarJarMod(fs);
		} catch (IOException e) {
			return false;
		}
	}

	public static boolean isModCompatible(Path file) {
		if (isJarInvalid(file)) return false;

		try (FileSystem fs = JarUtils.openJar(file)) {
			String entryPathString = metadataPathForLoader(getLoader());

			if (entryPathString != null && Files.exists(fs.getPath(entryPathString))) return true;

			if (("forge".equals(getLoader()) || "neoforge".equals(getLoader())) && (hasSpecificServices(fs) || isLoaderArtifact(fs) || hasJarJarMod(fs))) return true;
		} catch (IOException e) {
			LOGGER.error("Error examining JarJar in {}", e);
		}
		return false;
	}

	public static String getModVersion(Path file) {
		return extractBasicInfo(file, ModMetadata::version);
	}

	public static String getModID(Path file) {
		return extractBasicInfo(file, ModMetadata::modId);
	}

	public static boolean hasNestedModWithSameId(FileSystem fs) {
		ModMetadata metadata = getModMetadata(fs);
		if (metadata == null || metadata.modId() == null) return false;

		Set<String> rootIds = new HashSet<>(metadata.provides());
		rootIds.add(metadata.modId());
		try (Stream<Path> walk = Files.walk(fs.getPath("/"))) {
			return walk.filter(JarUtils::isRegularJar).anyMatch(path -> nestedModHasAnyId(path, rootIds));
		} catch (IOException e) {
			LOGGER.debug("Failed to inspect nested mod IDs");
			return false;
		}
	}

	private static boolean nestedModHasAnyId(Path path, Set<String> rootIds) {
		try (InputStream is = Files.newInputStream(path)) {
			Mod nested = readModFromStream(path, is);
			return nested != null && !Collections.disjoint(rootIds, nested.IDs());
		} catch (IOException e) {
			LOGGER.debug("Skipping unreadable nested jar: {}", path);
			return false;
		}
	}

	private static boolean isJarInvalid(Path file) {
		if (file == null || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) return true;
		if (JarUtils.hasJarExtension(file)) return false;
		try (InputStream input = Files.newInputStream(file)) {
			int first = input.read();
			int second = input.read();
			int third = input.read();
			int fourth = input.read();
			return !(first == 'P' && second == 'K' && ((third == 3 && fourth == 4) || (third == 5 && fourth == 6) || (third == 7 && fourth == 8)));
		} catch (IOException e) {
			return true;
		}
	}

	private static <T> T extractBasicInfo(Path file, Function<ModMetadata, T> extractor) {
		if (isJarInvalid(file)) return null;
		try (FileSystem fs = JarUtils.openJar(file)) {
			ModMetadata meta = getModMetadata(fs);
			return meta != null ? extractor.apply(meta) : null;
		} catch (IOException e) {
			LOGGER.error("Error reading mod file {}: {}", file, e.getMessage());
		}
		return null;
	}

	/** Scans the declared nested JAR entries; {@code ModFileCache} caches the resulting inspection by content hash. */
	private static Set<Mod> scanForNestedMods(FileSystem parentFs, Set<String> declaredJars) {
		Set<Mod> nestedMods = new HashSet<>();
		try (Stream<Path> walk = Files.walk(parentFs.getPath("/"))) {
			for (Path path : walk.toList()) {
				// Walk entries are absolute while declared paths are entry names, so both sides compare by the exact zip entry name the loader looks up.
				String entryName = path.toString();
				if (!JarUtils.isRegularJar(path) || !declaredJars.contains(entryName.startsWith("/") ? entryName.substring(1) : entryName)) continue;
				try (InputStream is = Files.newInputStream(path)) {
					Mod nested = readModFromStream(path, is);
					if (nested != null) nestedMods.add(nested);
				} catch (IOException e) {
					LOGGER.debug("Skipping unreadable nested jar: {}", path);
				}
			}
		} catch (IOException e) {
			LOGGER.error("Error scanning nested mods: {}", e.getMessage());
		}
		return nestedMods;
	}

	/**
	 * Reads a JAR from an InputStream (recursively) without mounting it as a FileSystem.
	 */
	private static Mod readModFromStream(Path virtualPath, InputStream is) {

		// ZipInputStream must NOT close the underlying stream if it's a child stream
		ZipInputStream zis = new ZipInputStream(is);
		ZipEntry entry;
		ModMetadata metadata = null;
		Map<String, Mod> nestedChildren = new HashMap<>();

		try {
			while ((entry = zis.getNextEntry()) != null) {
				String name = entry.getName();

				if (isMetadataFilename(name)) {
					if (name.endsWith(".toml")) {
						metadata = parseTomlMetadata(zis.readAllBytes(), name.endsWith("neoforge.mods.toml"));
					} else {
						// Prevent reader from closing the ZipInputStream
						BufferedReader reader = new BufferedReader(new InputStreamReader(new FilterInputStream(zis) {
							@Override
							public void close() {}
						}, StandardCharsets.UTF_8));
						metadata = parseJsonMetadata(reader);
					}
				} else if (JarUtils.hasJarExtension(name)) {
					// Wrap ZIS to protect current stream position
					Mod child = readModFromStream(virtualPath.resolve(name), new FilterInputStream(zis) {
						@Override
						public void close() {}
					});
					if (child != null) nestedChildren.put(name, child);
				}
			}
		} catch (IOException e) {
			LOGGER.debug("Error processing stream for {}", virtualPath);
		}

		if (metadata != null && metadata.modId() != null) {
			Set<String> ids = new HashSet<>(metadata.provides());
			ids.add(metadata.modId());
			Set<String> declaredJars = metadata.declaredJars();
			Set<Mod> nestedMods = nestedChildren.entrySet().stream().filter(child -> declaredJars.contains(child.getKey())).map(Map.Entry::getValue).collect(Collectors.toSet());
			// Investigate if we need hash or not
			return new Mod(ids, null, metadata.version(), virtualPath, metadata.deps(), nestedMods, metadata.modId(), metadata.environment(), clientPresence(metadata.displayTest(), false));
		}
		return null;
	}

	private static ModMetadata getModMetadata(FileSystem fs) {
		Path metaPath = getMetadataPath(fs);
		if (metaPath == null) return null;

		try {
			if (metaPath.toString().endsWith(".toml")) {
				return parseTomlMetadata(Files.readAllBytes(metaPath), metaPath.getFileName().toString().equals("neoforge.mods.toml"));
			}
			try (BufferedReader reader = Files.newBufferedReader(metaPath)) {
				return parseJsonMetadata(reader);
			}
		} catch (IOException e) {
			LOGGER.error("Error parsing metadata {}: {}", metaPath, e.getMessage());
		}
		return null;
	}

	private static ModMetadata parseTomlMetadata(byte[] bytes, boolean neoforgeSemantics) {
		try {
			ParseResult parsed = TomlEdit.parse(bytes);
			if (!parsed.isOk()) {
				LOGGER.error("TOML Parse Error: {}", parsed.error());
				return null;
			}
			TomlValue.Table result = parsed.document().tree();
			List<TomlValue.Table> mods = tomlTables(result, "mods");
			if (mods.isEmpty()) return null;

			String modId = null;
			String version = "1";
			Set<String> provides = new HashSet<>();
			Set<String> deps = new HashSet<>();
			DisplayTest displayTest = null;

			for (TomlValue.Table modTable : mods) {
				if (modId == null) modId = modTable.getString("modId").orElse(null);

				String v = modTable.getString("version").orElse(null);
				if (v != null && !v.equals("${file.jarVersion}")) version = v;

				TomlValue.Array prov = modTable.getArray("provides").orElse(null);
				if (prov != null) for (TomlValue p : prov.elements()) if (p instanceof TomlValue.Str s) provides.add(s.value());

				// Legacy Forge reads displayTest per mod entry and refuses unknown values; NeoForge dropped the key
				// entirely, so only mods.toml promises anything by it. A multi-mod jar keeps its strictest entry.
				if (!neoforgeSemantics) {
					DisplayTest entry = displayTestOf(modTable.getString("displayTest").orElse(null));
					if (displayTest == null || entry.ordinal() > displayTest.ordinal()) displayTest = entry;
				}
			}

			if (modId != null) {
				// [[dependencies.<modId>]] is keyed by the mod's own id. side on a minecraft/forge/neoforge row is which
				// physical side that dependency is checked on, not whether this jar loads on the client.
				TomlValue.Table depsTable = result.getTable("dependencies").orElse(null);
				if (depsTable != null) {
					for (TomlValue.Table depTable : tomlTables(depsTable, modId)) {
						String depId = depTable.getString("modId").orElse(null);
						if (depId == null) continue;

						// NeoForge 20.5+ reads neoforge.mods.toml, gates on type alone and never reads mandatory; legacy
						// Forge reads META-INF/mods.toml and gates on mandatory (absent means true). Type wins when both
						// exist. The metadata file names the semantics, so a jar parses the same on every loader - a
						// fabric client planning a neoforge pack included.
						boolean required;
						String typeName = depTable.getString("type").orElse(null);
						if (typeName != null) required = "required".equalsIgnoreCase(typeName);
						else if (neoforgeSemantics) required = true;
						else required = depTable.getBoolean("mandatory").orElse(true);
						if (!required) continue;

						deps.add(depId);
					}
				}
			}
			return new ModMetadata(modId, version, provides, deps, ModEnvironment.UNIVERSAL, Set.of(), displayTest);
		} catch (Exception e) {
			LOGGER.error("TOML Parse Error: {}", e.getMessage());
			return null;
		}
	}

	private static List<TomlValue.Table> tomlTables(TomlValue.Table parent, String key) {
		TomlValue.Array array = parent.getArray(key).orElse(null);
		if (array == null) return List.of();
		List<TomlValue.Table> out = new ArrayList<>();
		for (TomlValue elem : array.elements()) if (elem instanceof TomlValue.Table t) out.add(t);
		return out;
	}

	private static DisplayTest displayTestOf(String value) {
		if (value == null) return DisplayTest.MATCH_VERSION;
		DisplayTest test = DisplayTest.byName(value);
		return test == null ? DisplayTest.NONE : test;
	}

	private static ClientPresence clientPresence(DisplayTest displayTest, boolean shipsNestedJars) {
		if (displayTest == null) return ClientPresence.OPTIONAL;
		if (shipsNestedJars) return ClientPresence.REQUIRED;
		if (displayTest == DisplayTest.IGNORE_SERVER_VERSION) return ClientPresence.SERVER_ONLY;
		return displayTest.permitsClientAbsence() ? ClientPresence.OPTIONAL : ClientPresence.REQUIRED;
	}

	private static ModMetadata parseJsonMetadata(BufferedReader reader) {
		try {
			JsonObject json = GSON.fromJson(reader, JsonObject.class);
			String modId = getJsonString(json, "id");
			String version = getJsonString(json, "version");
			Set<String> provides = new HashSet<>();
			Set<String> deps = new HashSet<>();
			ModEnvironment env = ModEnvironment.UNIVERSAL;

			if (json.has("provides")) {
				for (JsonElement e : json.get("provides").getAsJsonArray()) {
					if (e.isJsonObject()) provides.add(e.getAsJsonObject().get("id").getAsString());
					else provides.add(e.getAsString());
				}
			}

			if (json.has("depends")) {
				JsonElement depends = json.get("depends");
				if (depends.isJsonObject()) {
					deps.addAll(depends.getAsJsonObject().keySet());
				} else if (depends.isJsonArray()) {
					for (JsonElement e : depends.getAsJsonArray()) {
						if (e.isJsonObject()) deps.add(e.getAsJsonObject().get("id").getAsString());
						else deps.add(e.getAsString());
					}
				}
			}

			if (json.has("environment")) env = ModEnvironment.of(json.get("environment").getAsString());

			Set<String> declaredJars = new HashSet<>();
			if (json.has("jars") && json.get("jars").isJsonArray()) {
				for (JsonElement element : json.get("jars").getAsJsonArray()) {
					if (!element.isJsonObject()) continue;
					JsonElement file = element.getAsJsonObject().get("file");
					if (file != null && file.isJsonPrimitive() && !file.getAsString().isBlank()) declaredJars.add(file.getAsString());
				}
			}

			return new ModMetadata(modId, version, provides, deps, env, declaredJars, null);
		} catch (Exception e) {
			LOGGER.error("JSON Parse Error: {}", e.getMessage());
			return null;
		}
	}

	private static String getJsonString(JsonObject obj, String key) {
		return obj.has(key) ? obj.get(key).getAsString() : null;
	}

	private record LoaderMetadata(String loader, String path) {}

	/** Every recognized mod-metadata location, in probe order. */
	private static final List<LoaderMetadata> METADATA_LOCATIONS = List.of(new LoaderMetadata("neoforge", "META-INF/neoforge.mods.toml"), new LoaderMetadata("fabric", "fabric.mod.json"),
			new LoaderMetadata("forge", "META-INF/mods.toml"));

	/** The metadata file the running loader prefers, or null when the running loader has none. */
	private static String metadataPathForLoader(String loader) {
		return METADATA_LOCATIONS.stream().filter(location -> location.loader().equals(loader)).findFirst().map(LoaderMetadata::path).orElse(null);
	}

	private static Path getMetadataPath(FileSystem fs) {
		String preferredEntry = metadataPathForLoader(getLoader());

		if (preferredEntry != null) {
			Path p = fs.getPath(preferredEntry);
			if (Files.exists(p)) return p;
		}

		for (LoaderMetadata location : METADATA_LOCATIONS) {
			if (location.path().equals(preferredEntry)) continue;
			Path p = fs.getPath(location.path());
			if (Files.exists(p)) return p;
		}
		return null;
	}

	private static boolean isMetadataFilename(String name) {
		return name.endsWith("mods.toml") || name.endsWith("mod.json");
	}

	/**
	 * Whether this jar ships any recognized loader-service file, root or nested - used to tell a
	 * service mod apart from a plain mod. Recognition is loader-agnostic (the running loader isn't
	 * known yet in all callers), so this checks the full cross-loader union.
	 */
	private static boolean hasSpecificServices(FileSystem fs) {
		Set<String> known = LoaderServicePaths.ALL_SERVICES;
		// Short-circuit on the first root match (the common case for service mods) before paying
		// for the nested jarjar scan - isMod/isModCompatible call this over every mod.
		for (String service : known) {
			if (Files.exists(fs.getPath(service))) return true;
		}
		Set<String> nested = new HashSet<>();
		collectSpecificServicesNested(fs, known, nested, true);
		return !nested.isEmpty();
	}

	/**
	 * The {@code ofInterest} loader-service files this jar provides, both at its root and inside any
	 * {@code META-INF/jarjar} nested jars. Callers pass whichever set is relevant to them (e.g. a
	 * loader module's own known/handleable services) instead of a hardcoded loader namespace.
	 */
	public static Set<String> getServices(FileSystem fs, Set<String> ofInterest) {
		Set<String> found = new HashSet<>();

		// Root FileSystem
		for (String service : ofInterest) {
			if (Files.exists(fs.getPath(service))) found.add(service);
		}

		// Nested JARs in META-INF/jarjar
		collectSpecificServicesNested(fs, ofInterest, found, false);

		return found;
	}

	/** Same over a jar file: the only service read that works for a service-only jar, which has no mod metadata to inspect. */
	public static Set<String> getServices(Path file, Set<String> ofInterest) {
		if (isJarInvalid(file)) return Set.of();
		try (FileSystem fs = JarUtils.openJar(file)) {
			return getServices(fs, ofInterest);
		} catch (IOException e) {
			LOGGER.debug("Failed to read loader services of {}", file);
			return Set.of();
		}
	}

	/**
	 * @param stopAtFirst
	 *            {@code true} to stop at the first match (the {@link #hasSpecificServices}
	 *            hot path), {@code false} to collect every match ({@link #getServices}).
	 */
	private static void collectSpecificServicesNested(FileSystem fs, Set<String> known, Set<String> found, boolean stopAtFirst) {
		Path jarJarDir = fs.getPath("META-INF", "jarjar");

		if (Files.notExists(jarJarDir)) return;

		try (DirectoryStream<Path> stream = Files.newDirectoryStream(jarJarDir, "*.jar")) {
			for (Path nestedJar : stream) {
				collectNestedJarServices(nestedJar, known, found, stopAtFirst);
				if (stopAtFirst && !found.isEmpty()) return;
			}
		} catch (IOException e) {
			LOGGER.error("Error examining JarJar directory in {}", fs, e);
		}
	}

	private static void collectNestedJarServices(Path nestedJarPath, Set<String> known, Set<String> found, boolean stopAtFirst) {
		try (InputStream is = Files.newInputStream(nestedJarPath);
				BufferedInputStream bis = new BufferedInputStream(is);
				ZipInputStream zip = new ZipInputStream(bis)) {

			ZipEntry entry;
			while ((entry = zip.getNextEntry()) != null) {
				if (known.contains(entry.getName())) {
					found.add(entry.getName());
					if (stopAtFirst) return;
				}
			}
		} catch (IOException e) {
			LOGGER.error("Error reading nested JAR {}: {}", nestedJarPath, e.getMessage());
		}
	}
}
