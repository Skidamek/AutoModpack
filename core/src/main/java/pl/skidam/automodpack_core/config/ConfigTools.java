package pl.skidam.automodpack_core.config;

import java.io.IOException;
import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.function.Supplier;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonPrimitive;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;

import pl.skidam.automodpack_core.utils.AddressHelpers;

public final class ConfigTools {
	public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting()
			.registerTypeAdapter(InetSocketAddress.class, new InetSocketAddressTypeAdapter())
			.registerTypeAdapter(Jsons.ConnectionInfo.class, new ConnectionInfoTypeAdapter())
			.registerTypeAdapter(Jsons.CertificateTrustEntry.class, new CertificateTrustEntryTypeAdapter()).create();

	private ConfigTools() {}

	public static <T> Optional<T> read(Path path, Class<T> type) {
		if (!Files.isRegularFile(path)) return Optional.empty();
		try {
			return Optional.ofNullable(parse(Files.readString(path, StandardCharsets.UTF_8), type));
		} catch (IOException e) {
			throw new ConfigException("Failed to read configuration " + path.toAbsolutePath().normalize(), e);
		}
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
		if (json == null) throw new ConfigException("Configuration JSON is null");
		try {
			T value = GSON.fromJson(json, type);
			if (value == null) throw new ConfigException("Configuration JSON produced null for " + type.getSimpleName());
			return value;
		} catch (JsonParseException e) {
			throw new ConfigException("Invalid JSON for " + type.getSimpleName(), e);
		}
	}

	public static void writeAtomic(Path path, Object value) throws IOException {
		AtomicFileWriter.write(path, GSON.toJson(value).getBytes(StandardCharsets.UTF_8));
	}

	private static class InetSocketAddressTypeAdapter implements JsonSerializer<InetSocketAddress> {
		@Override
		public JsonElement serialize(InetSocketAddress source, Type type, JsonSerializationContext context) {
			return new JsonPrimitive(AddressHelpers.formatAddress(source));
		}
	}

	private static class ConnectionInfoTypeAdapter implements JsonSerializer<Jsons.ConnectionInfo>, JsonDeserializer<Jsons.ConnectionInfo> {
		@Override
		public JsonElement serialize(Jsons.ConnectionInfo source, Type type, JsonSerializationContext context) {
			JsonObject object = new JsonObject();
			if (source.origin != null) object.addProperty("origin", AddressHelpers.formatAddress(source.origin));
			if (source.endpoint != null) object.addProperty("endpoint", AddressHelpers.formatAddress(source.endpoint));
			object.addProperty("requiresMagic", source.requiresMagic);
			return object;
		}

		@Override
		public Jsons.ConnectionInfo deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
			if (!json.isJsonObject()) throw new JsonParseException("ConnectionInfo must be an object");
			JsonObject object = json.getAsJsonObject();
			try {
				InetSocketAddress origin = parseAddress(object, "origin", "serverAddress", true);
				InetSocketAddress endpoint = parseAddress(object, "endpoint", "hostAddress", false);
				boolean requiresMagic = object.has("requiresMagic") && !object.get("requiresMagic").isJsonNull()
						&& object.get("requiresMagic").getAsBoolean();
				return new Jsons.ConnectionInfo(origin, endpoint, requiresMagic, null, null);
			} catch (IllegalArgumentException e) {
				throw new JsonParseException("Invalid ConnectionInfo address", e);
			}
		}

		private InetSocketAddress parseAddress(JsonObject object, String name, String alternate, boolean origin) {
			JsonElement value = object.has(name) ? object.get(name) : object.get(alternate);
			if (value == null || value.isJsonNull()) return null;
			return origin ? AddressHelpers.parseOrigin(value.getAsString()) : AddressHelpers.parseEndpoint(value.getAsString());
		}
	}

	private static class CertificateTrustEntryTypeAdapter implements JsonDeserializer<Jsons.CertificateTrustEntry> {
		@Override
		public Jsons.CertificateTrustEntry deserialize(JsonElement json, Type type, JsonDeserializationContext context) throws JsonParseException {
			if (json.isJsonPrimitive()) return new Jsons.CertificateTrustEntry(json.getAsString(), "TOFU");
			var object = json.getAsJsonObject();
			String reason = object.has("reason") ? object.get("reason").getAsString() : "TOFU";
			return new Jsons.CertificateTrustEntry(object.get("fingerprint").getAsString(), reason);
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
}
