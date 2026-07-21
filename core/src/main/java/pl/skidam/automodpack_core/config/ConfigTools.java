
package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.Constants.*;

import java.lang.reflect.Type;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import com.google.gson.*;

import pl.skidam.automodpack_core.utils.AddressHelpers;

public class ConfigTools {

	public static Gson GSON = new GsonBuilder().disableHtmlEscaping().setPrettyPrinting()
			.registerTypeAdapter(InetSocketAddress.class, new InetSocketAddressTypeAdapter())
			.registerTypeAdapter(Jsons.ConnectionInfo.class, new ConnectionInfoTypeAdapter())
			.registerTypeAdapter(Jsons.CertificateTrustEntry.class, new CertificateTrustEntryTypeAdapter()).create();

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
		public Jsons.CertificateTrustEntry deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
			if (json.isJsonPrimitive()) return new Jsons.CertificateTrustEntry(json.getAsString(), "TOFU");
			JsonObject object = json.getAsJsonObject();
			String reason = object.has("reason") ? object.get("reason").getAsString() : "TOFU";
			return new Jsons.CertificateTrustEntry(object.get("fingerprint").getAsString(), reason);
		}
	}

	public static <T> T getConfigObject(Class<T> configClass) {
		T object = null;
		try {
			object = configClass.getConstructor().newInstance();
		} catch (Exception e) {
			e.printStackTrace();
		}
		return object;
	}

	// Config stuff
	public static <T> T softLoad(Path configFile, Class<T> configClass) {
		try {
			if (Files.isRegularFile(configFile)) {
				String json = Files.readString(configFile);
				return GSON.fromJson(json, configClass);
			}
		} catch (Exception ignored) {
		}
		return null;
	}

	public static <T> T load(Path configFile, Class<T> configClass) {
		try {
			if (!Files.isDirectory(configFile.getParent())) Files.createDirectories(configFile.getParent());

			if (Files.isRegularFile(configFile)) {
				String json = Files.readString(configFile);
				T obj = GSON.fromJson(json, configClass);
				if (obj == null) {
					LOGGER.error("Parsed object is null. Possible JSON syntax error in file: " + configFile);
					return null;
				}

				save(configFile, obj);
				return obj;
			}
		} catch (JsonSyntaxException e) {
			LOGGER.error("JSON syntax error while loading config! {} {}", configClass, e.getMessage());
			LOGGER.error("This error most often happens when you e.g. forget to put a comma between fields in JSON file. Check the file: "
					+ configFile.toAbsolutePath().normalize());
			return null;
		} catch (Exception e) {
			LOGGER.error("Couldn't load config! " + configClass);
			e.printStackTrace();
		}

		try { // create new config
			T obj = getConfigObject(configClass);
			save(configFile, obj);
			return obj;
		} catch (Exception e) {
			LOGGER.error("Invalid config class! " + configClass);
			e.printStackTrace();
			return null;
		}
	}

	public static <T> T load(String json, Class<T> configClass) {
		try {
			if (json != null) return GSON.fromJson(json, configClass);
		} catch (Exception e) {
			LOGGER.error("Couldn't load config! " + configClass);
			e.printStackTrace();
		}

		return null;
	}

	public static boolean save(Path configFile, Object configObject) {
		if (clientConfigOverride != null) return false;

		try {
			if (!Files.isDirectory(configFile.getParent())) Files.createDirectories(configFile.getParent());

			Files.writeString(configFile, GSON.toJson(configObject), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			return true;
		} catch (Exception e) {
			LOGGER.error("Couldn't save config! " + configObject.getClass());
			e.printStackTrace();
			return false;
		}
	}

	// Modpack content stuff
	public static Jsons.ModpackContentFields loadModpackContent(Path modpackContentFile) {
		try {
			if (Files.isRegularFile(modpackContentFile)) {
				String json = Files.readString(modpackContentFile);
				return GSON.fromJson(json, Jsons.ModpackContentFields.class);
			}
		} catch (Exception e) {
			LOGGER.error("Couldn't load modpack content! {}", modpackContentFile.toAbsolutePath().normalize(), e);
		}
		return null;
	}

	public static boolean saveModpackContent(Path modpackContentFile, Jsons.ModpackContentFields configObject) {
		try {
			if (!Files.isDirectory(modpackContentFile.getParent())) Files.createDirectories(modpackContentFile.getParent());

			Files.writeString(modpackContentFile, GSON.toJson(configObject), StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING);
			return true;
		} catch (Exception e) {
			LOGGER.error("Couldn't save modpack content! " + configObject.getClass());
			e.printStackTrace();
			return false;
		}
	}
}
