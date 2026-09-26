package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.storage.StoragePaths.CLIENT_DIR;
import static pl.skidam.automodpack_core.storage.StoragePaths.CLIENT_SELECTED_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.V4_CLIENT_CONFIG_ALT_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.V4_CLIENT_CONFIG_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.V4_SERVER_CONFIG_ALT_FILE;
import static pl.skidam.automodpack_core.storage.StoragePaths.V4_SERVER_CONFIG_FILE;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.OsPaths;
import pl.skidam.reconf.Comments;
import pl.skidam.reconf.ConfigPath;
import pl.skidam.reconf.Document;
import pl.skidam.reconf.ParseError;
import pl.skidam.reconf.ParseResult;
import pl.skidam.reconf.Reconf;
import pl.skidam.reconf.Value;

/**
 * The reconf-backed store for the human-editable configs (server and client config; the embedding contract of
 * RECONF-SPEC §13). JSON predecessors are mapped once on load into a fresh canonical {@code .conf}; after that only
 * {@code .conf} is read. Saves reconcile the current document so user comments, blank lines, layout and line endings
 * survive. Machine-owned state documents stay on {@link ConfigTools} Gson serialization.
 *
 * <p>
 * A field annotated with {@link Comment} gets its comment emitted on fresh generation and is materialized by
 * {@code ensure} (with that comment) when an existing file lacks it.
 */
public final class ReconfConfigs {
	private ReconfConfigs() {}

	/** Marks a config field as documented: the comment is emitted on fresh generation and ensured into old files. */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Comment {
		String value();
	}

	private static final String BANNER = "AutoModpack configuration. Docs: https://moddedmc.wiki/en/project/automodpack/docs";

	/** Reads one human config; empty when neither it nor a JSON predecessor exists, {@link ConfigTools.ConfigParseException} with position when it is corrupt. */
	public static <T> Optional<T> read(Path path, Class<T> type) {
		if (Files.isRegularFile(path)) return Optional.of(readConf(path, type));
		Path jsonSource = firstJsonSource(path);
		if (jsonSource == null) return Optional.empty();
		return Optional.of(migrateJson(path, jsonSource, type));
	}

	/** Reads or generates one human config; a fresh file is generated canonically with the {@link Comment} declarations. */
	public static <T> T readOrCreate(Path path, Class<T> type, Supplier<T> defaults) {
		Optional<T> existing = read(path, type);
		if (existing.isPresent()) return existing.get();
		T value = defaults.get();
		writeFresh(path, value, type);
		return value;
	}

	/**
	 * Saves by reconciliation: the current document (kept byte-exact outside edited spans) is reconciled with the
	 * model, missing {@link Comment} fields are ensured, and the result is written atomically. A corrupt existing
	 * file fails this save loudly instead of being overwritten - the user's file cannot be reconciled and silently
	 * regenerating it would hide the defect.
	 */
	public static <T> void save(Path path, T model, Class<T> type, Supplier<T> defaults) throws IOException {
		Document document;
		if (!Files.isRegularFile(path)) {
			document = freshDocument(model, type);
		} else {
			byte[] bytes = Files.readAllBytes(path);
			ParseResult result = Reconf.parse(bytes);
			if (!result.isOk()) {
				ParseError error = result.error();
				throw new ConfigTools.ConfigParseException("Cannot save " + path.getFileName() + ": the file is corrupt at line " + error.line() + ":"
						+ error.column() + " (" + error.kind() + "): " + error.message() + "; fix or remove the file and retry");
			}
			document = result.document();
		}
		Value.Obj tree = modelTree(model);
		document.reconcile(tree);
		setArrays(document, tree, document.tree(), ConfigPath.root());
		ensureDeclared(document, type, defaults.get());
		ensureGroupListComments(document, model);
		OsPaths.requirePublishableConfig(path);
		DurableFiles.writeAtomic(path, document.text());
	}

	/**
	 * Sets every array member from the model, walking both trees in parallel. Reconcile is insert-only (§9.5): it
	 * never deletes, which is right for a model built from scratch, but this model was read from the very file being
	 * saved - so every element the model lacks is a deliberate removal (a normalized-away rule, an unpinned mod),
	 * and letting it stand would resurrect it on the next read. The model carries the user's own elements untouched;
	 * only the program's deletions are applied.
	 */
	// the reconf path type cannot take the short name here: java.nio.file.Path owns it in this class
	private static void setArrays(Document document, Value model, Value current, ConfigPath path) {
		if (model instanceof Value.Arr arr) {
			if (current instanceof Value.Arr) document.set(path, arr);
			return;
		}
		if (!(model instanceof Value.Obj modelObj) || !(current instanceof Value.Obj currentObj)) return;
		for (var entry : modelObj.members.entrySet()) {
			Value currentMember = currentObj.members.get(entry.getKey());
			if (currentMember == null) continue; // absent from the file: ensureDeclared materializes the declared ones
			setArrays(document, entry.getValue(), currentMember, path.appended(entry.getKey()));
		}
	}

	static List<Path> jsonSources(Path canonical) {
		String name = canonical.getFileName().toString();
		if (name.equals("server.conf")) return List.of(canonical.resolveSibling(V4_SERVER_CONFIG_FILE.getFileName()), canonical.resolveSibling(V4_SERVER_CONFIG_ALT_FILE.getFileName()));
		if (name.equals("client.conf")) return List.of(canonical.resolveSibling(V4_CLIENT_CONFIG_FILE.getFileName()), canonical.resolveSibling(V4_CLIENT_CONFIG_ALT_FILE.getFileName()));
		return List.of();
	}

	private static Path firstJsonSource(Path canonical) {
		for (Path source : jsonSources(canonical)) if (Files.isRegularFile(source)) return source;
		return null;
	}

	private static <T> T readConf(Path path, Class<T> type) {
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(path);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to read configuration " + path.toAbsolutePath().normalize(), e);
		}
		String json = parseJson(path, bytes);
		List<String> unknown = ConfigTools.unknownKeys(json, type);
		if (!unknown.isEmpty()) LOGGER.warn("{}: unknown keys ignored: {}", path.getFileName(), String.join(", ", unknown));
		return ConfigTools.parse(json, type);
	}

	private static <T> T migrateJson(Path canonical, Path jsonSource, Class<T> type) {
		JsonObject object = readJsonObject(jsonSource);
		T model;
		String followId = "";
		if (type == ServerConfigJsons.ServerConfigFieldsV3.class) {
			@SuppressWarnings("unchecked")
			T mapped = (T) HumanConfigMigration.mapServer(object);
			model = mapped;
		} else if (type == ClientConfigJsons.ClientConfigFieldsV3.class) {
			HumanConfigMigration.MappedClient mapped = HumanConfigMigration.mapClient(object);
			@SuppressWarnings("unchecked")
			T config = (T) mapped.config();
			model = config;
			followId = mapped.followId();
		} else {
			throw new ConfigTools.ConfigException("Cannot migrate " + jsonSource.getFileName() + " to " + type.getSimpleName());
		}
		writeFresh(canonical, model, type);
		if (type == ClientConfigJsons.ClientConfigFieldsV3.class && followId != null && !followId.isBlank()) writeFollowId(canonical, followId);
		backupJson(jsonSource);
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

	private static void writeFollowId(Path clientConf, String followId) {
		Path selected = clientConf.resolveSibling(CLIENT_DIR.getFileName()).resolve(CLIENT_SELECTED_FILE.getFileName());
		ClientConfigJsons.SelectedModpackFields fields = new ClientConfigJsons.SelectedModpackFields();
		fields.modpackId = followId;
		try {
			Files.createDirectories(selected.getParent());
			ConfigTools.writeAtomic(selected, fields);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to write selected modpack " + selected.toAbsolutePath().normalize(), e);
		}
	}

	private static void backupJson(Path jsonSource) {
		Path backup = uniqueBackup(jsonSource);
		try {
			Files.move(jsonSource, backup);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to rename " + jsonSource.getFileName() + " to " + backup.getFileName(), e);
		}
	}

	private static Path uniqueBackup(Path jsonSource) {
		Path first = jsonSource.resolveSibling(jsonSource.getFileName() + ".backup");
		if (!Files.exists(first)) return first;
		for (int n = 2;; n++) {
			Path candidate = jsonSource.resolveSibling(jsonSource.getFileName() + ".backup-" + n);
			if (!Files.exists(candidate)) return candidate;
		}
	}

	/** Generates one fresh document: canonical reconf with the banner and the {@link Comment} declarations. */
	private static <T> Document freshDocument(T model, Class<T> type) {
		Value.Obj root = modelTree(model);
		Map<String, String> byKey = new LinkedHashMap<>();
		for (Field field : type.getDeclaredFields()) {
			Comment comment = field.getAnnotation(Comment.class);
			if (comment != null) byKey.put(serializedName(field), comment.value());
		}
		Document document = Reconf.parse(Reconf.canonical(root, Comments.of(BANNER, byKey))).document();
		ensureGroupListComments(document, model);
		return document;
	}

	/** Ensures every {@link Comment} field declared in {@code type} exists in the document, materializing with the declared default and comment. */
	private static void ensureDeclared(Document document, Class<?> type, Object defaults) {
		for (Field field : type.getDeclaredFields()) {
			Comment comment = field.getAnnotation(Comment.class);
			if (comment == null) continue;
			try {
				Value defaultValue = toJsonValue(ConfigTools.GSON.toJsonTree(field.get(defaults)));
				document.ensure(ConfigPath.of(serializedName(field)), defaultValue, comment.value());
			} catch (IllegalAccessException e) {
				throw new ConfigTools.ConfigException("Cannot read default of annotated config field " + field.getName(), e);
			}
		}
	}

	private static void ensureGroupListComments(Document document, Object model) {
		if (!(model instanceof ServerConfigJsons.ServerConfigFieldsV3 server) || server.modpack == null || server.modpack.categories == null) return;
		for (var category : server.modpack.categories.entrySet()) {
			if (category.getValue() == null) continue;
			for (var group : category.getValue().entrySet()) {
				ServerConfigJsons.GroupDeclaration declaration = group.getValue();
				if (declaration == null) continue;
				ensureGroupList(document, ConfigPath.of("modpack", category.getKey(), group.getKey()), declaration);
			}
		}
	}

	private static void ensureGroupList(Document document, ConfigPath groupPath, ServerConfigJsons.GroupDeclaration declaration) {
		for (Field field : ServerConfigJsons.GroupDeclaration.class.getDeclaredFields()) {
			Comment comment = field.getAnnotation(Comment.class);
			if (comment == null) continue;
			try {
				Value value = toJsonValue(ConfigTools.GSON.toJsonTree(field.get(declaration)));
				ConfigPath path = groupPath.appended(serializedName(field));
				document.ensure(path, value, comment.value());
				if (document.attachedComment(path).isEmpty()) document.setComment(path, comment.value());
			} catch (IllegalAccessException e) {
				throw new ConfigTools.ConfigException("Cannot read group list " + field.getName(), e);
			}
		}
	}

	private static String serializedName(Field field) {
		SerializedName name = field.getAnnotation(SerializedName.class);
		return name == null ? field.getName() : name.value();
	}

	private static <T> void writeFresh(Path path, T model, Class<T> type) {
		try {
			OsPaths.requirePublishableConfig(path);
			DurableFiles.writeAtomic(path, freshDocument(model, type).text());
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to create configuration " + path.toAbsolutePath().normalize(), e);
		}
	}

	/** The model as an reconf object tree, via Gson's tree (declaration order preserved, transient fields skipped). */
	private static <T> Value.Obj modelTree(T model) {
		JsonElement tree = ConfigTools.GSON.toJsonTree(model);
		if (!(tree instanceof JsonObject object)) throw new ConfigTools.ConfigException("Config model must serialize to an object");
		return (Value.Obj) toJsonValue(object);
	}

	/** The reconf document as Gson-model JSON: the string path of {@link ConfigTools} (strict stream reader) does the binding. */
	private static String parseJson(Path path, byte[] bytes) {
		ParseResult result = Reconf.parse(bytes);
		if (!result.isOk()) {
			ParseError error = result.error();
			throw new ConfigTools.ConfigParseException("Invalid configuration " + path.getFileName() + " at line " + error.line() + ":" + error.column() + " ("
					+ error.kind() + "): " + error.message());
		}
		Value tree = result.document().tree();
		return ConfigTools.GSON.toJson(toJsonObject((Value.Obj) tree));
	}

	// The bridge (RECONF-SPEC §13): reconf tree <-> Gson tree. Numbers travel as their exact literal text in both directions.

	static JsonElement toJsonObject(Value.Obj obj) {
		JsonObject out = new JsonObject();
		for (var entry : obj.members.entrySet()) out.add(entry.getKey(), toJsonElement(entry.getValue()));
		return out;
	}

	static JsonElement toJsonElement(Value value) {
		if (value instanceof Value.Obj obj) return toJsonObject(obj);
		if (value instanceof Value.Arr arr) {
			JsonArray out = new JsonArray();
			for (Value element : arr.elements) out.add(toJsonElement(element));
			return out;
		}
		if (value instanceof Value.Str str) return new JsonPrimitive(str.value());
		if (value instanceof Value.Num num) return JsonParser.parseString(num.literal());
		if (value instanceof Value.Bool bool) return new JsonPrimitive(bool.value());
		return JsonNull.INSTANCE;
	}

	static Value toJsonValue(JsonElement element) {
		if (element.isJsonObject()) {
			Value.Obj out = Value.obj();
			for (var entry : element.getAsJsonObject().entrySet()) out.put(entry.getKey(), toJsonValue(entry.getValue()));
			return out;
		}
		if (element.isJsonArray()) {
			Value.Arr out = Value.arr();
			for (JsonElement item : element.getAsJsonArray()) out.add(toJsonValue(item));
			return out;
		}
		JsonPrimitive primitive = element.getAsJsonPrimitive();
		if (primitive.isBoolean()) return Value.bool(primitive.getAsBoolean());
		if (primitive.isNumber()) return Value.num(primitive.getAsString()); // the raw literal, never through a double
		if (primitive.isString()) return Value.str(primitive.getAsString());
		return Value.nul();
	}
}
