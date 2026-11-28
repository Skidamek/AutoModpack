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
import pl.skidam.automodpack_core.config.ServerConfigJsons.CategoryDeclaration;
import pl.skidam.automodpack_core.config.ServerConfigJsons.GroupDeclaration;
import pl.skidam.automodpack_core.config.ServerConfigJsons.ModpackFields;
import pl.skidam.automodpack_core.config.ServerConfigJsons.ServerConfigFieldsV3;
import pl.skidam.automodpack_core.modpack.group.CategoryPickMode;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionMode;
import pl.skidam.automodpack_core.modpack.group.ServerSideModExclusion;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.OsPaths;
import pl.skidam.tomledit.ConfigPath;
import pl.skidam.tomledit.Document;
import pl.skidam.tomledit.ParseError;
import pl.skidam.tomledit.ParseResult;
import pl.skidam.tomledit.ReconcilePolicy;
import pl.skidam.tomledit.TableReader;
import pl.skidam.tomledit.TomlEdit;
import pl.skidam.tomledit.TomlException;
import pl.skidam.tomledit.TomlSchema;
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
			.comment(ConfigPath.of("modpack"),
					"host-modpack/<id>/ is included in full. from-server pulls extra files from the server root. exclude and editable apply to every group; groups can extend or negate (!) them.")
			.field(ConfigPath.of("modpack", "name"), TomlValue.str(""))
			.field(ConfigPath.of("modpack", "auto-exclude-server-side-mods"), TomlValue.str(ServerSideModExclusion.METADATA.name()),
					"DISABLED ships every jar. METADATA and PLATFORMS drop dedicated-server jars on a best-effort basis.")
			.field(ConfigPath.of("modpack", "exclude"), stringArray(ServerConfigJsons.FACTORY_EXCLUDE))
			.field(ConfigPath.of("modpack", "editable"), stringArray(ServerConfigJsons.FACTORY_EDITABLE))
			.dynamicTable(ConfigPath.of("categories"))
			.comment(ConfigPath.of("groups"), "Each group maps 1:1 to host-modpack/<id>/. selection: REQUIRED, RECOMMENDED, OPTIONAL, AUTOMATIC.")
			.dynamicTable(ConfigPath.of("groups"))
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

	/** Reads or generates one human config; a fresh file is generated canonically with declared comments. */
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
		byte[] bytes = prepareSaveBytes(path, model, type);
		OsPaths.requirePublishableConfig(path);
		DurableFiles.writeAtomic(path, bytes);
	}

	/** Inserts newly imported {@code [groups.<id>]} tables losslessly without altering existing entries or comments. */
	public static void insertImportedGroups(Path path, Map<String, GroupDeclaration> imported) throws IOException {
		if (imported == null || imported.isEmpty()) return;
		byte[] bytes;
		if (Files.isRegularFile(path)) {
			Document document = parseExistingForEdit(path);
			try {
				document.atomic(d -> {
					for (var entry : imported.entrySet()) {
						if (entry.getValue() == null) continue;
						d.ensure(ConfigPath.of("groups", entry.getKey()), groupToTable(entry.getValue(), null), null);
					}
				});
			} catch (TomlException e) {
				throw new ConfigTools.ConfigParseException("Cannot update " + path.getFileName() + ": " + e.getMessage(), e);
			}
			bytes = document.text();
		} else {
			ServerConfigFieldsV3 config = new ServerConfigFieldsV3();
			config.modpack.groups.putAll(imported);
			bytes = canonicalBytes(config, ServerConfigFieldsV3.class);
		}
		OsPaths.requirePublishableConfig(path);
		DurableFiles.writeAtomic(path, bytes);
	}

	private static Document parseExistingForEdit(Path path) throws IOException {
		byte[] bytes = Files.readAllBytes(path);
		ParseResult result = TomlEdit.parse(bytes);
		if (!result.isOk()) {
			ParseError error = result.error();
			throw new ConfigTools.ConfigParseException("Cannot save " + path.getFileName() + ": the file is corrupt at line " + error.line() + ":"
					+ error.column() + " (" + error.kind() + "): " + error.message() + "; fix or remove the file and retry");
		}
		return result.document();
	}

	private static <T> byte[] prepareSaveBytes(Path path, T model, Class<T> type) throws IOException {
		if (!Files.isRegularFile(path)) {
			return canonicalBytes(model, type);
		}
		Document document = parseExistingForEdit(path);
		try {
			if (type == ServerConfigFieldsV3.class) {
				ServerConfigFieldsV3 server = (ServerConfigFieldsV3) model;
				SERVER_SCHEMA.apply(document, serverToTable(server, document.tree()), ReconcilePolicy.SYNC);
			} else if (type == ClientConfigFieldsV3.class) {
				ClientConfigFieldsV3 client = (ClientConfigFieldsV3) model;
				CLIENT_SCHEMA.apply(document, clientToTable(client), ReconcilePolicy.SYNC);
			} else {
				throw new ConfigTools.ConfigException("Unsupported TOML config type: " + type.getSimpleName());
			}
		} catch (TomlException e) {
			throw new ConfigTools.ConfigParseException("Cannot save " + path.getFileName() + ": " + e.getMessage(), e);
		}
		return document.text();
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
			if (type == ServerConfigFieldsV3.class) return (T) readServerTable(document.reader(), path);
			if (type == ClientConfigFieldsV3.class) return (T) readClientTable(document.reader(), path);
			throw new ConfigTools.ConfigException("Unsupported TOML config type: " + type.getSimpleName());
		} catch (TomlException e) {
			throw new ConfigTools.ConfigParseException("Invalid configuration " + path.getFileName() + ": " + e.getMessage(), e);
		}
	}

	private static ServerConfigFieldsV3 readServerTable(TableReader root, Path path) {
		ServerConfigFieldsV3 config = new ServerConfigFieldsV3();

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
		Optional<Map<String, TableReader>> categoriesOpt = root.tableMap("categories");
		Optional<Map<String, TableReader>> groupsOpt = root.tableMap("groups");
		if (modpackOpt.isPresent() || categoriesOpt.isPresent() || groupsOpt.isPresent()) {
			ModpackFields fields = new ModpackFields();
			if (modpackOpt.isPresent()) {
				TableReader modpack = modpackOpt.get();
				fields.name = modpack.string("name", fields.name);
				fields.autoExcludeServerSideMods = modpack.enumVal("auto-exclude-server-side-mods", ServerSideModExclusion.class, fields.autoExcludeServerSideMods);
				fields.exclude = modpack.stringSet("exclude", fields.exclude);
				fields.editable = modpack.stringSet("editable", fields.editable);
			}
			categoriesOpt.ifPresent(map -> map.forEach((categoryId, catReader) -> fields.categories.put(categoryId, readCategory(catReader))));
			groupsOpt.ifPresent(map -> map.forEach((groupId, groupReader) -> fields.groups.put(groupId, readGroup(groupReader))));
			config.modpack = fields;
		}

		warnUnknownKeys(root, path);
		return config;
	}

	private static CategoryDeclaration readCategory(TableReader r) {
		CategoryDeclaration c = new CategoryDeclaration();
		c.name = r.string("name", c.name);
		c.description = r.string("description", c.description);
		c.pick = r.enumVal("pick", CategoryPickMode.class, c.pick);
		return c;
	}

	private static GroupDeclaration readGroup(TableReader r) {
		GroupDeclaration g = new GroupDeclaration();
		g.name = r.string("name", g.name);
		g.description = r.string("description", g.description);
		g.category = r.string("category", g.category);
		g.selection = r.enumVal("selection", GroupSelectionMode.class, g.selection);
		g.autoExcludeServerSideMods = r.enumVal("auto-exclude-server-side-mods", ServerSideModExclusion.class).orElse(null);
		g.requires = r.stringSet("requires", g.requires);
		g.conflicts = r.stringSet("conflicts", g.conflicts);
		g.compatiblePlatforms = r.stringSet("compatible-platforms", g.compatiblePlatforms);
		g.fromServer = r.stringSet("from-server", g.fromServer);
		g.exclude = r.stringSet("exclude", g.exclude);
		g.editable = r.stringSet("editable", g.editable);
		return g;
	}

	private static ClientConfigFieldsV3 readClientTable(TableReader root, Path path) {
		ClientConfigFieldsV3 config = new ClientConfigFieldsV3();

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

	private static TomlValue.Table serverToTable(ServerConfigFieldsV3 c, TomlValue.Table existingTree) {
		TomlValue.Table root = TomlValue.table();
		root.put("modpack-host", TomlValue.bool(c.modpackHost));
		root.put("generate-modpack-on-start", TomlValue.bool(c.generateModpackOnStart));
		root.put("require-modpack", TomlValue.bool(c.requireModpack));
		root.put("self-updater", TomlValue.bool(c.selfUpdater));
		root.put("accepted-loaders", stringArray(c.acceptedLoaders));
		root.put("advertise-versions-to-sync", TomlValue.bool(c.advertiseVersionsToSync));

		ModpackFields mp = c.modpack != null ? c.modpack : ModpackFields.withMainGroup();
		ServerSideModExclusion packExclusion = mp.autoExcludeServerSideMods != null ? mp.autoExcludeServerSideMods : ServerSideModExclusion.METADATA;
		root.put("modpack", TomlValue.table()
				.put("name", TomlValue.str(nonNull(mp.name)))
				.put("auto-exclude-server-side-mods", TomlValue.str(packExclusion.name()))
				.put("exclude", stringArray(mp.exclude))
				.put("editable", stringArray(mp.editable)));

		TomlValue.Table existingCategories = childTable(existingTree, "categories");
		if (mp.categories != null && !mp.categories.isEmpty()) {
			TomlValue.Table categoriesTable = TomlValue.table();
			for (var entry : mp.categories.entrySet()) {
				if (entry.getValue() == null) continue;
				categoriesTable.put(entry.getKey(), categoryToTable(entry.getValue(), childTable(existingCategories, entry.getKey())));
			}
			if (!categoriesTable.members().isEmpty() || existingCategories != null) root.put("categories", categoriesTable);
		}

		TomlValue.Table existingGroups = childTable(existingTree, "groups");
		if (mp.groups != null && !mp.groups.isEmpty()) {
			TomlValue.Table groupsTable = TomlValue.table();
			for (var entry : mp.groups.entrySet()) {
				if (entry.getValue() == null) continue;
				groupsTable.put(entry.getKey(), groupToTable(entry.getValue(), childTable(existingGroups, entry.getKey())));
			}
			if (!groupsTable.members().isEmpty() || existingGroups != null) root.put("groups", groupsTable);
		}

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
		return root;
	}

	private static TomlValue.Table categoryToTable(CategoryDeclaration cat, TomlValue.Table existing) {
		TomlValue.Table t = TomlValue.table();
		CategoryPickMode pick = cat.pick != null ? cat.pick : CategoryPickMode.ANY;
		if (hasKey(existing, "name") || !nonNull(cat.name).isEmpty()) t.put("name", TomlValue.str(nonNull(cat.name)));
		if (hasKey(existing, "description") || !nonNull(cat.description).isEmpty()) t.put("description", TomlValue.str(nonNull(cat.description)));
		if (hasKey(existing, "pick") || pick != CategoryPickMode.ANY || t.members().isEmpty()) t.put("pick", TomlValue.str(pick.name()));
		return t;
	}

	private static TomlValue.Table groupToTable(GroupDeclaration g, TomlValue.Table existing) {
		TomlValue.Table t = TomlValue.table();
		GroupSelectionMode selection = g.selection != null ? g.selection : GroupSelectionMode.OPTIONAL;
		if (hasKey(existing, "name") || !nonNull(g.name).isEmpty()) t.put("name", TomlValue.str(nonNull(g.name)));
		if (hasKey(existing, "description") || !nonNull(g.description).isEmpty()) t.put("description", TomlValue.str(nonNull(g.description)));
		if (hasKey(existing, "category") || !nonNull(g.category).isEmpty()) t.put("category", TomlValue.str(nonNull(g.category)));
		if (g.autoExcludeServerSideMods != null) t.put("auto-exclude-server-side-mods", TomlValue.str(g.autoExcludeServerSideMods.name()));
		if (hasKey(existing, "requires") || !isEmpty(g.requires)) t.put("requires", stringArray(g.requires));
		if (hasKey(existing, "conflicts") || !isEmpty(g.conflicts)) t.put("conflicts", stringArray(g.conflicts));
		if (hasKey(existing, "compatible-platforms") || !isEmpty(g.compatiblePlatforms)) t.put("compatible-platforms", stringArray(g.compatiblePlatforms));
		if (hasKey(existing, "from-server") || !isEmpty(g.fromServer)) t.put("from-server", stringArray(g.fromServer));
		if (hasKey(existing, "exclude") || !isEmpty(g.exclude)) t.put("exclude", stringArray(g.exclude));
		if (hasKey(existing, "editable") || !isEmpty(g.editable)) t.put("editable", stringArray(g.editable));
		if (hasKey(existing, "selection") || selection != GroupSelectionMode.OPTIONAL || t.members().isEmpty()) t.put("selection", TomlValue.str(selection.name()));
		return t;
	}

	private static TomlValue.Table childTable(TomlValue.Table parent, String key) {
		if (parent == null) return null;
		return parent.get(key) instanceof TomlValue.Table child ? child : null;
	}

	private static boolean hasKey(TomlValue.Table table, String key) {
		return table != null && table.get(key) != null;
	}

	private static boolean isEmpty(Collection<String> values) {
		return values == null || values.isEmpty();
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

	private static <T> byte[] canonicalBytes(T model, Class<T> type) {
		if (type == ServerConfigFieldsV3.class) return SERVER_SCHEMA.canonical(serverToTable((ServerConfigFieldsV3) model, null));
		if (type == ClientConfigFieldsV3.class) return CLIENT_SCHEMA.canonical(clientToTable((ClientConfigFieldsV3) model));
		throw new ConfigTools.ConfigException("Unsupported TOML config type: " + type.getSimpleName());
	}

	private static <T> void writeFresh(Path path, T model, Class<T> type) {
		try {
			OsPaths.requirePublishableConfig(path);
			DurableFiles.writeAtomic(path, canonicalBytes(model, type));
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
