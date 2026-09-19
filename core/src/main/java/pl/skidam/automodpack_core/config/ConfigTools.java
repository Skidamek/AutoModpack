package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import com.google.gson.JsonSyntaxException;
import com.google.gson.annotations.SerializedName;

import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.OsPaths;

public final class ConfigTools {
	/** The custom JSON shapes registered on {@link #GSON}; the same set defines which types key-reflection may not inspect. */
	private static final Map<Class<?>, Object> CUSTOM_JSON_ADAPTERS = Map.of(InetSocketAddress.class, new InetSocketAddressTypeAdapter(), ConnectionJsons.ConnectionInfo.class,
			new ConnectionInfoTypeAdapter(), ConnectionJsons.CertificateTrustEntry.class, new CertificateTrustEntryTypeAdapter());

	/**
	 * Stream-reader strictness for the integral types, registered because Gson 2.8.9 deserializing from a parsed tree
	 * instead of a string narrows every number through a double and silently truncates out-of-range or fractional
	 * literals — a hand-edited or corrupt config must fail loudly at parse, never take a truncated port or size.
	 */
	private static final Map<Class<?>, Object> STRICT_INTEGRAL_DESERIALIZERS = Map.of(byte.class, StrictIntegralDeserializer.BYTE, Byte.class, StrictIntegralDeserializer.BYTE,
			short.class, StrictIntegralDeserializer.SHORT, Short.class, StrictIntegralDeserializer.SHORT, int.class, StrictIntegralDeserializer.INT, Integer.class,
			StrictIntegralDeserializer.INT, long.class, StrictIntegralDeserializer.LONG, Long.class, StrictIntegralDeserializer.LONG);

	public static final Gson GSON = buildGson();

	/** Strict-enums Gson without pretty printing, for JSON-lines documents where one line is one record. */
	public static final Gson COMPACT = strictEnums(new GsonBuilder().disableHtmlEscaping()).create();

	private ConfigTools() {}

	private static Gson buildGson() {
		GsonBuilder builder = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting();
		CUSTOM_JSON_ADAPTERS.forEach(builder::registerTypeAdapter);
		STRICT_INTEGRAL_DESERIALIZERS.forEach(builder::registerTypeAdapter);
		return strictEnums(builder).create();
	}

	/** Registers the strict enum adapter every durable-state Gson must carry, so unknown enum names fail loudly instead of deserializing to null. */
	public static GsonBuilder strictEnums(GsonBuilder builder) {
		return builder.registerTypeHierarchyAdapter(Enum.class, new StrictEnumTypeAdapter());
	}

	public static <T> Optional<T> read(Path path, Class<T> type) {
		if (!Files.isRegularFile(path)) return Optional.empty();
		String json;
		try {
			json = Files.readString(path, StandardCharsets.UTF_8);
		} catch (IOException e) {
			throw new ConfigException("Failed to read configuration " + path.toAbsolutePath().normalize(), e);
		}
		JsonElement tree = parseTree(json, type);
		T value = deserialize(tree, type);
		List<String> unknown = unknownKeys(tree, type);
		if (!unknown.isEmpty()) LOGGER.warn("{}: unknown keys ignored: {}", path.getFileName(), String.join(", ", unknown));
		return Optional.of(value);
	}

	public static <T> T readOrCreate(Path path, Class<T> type, Supplier<T> defaults) {
		Optional<T> existing = read(path, type);
		if (existing.isPresent()) return existing.get();
		T value = defaults.get();
		try {
			writeAtomic(path, value);
			return value;
		} catch (IOException e) {
			throw new ConfigException("Failed to create configuration " + path.toAbsolutePath().normalize(), e);
		}
	}

	/**
	 * Reads one persisted-but-rebuildable state document: a missing file reads as empty, unusable content (including a
	 * non-regular path occupying the name) is set aside as evidence and also reads as empty, and only real IO trouble
	 * of a regular file propagates. The mapper folds every content validation in; the state's owner stays the sole
	 * authority on what its document must look like. A failed aside is IO trouble, never empty.
	 */
	public static <F, S> Optional<S> readState(Path path, Class<F> type, String description, Function<F, S> fromFields) throws IOException {
		return readPersisted(path, type, description, fromFields, PersistFate.REBUILDABLE);
	}

	/**
	 * Reads unique client history (baseline, vault, overlay tombstones, the active pointer): missing still means
	 * never written, and unusable content fails this boot in place. No aside happens, so the evidence stays where
	 * the owner wrote it and every later boot keeps failing with the same cause instead of reading the history as
	 * empty; moving, fixing, or deleting the file is the explicit human decision that unblocks the next boot. Real
	 * IO trouble of a regular file propagates the same way.
	 */
	public static <F, S> Optional<S> readUnique(Path path, Class<F> type, String description, Function<F, S> fromFields) throws IOException {
		return readPersisted(path, type, description, fromFields, PersistFate.UNIQUE);
	}

	private enum PersistFate {
		REBUILDABLE, UNIQUE
	}

	private static <F, S> Optional<S> readPersisted(Path path, Class<F> type, String description, Function<F, S> fromFields, PersistFate fate) throws IOException {
		if (!Files.exists(path, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
		boolean rebuildable = fate == PersistFate.REBUILDABLE;
		if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
			IOException unusable = new IOException(description + " is not a regular file: " + path);
			if (!rebuildable) throw unusable;
			DurableFiles.setAside(path, description, unusable);
			return Optional.empty();
		}
		try {
			return Optional.of(fromFields.apply(readDocument(path, type, description)));
		} catch (IOException e) {
			throw e;
		} catch (RuntimeException e) {
			if (!rebuildable) throw new IOException(description + " is unusable; it fails every boot until a human moves, fixes, or deletes it: " + path, e);
			DurableFiles.setAside(path, description, e);
			return Optional.empty();
		}
	}

	/** Reads one regular file as JSON without wrapping physical IO in {@link ConfigException}. */
	private static <T> T readDocument(Path path, Class<T> type, String description) throws IOException {
		String json = Files.readString(path, StandardCharsets.UTF_8);
		if (json.isBlank()) throw new ConfigParseException(description + " is empty: " + path);
		JsonElement tree = parseTree(json, type);
		T value = deserialize(tree, type);
		List<String> unknown = unknownKeys(tree, type);
		if (!unknown.isEmpty()) LOGGER.warn("{}: unknown keys ignored: {}", path.getFileName(), String.join(", ", unknown));
		return value;
	}

	public static <T> T parse(String json, Class<T> type) {
		return deserialize(parseTree(json, type), type);
	}

	private static JsonElement parseTree(String json, Class<?> type) {
		if (json == null) throw new ConfigParseException("Configuration JSON is null");
		try {
			return JsonParser.parseString(json);
		} catch (JsonParseException e) {
			throw new ConfigParseException("Invalid JSON for " + type.getSimpleName(), e);
		}
	}

	private static <T> T deserialize(JsonElement tree, Class<T> type) {
		try {
			T value = GSON.fromJson(tree, type);
			if (value == null) throw new ConfigParseException("Configuration JSON produced null for " + type.getSimpleName());
			return value;
		} catch (JsonParseException e) {
			throw new ConfigParseException("Invalid JSON for " + type.getSimpleName(), e);
		}
	}

	/** JSON paths present in the document that match no field of the target class, so Gson silently drops them; empty for invalid JSON, which {@link #parse} reports instead. */
	public static List<String> unknownKeys(String json, Class<?> type) {
		try {
			return unknownKeys(JsonParser.parseString(json), type);
		} catch (JsonParseException e) {
			return List.of();
		}
	}

	private static List<String> unknownKeys(JsonElement tree, Class<?> type) {
		List<String> unknown = new ArrayList<>();
		collectUnknownKeys(tree, type, "", unknown);
		return unknown;
	}

	private static void collectUnknownKeys(JsonElement element, Type type, String prefix, List<String> unknown) {
		if (type instanceof ParameterizedType parameterized) {
			Class<?> raw = (Class<?>) parameterized.getRawType();
			Type[] arguments = parameterized.getActualTypeArguments();
			if (element.isJsonArray() && Collection.class.isAssignableFrom(raw)) {
				JsonArray array = element.getAsJsonArray();
				for (int index = 0; index < array.size(); index++) collectUnknownKeys(array.get(index), arguments[0], prefix + "[" + index + "]", unknown);
			} else if (element.isJsonObject() && Map.class.isAssignableFrom(raw) && arguments[0] == String.class) {
				for (var entry : element.getAsJsonObject().entrySet()) collectUnknownKeys(entry.getValue(), arguments[1], prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey(), unknown);
			}
			return;
		}
		if (!(type instanceof Class<?> raw) || !isInspectable(raw) || !element.isJsonObject()) return;
		Map<String, Field> fields = jsonFieldNames(raw);
		for (var entry : element.getAsJsonObject().entrySet()) {
			String path = prefix.isEmpty() ? entry.getKey() : prefix + "." + entry.getKey();
			Field field = fields.get(entry.getKey());
			if (field == null) unknown.add(path);
			else collectUnknownKeys(entry.getValue(), field.getGenericType(), path, unknown);
		}
	}

	/** Types key-reflection may not inspect, derived from the {@link #CUSTOM_JSON_ADAPTERS} registry; JSON primitives are excluded separately. */
	private static final Set<Class<?>> OPAQUE_JSON_TYPES = CUSTOM_JSON_ADAPTERS.keySet();

	private static boolean isInspectable(Class<?> raw) {
		return !raw.isPrimitive() && !raw.isArray() && !raw.isEnum() && !raw.isInterface() && !OPAQUE_JSON_TYPES.contains(raw) && raw != String.class && raw != Boolean.class
				&& raw != Character.class && !Number.class.isAssignableFrom(raw);
	}

	/** Per-class JSON name to field maps, cached because a single document scan revisits the same class at every nested JSON object. */
	private static final ConcurrentHashMap<Class<?>, Map<String, Field>> JSON_FIELDS = new ConcurrentHashMap<>();

	private static Map<String, Field> jsonFieldNames(Class<?> raw) {
		return JSON_FIELDS.computeIfAbsent(raw, type -> {
			Map<String, Field> names = new HashMap<>();
			for (Field field : type.getDeclaredFields()) {
				if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers()) || field.isSynthetic()) continue;
				SerializedName serializedName = field.getAnnotation(SerializedName.class);
				if (serializedName == null) names.put(field.getName(), field);
				else {
					names.put(serializedName.value(), field);
					for (String alternate : serializedName.alternate()) names.put(alternate, field);
				}
			}
			return names;
		});
	}

	public static void writeAtomic(Path path, Object value) throws IOException {
		OsPaths.requirePublishableConfig(path);
		DurableFiles.writeAtomic(path, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
	}

	/** Publishes rebuildable cache records: atomic replace without any fsync, because a lost or torn write only costs a recompute. */
	public static void writeCached(Path path, Object value) throws IOException {
		OsPaths.requirePublishableConfig(path);
		DurableFiles.writeVolatile(path, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
	}

	private static class InetSocketAddressTypeAdapter implements JsonSerializer<InetSocketAddress> {
		@Override
		public JsonElement serialize(InetSocketAddress source, Type type, JsonSerializationContext context) {
			return new JsonPrimitive(AddressHelpers.formatAddress(source));
		}
	}

	private static class ConnectionInfoTypeAdapter implements JsonSerializer<ConnectionJsons.ConnectionInfo>, JsonDeserializer<ConnectionJsons.ConnectionInfo> {
		@Override
		public JsonElement serialize(ConnectionJsons.ConnectionInfo source, Type type, JsonSerializationContext context) {
			JsonObject object = new JsonObject();
			if (source.origin != null) object.addProperty("origin", AddressHelpers.formatAddress(source.origin));
			if (source.endpoint != null) object.addProperty("endpoint", AddressHelpers.formatAddress(source.endpoint));
			object.add("connectionMode", context.serialize(source.connectionMode));
			if (!source.approvedOrigins().isEmpty()) {
				JsonArray approved = new JsonArray();
				source.approvedOrigins().forEach(approved::add);
				object.add("approvedOrigins", approved);
			}
			return object;
		}

		@Override
		public ConnectionJsons.ConnectionInfo deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
			if (!json.isJsonObject()) throw new JsonParseException("ConnectionInfo must be an object");
			JsonObject object = json.getAsJsonObject();
			try {
				InetSocketAddress origin = parseAddress(object, "origin", "serverAddress", true);
				InetSocketAddress endpoint = parseAddress(object, "endpoint", "hostAddress", false);
				JsonElement modeElement = object.get("connectionMode");
				ModpackConnectionMode connectionMode = modeElement == null || modeElement.isJsonNull()
						? ModpackConnectionMode.HOLEPUNCH
						: context.deserialize(modeElement, ModpackConnectionMode.class);
				ConnectionJsons.ConnectionInfo connection = new ConnectionJsons.ConnectionInfo(origin, endpoint, connectionMode, null, null);
				JsonElement approved = object.get("approvedOrigins");
				if (approved != null && approved.isJsonArray()) for (JsonElement element : approved.getAsJsonArray()) if (element.isJsonPrimitive()) connection.approveOrigin(element.getAsString());
				return connection;
			} catch (IllegalArgumentException e) {
				throw new JsonParseException("Invalid ConnectionInfo", e);
			}
		}

		private InetSocketAddress parseAddress(JsonObject object, String name, String alternate, boolean origin) {
			JsonElement value = object.has(name) ? object.get(name) : object.get(alternate);
			if (value == null || value.isJsonNull()) return null;
			return origin ? AddressHelpers.parseOrigin(value.getAsString()) : AddressHelpers.parseEndpoint(value.getAsString());
		}
	}

	private static class CertificateTrustEntryTypeAdapter implements JsonDeserializer<ConnectionJsons.CertificateTrustEntry> {
		@Override
		public ConnectionJsons.CertificateTrustEntry deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
			if (json.isJsonPrimitive()) return new ConnectionJsons.CertificateTrustEntry(json.getAsString(), "TOFU");
			var object = json.getAsJsonObject();
			String reason = object.has("reason") ? object.get("reason").getAsString() : "TOFU";
			return new ConnectionJsons.CertificateTrustEntry(object.get("fingerprint").getAsString(), reason);
		}
	}

	/** What the stream reader ({@code JsonReader#nextInt}) yields for a literal read as an {@code int}: narrowing to int must lose nothing, or the read fails. */
	private static int streamInt(String literal) {
		double value = Double.parseDouble(literal);
		int narrowed = (int) value;
		if (narrowed != value) throw new NumberFormatException("Expected an int but was " + literal);
		return narrowed;
	}

	/** What the stream reader ({@code JsonReader#nextLong}) yields for a literal read as a {@code long}: whole literals keep full precision, anything else must narrow to long without loss. */
	private static long streamLong(String literal) {
		try {
			return Long.parseLong(literal);
		} catch (NumberFormatException notAWholeLiteral) {
			double value = Double.parseDouble(literal);
			long narrowed = (long) value;
			if (narrowed != value) throw new NumberFormatException("Expected a long but was " + literal);
			return narrowed;
		}
	}

	/**
	 * Enforces the stream reader's number grammar on integral fields deserialized from a parsed tree, where Gson 2.8.9's
	 * own adapters would otherwise read the tree's numbers leniently and accept what the string path has always refused.
	 */
	private static final class StrictIntegralDeserializer implements JsonDeserializer<Number> {
		static final StrictIntegralDeserializer BYTE = new StrictIntegralDeserializer("an int", literal -> (byte) streamInt(literal));
		static final StrictIntegralDeserializer SHORT = new StrictIntegralDeserializer("an int", literal -> (short) streamInt(literal));
		static final StrictIntegralDeserializer INT = new StrictIntegralDeserializer("an int", ConfigTools::streamInt);
		static final StrictIntegralDeserializer LONG = new StrictIntegralDeserializer("a long", ConfigTools::streamLong);

		private final String expected;
		private final Function<String, Number> reader;

		private StrictIntegralDeserializer(String expected, Function<String, Number> reader) {
			this.expected = expected;
			this.reader = reader;
		}

		@Override
		public Number deserialize(JsonElement json, Type type, JsonDeserializationContext context) {
			if (json.isJsonNull()) return null;
			if (!json.isJsonPrimitive() || json.getAsJsonPrimitive().isBoolean()) throw new IllegalStateException("Expected " + expected + " but was " + token(json));
			try {
				return reader.apply(json.getAsString());
			} catch (NumberFormatException e) {
				throw new JsonSyntaxException(e);
			}
		}

		private static String token(JsonElement json) {
			if (json.isJsonObject()) return "BEGIN_OBJECT";
			if (json.isJsonArray()) return "BEGIN_ARRAY";
			return "BOOLEAN";
		}
	}

	/** Refuses unknown enum names with a clear error instead of silently yielding null elements inside persisted state. */
	public static final class StrictEnumTypeAdapter implements JsonDeserializer<Enum<?>> {
		@Override
		@SuppressWarnings({"unchecked", "rawtypes"})
		public Enum<?> deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
			if (!json.isJsonPrimitive()) throw new JsonParseException("Enum " + ((Class<?>) type).getSimpleName() + " value must be a string name");
			String name = json.getAsString();
			try {
				return Enum.valueOf((Class<? extends Enum>) type, name);
			} catch (IllegalArgumentException e) {
				throw new JsonParseException("Unknown " + ((Class<?>) type).getSimpleName() + " value '" + name + "'", e);
			}
		}
	}

	public static class ConfigException extends RuntimeException {
		public ConfigException(String message) {
			super(message);
		}

		public ConfigException(String message, Throwable cause) {
			super(message, cause);
		}
	}

	/** A configuration file's content is unparseable: wrong JSON shape, unknown value, or unusable result; distinct from IO trouble so callers can move broken files aside without hiding read failures. */
	public static class ConfigParseException extends ConfigException {
		public ConfigParseException(String message) {
			super(message);
		}

		public ConfigParseException(String message, Throwable cause) {
			super(message, cause);
		}
	}
}
