package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.reflect.Field;
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

import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.OsPaths;
import pl.skidam.hconf.Comments;
import pl.skidam.hconf.ConfigPath;
import pl.skidam.hconf.Document;
import pl.skidam.hconf.Hconf;
import pl.skidam.hconf.ParseError;
import pl.skidam.hconf.ParseResult;
import pl.skidam.hconf.Value;

/**
 * The hconf-backed store for the human-editable configs (server and client config; the embedding contract of
 * HCONF-SPEC §13). Reads accept both the historical Gson-written JSON files and hand-edited hconf - claim 1 makes
 * them one format family. Saves never rewrite the file wholesale: the current document is reconciled with the
 * model, so user comments, blank lines, layout and line endings survive every programmatic change by construction.
 * Machine-owned state documents stay on {@link ConfigTools} Gson serialization.
 *
 * <p>
 * A field annotated with {@link Comment} gets its comment emitted on fresh generation and is materialized by
 * {@code ensure} (with that comment) when an existing file lacks it - the release-to-release convergence of the
 * spec's embedding contract. Unannotated fields are reconcile-only and never re-materialize.
 */
public final class HconfConfigs {
	private HconfConfigs() {}

	/** Marks a config field as documented: the comment is emitted on fresh generation and ensured into old files. */
	@Retention(RetentionPolicy.RUNTIME)
	@Target(ElementType.FIELD)
	public @interface Comment {
		String value();
	}

	private static final String BANNER = "AutoModpack configuration - your edits and comments survive updates. Docs: https://moddedmc.wiki/en/project/automodpack/docs Discord: https://discord.gg/hS6aMyeA9P";

	/** Reads one human config; empty when neither it nor its pre-hconf {@code .json} predecessor exists, {@link ConfigTools.ConfigParseException} with position when it is corrupt. */
	public static <T> Optional<T> read(Path path, Class<T> type) {
		Path effective = Files.isRegularFile(path) ? path : legacyPath(path);
		if (!Files.isRegularFile(effective)) return Optional.empty();
		byte[] bytes;
		try {
			bytes = Files.readAllBytes(effective);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to read configuration " + effective.toAbsolutePath().normalize(), e);
		}
		String json = parseJson(effective, bytes);
		List<String> unknown = ConfigTools.unknownKeys(json, type);
		if (!unknown.isEmpty()) LOGGER.warn("{}: unknown keys ignored: {}", path.getFileName(), String.join(", ", unknown));
		return Optional.of(ConfigTools.parse(json, type));
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
		Path legacy = legacyPath(path);
		byte[] legacyBytes = !Files.isRegularFile(path) && Files.isRegularFile(legacy) ? Files.readAllBytes(legacy) : null;
		byte[] bytes = Files.isRegularFile(path) ? Files.readAllBytes(path) : legacyBytes;
		Document document;
		if (bytes == null) {
			document = freshDocument(model, type);
		} else if (legacyBytes != null) {
			// format migration (one-time, json -> hconf): the model was read through the legacy
			// fallback, so generating fresh carries every current value into the documented canonical
			// file; the old file goes away only after the new one is written
			ParseResult legacyResult = Hconf.parse(legacyBytes);
			if (!legacyResult.isOk()) {
				ParseError error = legacyResult.error();
				throw new ConfigTools.ConfigParseException("Cannot migrate " + legacy.getFileName() + ": the file is corrupt at line " + error.line() + ":"
						+ error.column() + " (" + error.kind() + "): " + error.message() + "; fix or remove the file and retry");
			}
			document = freshDocument(model, type);
		} else {
			ParseResult result = Hconf.parse(bytes);
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
		OsPaths.requirePublishableConfig(path);
		DurableFiles.writeAtomic(path, document.text());
		if (legacyBytes != null) Files.deleteIfExists(legacy);
	}

	/**
	 * Sets every array member from the model, walking both trees in parallel. Reconcile is insert-only (§9.5): it
	 * never deletes, which is right for a model built from scratch, but this model was read from the very file being
	 * saved - so every element the model lacks is a deliberate removal (a normalized-away rule, an unpinned mod),
	 * and letting it stand would resurrect it on the next read. The model carries the user's own elements untouched;
	 * only the program's deletions are applied.
	 */
	// the hconf path type cannot take the short name here: java.nio.file.Path owns it in this class
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

	/** The pre-hconf {@code .json} name of a config file; the read fallback and the save migration source. */
	private static Path legacyPath(Path path) {
		String name = path.getFileName().toString();
		int dot = name.lastIndexOf('.');
		return path.resolveSibling((dot > 0 ? name.substring(0, dot) : name) + ".json");
	}

	/** Generates one fresh document: canonical hconf with the banner and the {@link Comment} declarations. */
	private static <T> Document freshDocument(T model, Class<T> type) {
		Value.Obj root = modelTree(model);
		Map<String, String> byKey = new LinkedHashMap<>();
		for (Field field : type.getDeclaredFields()) {
			Comment comment = field.getAnnotation(Comment.class);
			if (comment != null) byKey.put(field.getName(), comment.value());
		}
		return Hconf.parse(Hconf.canonical(root, Comments.of(BANNER, byKey))).document();
	}

	/** Ensures every {@link Comment} field declared in {@code type} exists in the document, materializing with the declared default and comment. */
	private static void ensureDeclared(Document document, Class<?> type, Object defaults) {
		for (Field field : type.getDeclaredFields()) {
			Comment comment = field.getAnnotation(Comment.class);
			if (comment == null) continue;
			try {
				Value defaultValue = toJsonValue(ConfigTools.GSON.toJsonTree(field.get(defaults)));
				document.ensure(ConfigPath.of(field.getName()), defaultValue, comment.value());
			} catch (IllegalAccessException e) {
				throw new ConfigTools.ConfigException("Cannot read default of annotated config field " + field.getName(), e);
			}
		}
	}

	private static <T> void writeFresh(Path path, T model, Class<T> type) {
		try {
			OsPaths.requirePublishableConfig(path);
			DurableFiles.writeAtomic(path, freshDocument(model, type).text());
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to create configuration " + path.toAbsolutePath().normalize(), e);
		}
	}

	/** The model as an hconf object tree, via Gson's tree (declaration order preserved, transient fields skipped). */
	private static <T> Value.Obj modelTree(T model) {
		JsonElement tree = ConfigTools.GSON.toJsonTree(model);
		if (!(tree instanceof JsonObject object)) throw new ConfigTools.ConfigException("Config model must serialize to an object");
		return (Value.Obj) toJsonValue(object);
	}

	/** The hconf document as Gson-model JSON: the string path of {@link ConfigTools} (strict stream reader) does the binding. */
	private static String parseJson(Path path, byte[] bytes) {
		ParseResult result = Hconf.parse(bytes);
		if (!result.isOk()) {
			ParseError error = result.error();
			throw new ConfigTools.ConfigParseException("Invalid configuration " + path.getFileName() + " at line " + error.line() + ":" + error.column() + " ("
					+ error.kind() + "): " + error.message());
		}
		Value tree = result.document().tree();
		return ConfigTools.GSON.toJson(toJsonObject((Value.Obj) tree));
	}

	// The bridge (HCONF-SPEC §13): hconf tree <-> Gson tree. Numbers travel as their exact literal text in both directions.

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
