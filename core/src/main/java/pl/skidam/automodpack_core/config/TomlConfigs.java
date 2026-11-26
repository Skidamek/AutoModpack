package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.storage.StoragePaths.CLIENT_DIR;
import static pl.skidam.automodpack_core.storage.StoragePaths.CLIENT_SELECTED_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.V4_CLIENT_CONFIG_ALT_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.V4_CLIENT_CONFIG_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.V4_SERVER_CONFIG_ALT_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.V4_SERVER_CONFIG_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.V5_RC_CLIENT_CONFIG_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.V5_RC_SERVER_CONFIG_FILE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonSyntaxException;

import pl.skidam.automodpack_core.config.ClientConfigJsons.ClientConfigFieldsV3;
import pl.skidam.automodpack_core.config.ServerConfigJsons.GroupDeclaration;
import pl.skidam.automodpack_core.config.ServerConfigJsons.ModpackFields;
import pl.skidam.automodpack_core.config.ServerConfigJsons.ServerConfigFieldsV3;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.modpack.group.ServerSideModExclusion;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.OsPaths;
import pl.skidam.tomledit.Comments;
import pl.skidam.tomledit.ConfigPath;
import pl.skidam.tomledit.Document;
import pl.skidam.tomledit.ParseError;
import pl.skidam.tomledit.ParseResult;
import pl.skidam.tomledit.ReconcilePolicy;
import pl.skidam.tomledit.TableReader;
import pl.skidam.tomledit.TomlEdit;
import pl.skidam.tomledit.TomlSchema;
import pl.skidam.tomledit.TomlValidationException;
import pl.skidam.tomledit.TomlValue;

/**
 * The {@code toml-edit}-backed store for human-editable configs ({@code server.toml} and {@code client.toml}).
 *
 * <p>
 * Legacy predecessors ({@code server.conf}/{@code client.conf} from v5-rc, or v4 JSON files) are
 * migrated once on load into a fresh canonical {@code .toml} and renamed to {@code .backup}; after
 * that only {@code .toml} is read. Saves reconcile the existing document in place with
 * {@link ReconcilePolicy#SYNC} so user comments, blank lines, layout, and line endings survive.
 */
public final class TomlConfigs {
	private static final Logger LOGGER = LogManager.getLogger(TomlConfigs.class);
	private static final String BANNER = "AutoModpack configuration. Docs: https://moddedmc.wiki/en/project/automodpack/docs";

	private static final TomlSchema SERVER_SCHEMA = TomlSchema.builder()
			.banner(BANNER)
			.field(ConfigPath.of("modpack-host"), TomlValue.bool(true))
			.field(ConfigPath.of("generate-modpack-on-start"), TomlValue.bool(true))
			.field(ConfigPath.of("require-modpack"), TomlValue.bool(true))
			.field(ConfigPath.of("self-updater"), TomlValue.bool(false))
			.field(ConfigPath.of("accepted-loaders"), TomlValue.array(), "extra loaders allowed to join; this server's loader is always accepted")
			.field(ConfigPath.of("advertise-versions-to-sync"), TomlValue.bool(true), "tell clients the pack Minecraft and loader versions")
			.field(ConfigPath.of("network", "connection-mode"), TomlValue.str(ModpackConnectionMode.HOLEPUNCH.name()),
					"HOLEPUNCH uses the Minecraft port. MAGIC can share it or use bind-port. HTTP uses bind-port.")
			.field(ConfigPath.of("network", "bind-address"), TomlValue.str(""))
			.field(ConfigPath.of("network", "bind-port"), TomlValue.integer(-1))
			.field(ConfigPath.of("network", "advertised-host"), TomlValue.str(""))
			.field(ConfigPath.of("network", "advertised-port"), TomlValue.integer(-1))
			.field(ConfigPath.of("network", "disable-internal-tls"), TomlValue.bool(false))
			.field(ConfigPath.of("network", "accept-proxy-protocol"), TomlValue.bool(false))
			.field(ConfigPath.of("network", "bandwidth-limit"), TomlValue.integer(0))
			.field(ConfigPath.of("secrets", "validate"), TomlValue.bool(true), "require a login-issued download secret")
			.field(ConfigPath.of("secrets", "lifetime-hours"), TomlValue.integer(336))
			.field(ConfigPath.of("nag", "enabled"), TomlValue.bool(true))
			.field(ConfigPath.of("nag", "message"), TomlValue.str("Install the AutoModpack mod to get this server's modpack!"))
			.field(ConfigPath.of("nag", "clickable-message"), TomlValue.str("Click here to get the AutoModpack!"))
			.field(ConfigPath.of("nag", "clickable-link"), TomlValue.str("https://modrinth.com/project/automodpack"))
			.field(ConfigPath.of("export-http", "directory"), TomlValue.str(""))
			.field(ConfigPath.of("export-http", "include-all"), TomlValue.bool(false))
			.comment(ConfigPath.of("modpack"),
					"host-modpack/<id>/ is included in full. from-server pulls extra files from the server root. exclude and editable apply after that; they do not add files.")
			.field(ConfigPath.of("modpack", "name"), TomlValue.str(""))
			.build();

	private static final TomlSchema GROUP_SCHEMA = TomlSchema.builder()
			.field(ConfigPath.of("display-name"), TomlValue.str(""))
			.field(ConfigPath.of("description"), TomlValue.str(""))
			.field(ConfigPath.of("selection"), TomlValue.str(GroupSelectionMode.OPTIONAL.name()),
					"REQUIRED is always installed. RECOMMENDED is pre-selected, players can opt out. OPTIONAL starts unchecked.")
			.field(ConfigPath.of("auto-exclude-server-side-mods"), TomlValue.str(ServerSideModExclusion.METADATA.name()),
					"DISABLED ships every jar. METADATA and PLATFORMS drop dedicated-server jars on a best-effort basis. Use exclude for anything else.")
			.field(ConfigPath.of("breaks-with"), TomlValue.array())
			.field(ConfigPath.of("requires"), TomlValue.array())
			.field(ConfigPath.of("compatible-platforms"), TomlValue.array())
			.field(ConfigPath.of("from-server"), TomlValue.array(), "extra paths from the server root")
			.field(ConfigPath.of("exclude"), TomlValue.array(), "drop these from the pack; they do not add files")
			.field(ConfigPath.of("editable"), TomlValue.array(), "pack files players may change; listing a path here does not add it to the pack")
			.build();

	private static final TomlSchema CLIENT_SCHEMA = TomlSchema.builder()
			.banner(BANNER)
			.field(ConfigPath.of("update-selected-modpack-on-launch"), TomlValue.bool(true), "fetch and apply the pack during launch")
			.field(ConfigPath.of("self-updater"), TomlValue.bool(false))
			.field(ConfigPath.of("sync-automodpack-version"), TomlValue.bool(true))
			.field(ConfigPath.of("sync-versions"), TomlValue.bool(true))
			.field(ConfigPath.of("play-music"), TomlValue.bool(true))
			.field(ConfigPath.of("show-modpack-settings-button"), TomlValue.bool(true))
			.field(ConfigPath.of("pinned-mod-ids"), TomlValue.array(), "mod ids that keep loading from this instance's own mods folder")
			.build();

	private TomlConfigs() {}

	/**
	 * Reads one human config; empty when neither it nor a legacy predecessor exists,
	 * {@link ConfigTools.ConfigParseException} with position when corrupt. Reading a canonical
	 * {@code .toml} never writes; only one-shot legacy migrations ({@code .conf} or {@code .json}) write.
	 */
	public static <T> Optional<T> read(Path path, Class<T> type) {
		if (Files.isRegularFile(path)) return Optional.of(readToml(path, type));
		Path confSource = legacyConfSource(path);
		if (confSource != null) return Optional.of(migrateLegacyConf(path, confSource, type));
		Path jsonSource = firstJsonSource(path);
		if (jsonSource != null) return Optional.of(migrateJson(path, jsonSource, type));
		return Optional.empty();
	}

	/**
	 * Reads or generates one human config; a fresh file is generated canonically with declared comments.
	 */
	public static <T> T readOrCreate(Path path, Class<T> type, Supplier<T> defaults) {
		Optional<T> existing = read(path, type);
		if (existing.isPresent()) return existing.get();
		T value = defaults.get();
		writeFresh(path, value, type);
		return value;
	}

	/**
	 * Saves by reconciliation: the current document is reconciled with {@code model} using
	 * {@link ReconcilePolicy#SYNC}, missing declared fields are materialized with their defaults and
	 * comments, and the result is written atomically. A corrupt existing file fails loudly.
	 */
	public static <T> void save(Path path, T model, Class<T> type, Supplier<T> defaults) throws IOException {
		Document document = prepareSaveDocument(path, model, type);
		OsPaths.requirePublishableConfig(path);
		DurableFiles.writeAtomic(path, document.text());
	}

	private static <T> Document prepareSaveDocument(Path path, T model, Class<T> type) throws IOException {
		if (!Files.isRegularFile(path)) {
			return freshDocument(model, type);
		}
		byte[] bytes = Files.readAllBytes(path);
		ParseResult result = TomlEdit.parse(bytes);
		if (!result.isOk()) {
			ParseError error = result.error();
			throw new ConfigTools.ConfigParseException("Cannot save " + path.getFileName() + ": the file is corrupt at line " + error.line() + ":"
					+ error.column() + " (" + error.kind() + "): " + error.message() + "; fix or remove the file and retry");
		}
		Document document = result.document();
		if (type == ServerConfigFieldsV3.class) {
			ServerConfigFieldsV3 server = (ServerConfigFieldsV3) model;
			SERVER_SCHEMA.apply(document, serverToTable(server), ReconcilePolicy.SYNC);
			ensureGroupDefaults(document, server);
		} else if (type == ClientConfigFieldsV3.class) {
			ClientConfigFieldsV3 client = (ClientConfigFieldsV3) model;
			CLIENT_SCHEMA.apply(document, clientToTable(client), ReconcilePolicy.SYNC);
		} else {
			throw new ConfigTools.ConfigException("Unsupported TOML config type: " + type.getSimpleName());
		}
		return document;
	}

	private static void ensureGroupDefaults(Document document, ServerConfigFieldsV3 server) {
		if (server.modpack == null || server.modpack.categories == null) return;
		for (var category : server.modpack.categories.entrySet()) {
			if (category.getValue() == null) continue;
			for (var group : category.getValue().entrySet()) {
				if (group.getValue() == null) continue;
				ConfigPath groupPath = ConfigPath.of("modpack", "groups", category.getKey(), group.getKey());
				GROUP_SCHEMA.ensureDefaults(document, groupPath);
			}
		}
	}

	// ------------------------------------------------------------------ TOML read / write

	@SuppressWarnings("unchecked")
	private static <T> T readToml(Path path, Class<T> type) {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(path);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to read configuration " + path.toAbsolutePath().normalize(), e);
		}
		Document document = parseDocument(path, bytes);
		try {
			if (type == ServerConfigFieldsV3.class) return (T) readServerTable(document.tree(), path);
			if (type == ClientConfigFieldsV3.class) return (T) readClientTable(document.tree(), path);
			throw new ConfigTools.ConfigException("Unsupported TOML config type: " + type.getSimpleName());
		} catch (TomlValidationException e) {
			throw new ConfigTools.ConfigParseException("Invalid configuration " + path.getFileName() + ": " + e.getMessage(), e);
		}
	}

	private static ServerConfigFieldsV3 readServerTable(TomlValue.Table tree, Path path) {
		ServerConfigFieldsV3 config = new ServerConfigFieldsV3();
		TableReader root = TableReader.of(tree);

		config.modpackHost = root.bool("modpack-host", config.modpackHost);
		config.generateModpackOnStart = root.bool("generate-modpack-on-start", config.generateModpackOnStart);
		config.requireModpack = root.bool("require-modpack", config.requireModpack);
		config.selfUpdater = root.bool("self-updater", config.selfUpdater);
		config.acceptedLoaders = root.stringSet("accepted-loaders", config.acceptedLoaders);
		config.advertiseVersionsToSync = root.bool("advertise-versions-to-sync", config.advertiseVersionsToSync);

		TableReader network = root.tableOrEmpty("network");
		config.connectionMode = network.enumVal("connection-mode", ModpackConnectionMode.class, config.connectionMode);
		config.bindAddress = network.string("bind-address", config.bindAddress);
		config.bindPort = network.intVal("bind-port", config.bindPort);
		config.advertisedEndpointHost = network.string("advertised-host", config.advertisedEndpointHost);
		config.advertisedEndpointPort = network.intVal("advertised-port", config.advertisedEndpointPort);
		config.disableInternalTLS = network.bool("disable-internal-tls", config.disableInternalTLS);
		config.acceptProxyProtocol = network.bool("accept-proxy-protocol", config.acceptProxyProtocol);
		config.bandwidthLimit = network.intVal("bandwidth-limit", config.bandwidthLimit);

		TableReader secrets = root.tableOrEmpty("secrets");
		config.validateSecrets = secrets.bool("validate", config.validateSecrets);
		config.secretLifetime = secrets.longVal("lifetime-hours", config.secretLifetime);

		TableReader nag = root.tableOrEmpty("nag");
		config.nagUnModdedClients = nag.bool("enabled", config.nagUnModdedClients);
		config.nagMessage = nag.string("message", config.nagMessage);
		config.nagClickableMessage = nag.string("clickable-message", config.nagClickableMessage);
		config.nagClickableLink = nag.string("clickable-link", config.nagClickableLink);

		TableReader exportHttp = root.tableOrEmpty("export-http");
		config.exportHttpDirectory = exportHttp.string("directory", config.exportHttpDirectory);
		config.exportHttpIncludeAll = exportHttp.bool("include-all", config.exportHttpIncludeAll);

		Optional<TableReader> modpackOpt = root.table("modpack");
		if (modpackOpt.isPresent()) {
			TableReader modpack = modpackOpt.get();
			ModpackFields fields = new ModpackFields();
			fields.name = modpack.string("name", "");
			Optional<TableReader> groupsOpt = modpack.table("groups");
			if (groupsOpt.isPresent()) {
				TableReader groupsTable = groupsOpt.get();
				Map<String, Map<String, GroupDeclaration>> categories = new LinkedHashMap<>();
				for (String categoryName : groupsTable.keys()) {
					TableReader catReader = groupsTable.table(categoryName).orElseThrow();
					Map<String, GroupDeclaration> catGroups = new LinkedHashMap<>();
					for (String groupId : catReader.keys()) {
						TableReader groupReader = catReader.table(groupId).orElseThrow();
						catGroups.put(groupId, readGroup(groupReader));
					}
					categories.put(categoryName, catGroups);
				}
				fields.categories = categories;
			} else {
				fields.categories = ModpackFields.withMainGroup().categories;
			}
			config.modpack = fields;
		}

		warnUnknownKeys(root, path);
		return config;
	}

	private static GroupDeclaration readGroup(TableReader r) {
		GroupDeclaration g = new GroupDeclaration();
		g.displayName = r.string("display-name", g.displayName);
		g.description = r.string("description", g.description);
		g.selection = r.enumVal("selection", GroupSelectionMode.class, g.selection);
		g.autoExcludeServerSideMods = r.enumVal("auto-exclude-server-side-mods", ServerSideModExclusion.class, g.autoExcludeServerSideMods);
		g.breaksWith = r.stringSet("breaks-with", g.breaksWith);
		g.requires = r.stringSet("requires", g.requires);
		g.compatiblePlatforms = r.stringSet("compatible-platforms", g.compatiblePlatforms);
		g.fromServer = r.stringSet("from-server", g.fromServer);
		g.exclude = r.stringSet("exclude", g.exclude);
		g.editable = r.stringSet("editable", g.editable);
		return g;
	}

	private static ClientConfigFieldsV3 readClientTable(TomlValue.Table tree, Path path) {
		ClientConfigFieldsV3 config = new ClientConfigFieldsV3();
		TableReader root = TableReader.of(tree);

		config.updateSelectedModpackOnLaunch = root.bool("update-selected-modpack-on-launch", config.updateSelectedModpackOnLaunch);
		config.selfUpdater = root.bool("self-updater", config.selfUpdater);
		config.syncAutoModpackVersion = root.bool("sync-automodpack-version", config.syncAutoModpackVersion);
		config.syncVersions = root.bool("sync-versions", config.syncVersions);
		config.playMusic = root.bool("play-music", config.playMusic);
		config.showModpackSettingsButton = root.bool("show-modpack-settings-button", config.showModpackSettingsButton);
		config.pinnedModIds = root.stringList("pinned-mod-ids", config.pinnedModIds);

		warnUnknownKeys(root, path);
		return config;
	}

	private static void warnUnknownKeys(TableReader root, Path path) {
		List<ConfigPath> unknown = root.unknownKeys();
		if (unknown.isEmpty()) return;
		List<String> names = new ArrayList<>(unknown.size());
		for (ConfigPath p : unknown) names.add(p.toString());
		LOGGER.warn("{}: unknown keys ignored: {}", path.getFileName(), String.join(", ", names));
	}

	private static TomlValue.Table serverToTable(ServerConfigFieldsV3 c) {
		TomlValue.Table root = TomlValue.table();
		root.put("modpack-host", TomlValue.bool(c.modpackHost));
		root.put("generate-modpack-on-start", TomlValue.bool(c.generateModpackOnStart));
		root.put("require-modpack", TomlValue.bool(c.requireModpack));
		root.put("self-updater", TomlValue.bool(c.selfUpdater));
		root.put("accepted-loaders", stringArray(c.acceptedLoaders));
		root.put("advertise-versions-to-sync", TomlValue.bool(c.advertiseVersionsToSync));

		ModpackConnectionMode mode = c.connectionMode != null ? c.connectionMode : ModpackConnectionMode.HOLEPUNCH;
		root.put("network", TomlValue.table()
				.put("connection-mode", TomlValue.str(mode.name()))
				.put("bind-address", TomlValue.str(nonNull(c.bindAddress)))
				.put("bind-port", TomlValue.integer(c.bindPort))
				.put("advertised-host", TomlValue.str(nonNull(c.advertisedEndpointHost)))
				.put("advertised-port", TomlValue.integer(c.advertisedEndpointPort))
				.put("disable-internal-tls", TomlValue.bool(c.disableInternalTLS))
				.put("accept-proxy-protocol", TomlValue.bool(c.acceptProxyProtocol))
				.put("bandwidth-limit", TomlValue.integer(c.bandwidthLimit)));

		root.put("secrets", TomlValue.table()
				.put("validate", TomlValue.bool(c.validateSecrets))
				.put("lifetime-hours", TomlValue.integer(c.secretLifetime)));

		root.put("nag", TomlValue.table()
				.put("enabled", TomlValue.bool(c.nagUnModdedClients))
				.put("message", TomlValue.str(nonNull(c.nagMessage)))
				.put("clickable-message", TomlValue.str(nonNull(c.nagClickableMessage)))
				.put("clickable-link", TomlValue.str(nonNull(c.nagClickableLink))));

		root.put("export-http", TomlValue.table()
				.put("directory", TomlValue.str(nonNull(c.exportHttpDirectory)))
				.put("include-all", TomlValue.bool(c.exportHttpIncludeAll)));

		ModpackFields mp = c.modpack != null ? c.modpack : ModpackFields.withMainGroup();
		TomlValue.Table modpackTable = TomlValue.table().put("name", TomlValue.str(nonNull(mp.name)));
		TomlValue.Table groupsTable = TomlValue.table();
		if (mp.categories != null) {
			for (var catEntry : mp.categories.entrySet()) {
				if (catEntry.getValue() == null) continue;
				TomlValue.Table catTable = TomlValue.table();
				for (var grpEntry : catEntry.getValue().entrySet()) {
					if (grpEntry.getValue() == null) continue;
					catTable.put(grpEntry.getKey(), groupToTable(grpEntry.getValue()));
				}
				groupsTable.put(catEntry.getKey(), catTable);
			}
		}
		modpackTable.put("groups", groupsTable);
		root.put("modpack", modpackTable);
		return root;
	}

	private static TomlValue.Table groupToTable(GroupDeclaration g) {
		GroupSelectionMode selection = g.selection != null ? g.selection : GroupSelectionMode.OPTIONAL;
		ServerSideModExclusion exclusion = g.autoExcludeServerSideMods != null ? g.autoExcludeServerSideMods : ServerSideModExclusion.METADATA;
		return TomlValue.table()
				.put("display-name", TomlValue.str(nonNull(g.displayName)))
				.put("description", TomlValue.str(nonNull(g.description)))
				.put("selection", TomlValue.str(selection.name()))
				.put("auto-exclude-server-side-mods", TomlValue.str(exclusion.name()))
				.put("breaks-with", stringArray(g.breaksWith))
				.put("requires", stringArray(g.requires))
				.put("compatible-platforms", stringArray(g.compatiblePlatforms))
				.put("from-server", stringArray(g.fromServer))
				.put("exclude", stringArray(g.exclude))
				.put("editable", stringArray(g.editable));
	}

	private static TomlValue.Table clientToTable(ClientConfigFieldsV3 c) {
		return TomlValue.table()
				.put("update-selected-modpack-on-launch", TomlValue.bool(c.updateSelectedModpackOnLaunch))
				.put("self-updater", TomlValue.bool(c.selfUpdater))
				.put("sync-automodpack-version", TomlValue.bool(c.syncAutoModpackVersion))
				.put("sync-versions", TomlValue.bool(c.syncVersions))
				.put("play-music", TomlValue.bool(c.playMusic))
				.put("show-modpack-settings-button", TomlValue.bool(c.showModpackSettingsButton))
				.put("pinned-mod-ids", stringArray(c.pinnedModIds));
	}

	private static TomlValue.Array stringArray(Collection<String> values) {
		TomlValue.Array arr = TomlValue.array();
		if (values != null) {
			for (String v : values) {
				if (v != null) arr.add(TomlValue.str(v));
			}
		}
		return arr;
	}

	private static String nonNull(String s) {
		return s == null ? "" : s;
	}

	private static <T> Document freshDocument(T model, Class<T> type) {
		byte[] bytes;
		if (type == ServerConfigFieldsV3.class) {
			ServerConfigFieldsV3 server = (ServerConfigFieldsV3) model;
			Comments.Builder comments = Comments.builder().banner(BANNER);
			SERVER_SCHEMA.addCommentsTo(comments, ConfigPath.root());
			if (server.modpack != null && server.modpack.categories != null) {
				for (var cat : server.modpack.categories.entrySet()) {
					if (cat.getValue() == null) continue;
					for (var grp : cat.getValue().entrySet()) {
						if (grp.getValue() == null) continue;
						GROUP_SCHEMA.addCommentsTo(comments, ConfigPath.of("modpack", "groups", cat.getKey(), grp.getKey()));
					}
				}
			}
			bytes = TomlEdit.canonical(serverToTable(server), comments.build());
		} else if (type == ClientConfigFieldsV3.class) {
			bytes = CLIENT_SCHEMA.canonical(clientToTable((ClientConfigFieldsV3) model));
		} else {
			throw new ConfigTools.ConfigException("Unsupported TOML config type: " + type.getSimpleName());
		}
		return TomlEdit.parse(bytes).document();
	}

	private static <T> void writeFresh(Path path, T model, Class<T> type) {
		try {
			OsPaths.requirePublishableConfig(path);
			DurableFiles.writeAtomic(path, freshDocument(model, type).text());
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to create configuration " + path.toAbsolutePath().normalize(), e);
		}
	}

	private static Document parseDocument(Path path, byte[] bytes) {
		ParseResult result = TomlEdit.parse(bytes);
		if (!result.isOk()) {
			ParseError error = result.error();
			throw new ConfigTools.ConfigParseException("Invalid configuration " + path.getFileName() + " at line " + error.line() + ":" + error.column() + " ("
					+ error.kind() + "): " + error.message());
		}
		return result.document();
	}

	// ------------------------------------------------------------------ one-shot legacy migrations (.conf and .json -> .toml)

	private static Path legacyConfSource(Path canonical) {
		String name = canonical.getFileName().toString();
		Path candidate = null;
		if (name.equals("server.toml")) candidate = canonical.resolveSibling(V5_RC_SERVER_CONFIG_FILE.getFileName());
		else if (name.equals("client.toml")) candidate = canonical.resolveSibling(V5_RC_CLIENT_CONFIG_FILE.getFileName());
		return candidate != null && Files.isRegularFile(candidate) ? candidate : null;
	}

	static List<Path> jsonSources(Path canonical) {
		String name = canonical.getFileName().toString();
		if (name.equals("server.toml")) return List.of(canonical.resolveSibling(V4_SERVER_CONFIG_FILE.getFileName()), canonical.resolveSibling(V4_SERVER_CONFIG_ALT_FILE.getFileName()));
		if (name.equals("client.toml")) return List.of(canonical.resolveSibling(V4_CLIENT_CONFIG_FILE.getFileName()), canonical.resolveSibling(V4_CLIENT_CONFIG_ALT_FILE.getFileName()));
		return List.of();
	}

	private static Path firstJsonSource(Path canonical) {
		for (Path source : jsonSources(canonical)) if (Files.isRegularFile(source)) return source;
		return null;
	}

	private static <T> T migrateLegacyConf(Path canonical, Path confSource, Class<T> type) {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(confSource);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to read legacy configuration " + confSource.toAbsolutePath().normalize(), e);
		}
		JsonObject object = LegacyConfParser.parse(bytes, confSource.getFileName().toString());
		T model = mapLegacyObject(canonical, confSource, object, type);
		LOGGER.info("Migrated legacy {} to {}", confSource.getFileName(), canonical.getFileName());
		return model;
	}

	private static <T> T migrateJson(Path canonical, Path jsonSource, Class<T> type) {
		JsonObject object = readJsonObject(jsonSource);
		T model = mapLegacyObject(canonical, jsonSource, object, type);
		LOGGER.info("Migrated legacy {} to {}", jsonSource.getFileName(), canonical.getFileName());
		return model;
	}

	@SuppressWarnings("unchecked")
	private static <T> T mapLegacyObject(Path canonical, Path legacySource, JsonObject object, Class<T> type) {
		T model;
		String followId = "";
		if (type == ServerConfigFieldsV3.class) {
			model = (T) HumanConfigMigration.mapServer(object);
		} else if (type == ClientConfigFieldsV3.class) {
			HumanConfigMigration.MappedClient mapped = HumanConfigMigration.mapClient(object);
			model = (T) mapped.config();
			followId = mapped.followId();
		} else {
			throw new ConfigTools.ConfigException("Cannot migrate " + legacySource.getFileName() + " to " + type.getSimpleName());
		}
		writeFresh(canonical, model, type);
		if (type == ClientConfigFieldsV3.class && followId != null && !followId.isBlank()) writeFollowId(canonical, followId);
		backupLegacy(legacySource);
		return model;
	}

	private static JsonObject readJsonObject(Path path) {
		String text;
		try {
			text = Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to read configuration " + path.toAbsolutePath().normalize(), e);
		}
		try {
			JsonElement tree = JsonParser.parseString(text);
			if (tree == null || !tree.isJsonObject()) throw new ConfigTools.ConfigParseException("Configuration JSON is not an object: " + path.getFileName());
			return tree.getAsJsonObject();
		} catch (JsonSyntaxException e) {
			throw new ConfigTools.ConfigParseException("Invalid JSON for " + path.getFileName(), e);
		}
	}

	private static void writeFollowId(Path clientToml, String followId) {
		Path selected = clientToml.resolveSibling(CLIENT_DIR.getFileName()).resolve(CLIENT_SELECTED_FILE.getFileName());
		ClientConfigJsons.SelectedModpackFields fields = new ClientConfigJsons.SelectedModpackFields();
		fields.modpackId = followId;
		try {
			Files.createDirectories(selected.getParent());
			ConfigTools.writeAtomic(selected, fields);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to write selected modpack " + selected.toAbsolutePath().normalize(), e);
		}
	}

	private static void backupLegacy(Path legacySource) {
		Path backup = uniqueBackup(legacySource);
		try {
			Files.move(legacySource, backup);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to rename " + legacySource.getFileName() + " to " + backup.getFileName(), e);
		}
	}

	private static Path uniqueBackup(Path source) {
		Path first = source.resolveSibling(source.getFileName() + ".backup");
		if (!Files.exists(first)) return first;
		for (int n = 2;; n++) {
			Path candidate = source.resolveSibling(source.getFileName() + ".backup-" + n);
			if (!Files.exists(candidate)) return candidate;
		}
	}
}
