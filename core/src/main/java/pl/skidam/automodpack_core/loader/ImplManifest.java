package pl.skidam.automodpack_core.loader;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * The index of the one jar's solid impl blob ({@code impl/manifest.json}): the digest - the
 * SHA-1 of the UNCOMPRESSED solid, so a compressor bump cannot invalidate every install's impl
 * cache - and per impl its id, the Minecraft versions that target covers, its offset and STORE
 * length inside the solid, and the SHA-1 of that slice. Bare data, no version field: the manifest
 * ships inside the same jar as this parser, so reader and writer can never skew, and any malformed
 * content is a broken outer jar that crashes instead of answering a partial question.
 */
public final class ImplManifest {
	private static final Pattern SHA1_HEX = Pattern.compile("[0-9a-f]{40}");

	private final String digest;
	private final List<Entry> entries;

	/** The build's target spelling plus the exact Minecraft releases its {@code publish_versions} cover - the only source of truth for version resolution. */
	public record Entry(String id, List<String> versions, long offset, long length, String sha1) {}

	private ImplManifest(String digest, List<Entry> entries) {
		this.digest = digest;
		this.entries = entries;
	}

	/** Parses the manifest bytes; throws {@link IllegalStateException} on anything that is not exactly one well-formed manifest. */
	public static ImplManifest parse(byte[] bytes) {
		JsonObject root;
		try {
			root = JsonParser.parseString(new String(bytes, StandardCharsets.UTF_8)).getAsJsonObject();
		} catch (Exception e) {
			throw new IllegalStateException("Impl manifest is not a JSON object: " + message(e), e);
		}
		String digest = string(root, "digest");
		if (!SHA1_HEX.matcher(digest).matches()) throw new IllegalStateException("Impl manifest digest " + digest + " is not a SHA-1 hash");
		JsonArray impls = array(root, "impls");
		List<Entry> entries = new ArrayList<>(impls.size());
		for (JsonElement element : impls) {
			JsonObject impl = object(element, "impl");
			Entry entry = new Entry(
					string(impl, "id"),
					List.copyOf(strings(impl, "versions")),
					positive(impl, "offset"),
					positive(impl, "length"),
					sha1(impl));
			entries.add(entry);
		}
		return new ImplManifest(digest, List.copyOf(entries));
	}

	/** The SHA-1 of the uncompressed solid as lowercase hex - the impl-cache digest key. */
	public String digest() {
		return digest;
	}

	public List<Entry> entries() {
		return entries;
	}

	/** The entry for {@code id}; an id the manifest does not carry is a broken launch and crashes with the ids that exist. */
	public Entry entry(String id) {
		return entries.stream().filter(entry -> entry.id().equals(id)).findFirst().orElseThrow(() -> new IllegalStateException("This AutoModpack jar carries no impl for " + id + "; available impls: " + ids()));
	}

	/**
	 * The entry whose target spelling ({@code <mcVersion>-<loader>}) and covered-versions list name this launch's
	 * exact Minecraft version - a patch release like {@code 26.1.2} resolves to the {@code 26.1-fabric} target that
	 * declares it. Only exact, build-declared coverage matches: an uncovered version is a broken launch and crashes
	 * with the covered versions instead of silently mounting a wrong impl.
	 */
	public Entry entryFor(String loader, String mcVersion) {
		return entries.stream().filter(entry -> entry.id().endsWith("-" + loader) && entry.versions().contains(mcVersion)).findFirst()
				.orElseThrow(() -> new IllegalStateException("This AutoModpack jar carries no impl for Minecraft " + mcVersion + " on " + loader + "; supported: " + coverage()));
	}

	/** The uncompressed solid size the manifest describes. */
	public long totalSize() {
		return entries.stream().mapToLong(Entry::length).sum();
	}

	private static String string(JsonObject object, String field) {
		JsonElement element = object.get(field);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
			throw new IllegalStateException("Impl manifest carries no usable " + field + " in " + object);
		return element.getAsString();
	}

	private static String sha1(JsonObject impl) {
		String sha1 = string(impl, "sha1");
		if (!SHA1_HEX.matcher(sha1).matches()) throw new IllegalStateException("Impl manifest slice digest " + sha1 + " is not a SHA-1 hash");
		return sha1;
	}

	private static List<String> strings(JsonObject object, String field) {
		JsonArray array = array(object, field);
		List<String> values = new ArrayList<>(array.size());
		for (JsonElement element : array) {
			if (!element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
				throw new IllegalStateException("Impl manifest " + field + " carries a non-string entry in " + object);
			values.add(element.getAsString());
		}
		return values;
	}

	private static long positive(JsonObject object, String field) {
		JsonElement element = object.get(field);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isNumber())
			throw new IllegalStateException("Impl manifest carries no usable " + field + " in " + object);
		long value = element.getAsLong();
		if (value < 0) throw new IllegalStateException("Impl manifest carries a negative " + field + " in " + object);
		return value;
	}

	private static JsonArray array(JsonObject object, String field) {
		JsonElement element = object.get(field);
		if (element == null || !element.isJsonArray()) throw new IllegalStateException("Impl manifest carries no " + field + " array in " + object);
		return element.getAsJsonArray();
	}

	private static JsonObject object(JsonElement element, String what) {
		if (!element.isJsonObject()) throw new IllegalStateException("Impl manifest " + what + " is not an object: " + element);
		return element.getAsJsonObject();
	}

	private static String message(Exception e) {
		return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
	}

	private String ids() {
		return entries.stream().map(Entry::id).sorted().collect(Collectors.joining(", "));
	}

	private String coverage() {
		return entries.stream().map(entry -> entry.id() + " [" + String.join(", ", entry.versions()) + "]").sorted().collect(Collectors.joining(", "));
	}
}
