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
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
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
import com.google.gson.annotations.SerializedName;

import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.OsPaths;

public final class ConfigTools {
	/** The custom JSON shapes registered on {@link #GSON}; the same set defines which types key-reflection may not inspect. */
	private static final Map<Class<?>, Object> CUSTOM_JSON_ADAPTERS = Map.of(InetSocketAddress.class, new InetSocketAddressTypeAdapter(), ConnectionJsons.ConnectionInfo.class,
			new ConnectionInfoTypeAdapter(), ConnectionJsons.CertificateTrustEntry.class, new CertificateTrustEntryTypeAdapter());

	public static final Gson GSON = buildGson();

	private ConfigTools() {}

	private static Gson buildGson() {
		GsonBuilder builder = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting();
		CUSTOM_JSON_ADAPTERS.forEach(builder::registerTypeAdapter);
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
		T value = parse(json, type);
		List<String> unknown = unknownKeys(json, type);
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

	public static <T> T parse(String json, Class<T> type) {
		if (json == null) throw new ConfigParseException("Configuration JSON is null");
		try {
			T value = GSON.fromJson(json, type);
			if (value == null) throw new ConfigParseException("Configuration JSON produced null for " + type.getSimpleName());
			return value;
		} catch (JsonParseException e) {
			throw new ConfigParseException("Invalid JSON for " + type.getSimpleName(), e);
		}
	}

	/** JSON paths present in the document that match no field of the target class, so Gson silently drops them; empty for invalid JSON, which {@link #parse} reports instead. */
	public static List<String> unknownKeys(String json, Class<?> type) {
		List<String> unknown = new ArrayList<>();
		try {
			collectUnknownKeys(JsonParser.parseString(json), type, "", unknown);
		} catch (JsonParseException e) {
			return List.of();
		}
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

	private static Map<String, Field> jsonFieldNames(Class<?> raw) {
		Map<String, Field> names = new HashMap<>();
		for (Field field : raw.getDeclaredFields()) {
			if (Modifier.isStatic(field.getModifiers()) || Modifier.isTransient(field.getModifiers()) || field.isSynthetic()) continue;
			SerializedName serializedName = field.getAnnotation(SerializedName.class);
			if (serializedName == null) names.put(field.getName(), field);
			else {
				names.put(serializedName.value(), field);
				for (String alternate : serializedName.alternate()) names.put(alternate, field);
			}
		}
		return names;
	}

	public static void writeAtomic(Path path, Object value) throws IOException {
		OsPaths.requirePublishableConfig(path);
		DurableFiles.writeAtomic(path, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
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
