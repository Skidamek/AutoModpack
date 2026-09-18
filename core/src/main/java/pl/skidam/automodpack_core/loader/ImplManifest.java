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
	private static final Pattern COVER = Pattern.compile("~?\\d+(\\.\\d+){1,3}");

	private final String digest;
	private final List<Entry> entries;

	/**
	 * The build's cover declaration: each cover is either an exact Minecraft version ({@code 1.20.1},
	 * matching only that version) or a tilde patch line ({@code ~26.3}, matching the base and every
	 * later patch below the next minor - the author's claim that the whole line rides this one impl).
	 */
	public record Entry(String id, List<String> covers, long offset, long length, String sha1) {}

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
					coveredBy(impl),
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
	 * The entry whose id names this launch's loader and whose covers name this launch's Minecraft
	 * version - a patch release like {@code 26.1.2} resolves to the {@code 26.1-fabric} target that
	 * covers {@code ~26.1}. A version no cover names is a broken launch and crashes with the covered
	 * versions instead of silently mounting a wrong impl; a version two targets cover is a broken
	 * manifest and crashes the same way.
	 */
	public Entry entryFor(String loader, String mcVersion) {
		List<String> version = components(mcVersion);
		List<Entry> matches = entries.stream()
				.filter(entry -> entry.id().endsWith("-" + loader))
				.filter(entry -> entry.covers().stream().anyMatch(cover -> coverMatches(cover, version)))
				.toList();
		if (matches.isEmpty()) throw new IllegalStateException("This AutoModpack jar carries no impl for Minecraft " + mcVersion + " on " + loader + "; covered: " + coverage());
		if (matches.size() > 1)
			throw new IllegalStateException(
					"Minecraft " + mcVersion + " on " + loader + " is covered by " + matches.stream().map(Entry::id).sorted().collect(Collectors.joining(" and ")) + " - ambiguous impl selection; covered: " + coverage());
		return matches.get(0);
	}

	/** The uncompressed solid size the manifest describes. */
	public long totalSize() {
		return entries.stream().mapToLong(Entry::length).sum();
	}

	/** The dot-separated numeric prefix of {@code version} - the {@code -suffix} of a pre-release is dropped. */
	private static List<String> components(String version) {
		String numeric = version;
		int suffix = numeric.indexOf('-');
		if (suffix >= 0) numeric = numeric.substring(0, suffix);
		return List.of(numeric.split("\\."));
	}

	/** Whether one cover names the version components: an exact as equality, a tilde as its patch line - from the base up to its bumped minor. */
	private static boolean coverMatches(String cover, List<String> version) {
		if (!cover.startsWith("~")) return compare(version, components(cover)) == 0;
		String base = cover.substring(1);
		return compare(version, components(base)) >= 0 && compare(version, upperBound(base)) < 0;
	}

	/** The tilde cover's exclusive end - the second-to-last component bumped and the last dropped ({@code 26.3.1} ends at {@code 26.4}), the base padded to three components first. */
	private static List<String> upperBound(String base) {
		List<String> parts = new ArrayList<>(components(base));
		while (parts.size() < 3) parts.add("0");
		parts.remove(parts.size() - 1);
		int bumped = parts.size() - 1;
		parts.set(bumped, Long.toString(Long.parseLong(parts.get(bumped)) + 1));
		return parts;
	}

	/** Numeric dotted compare, missing components zero. */
	private static int compare(List<String> a, List<String> b) {
		for (int i = 0; i < Math.max(a.size(), b.size()); i++) {
			long left = i < a.size() ? Long.parseLong(a.get(i)) : 0L;
			long right = i < b.size() ? Long.parseLong(b.get(i)) : 0L;
			if (left != right) return Long.compare(left, right);
		}
		return 0;
	}

	private static List<String> coveredBy(JsonObject impl) {
		List<String> covers = strings(impl, "covers");
		for (String cover : covers) {
			if (!COVER.matcher(cover).matches()) throw new IllegalStateException("Impl manifest cover " + cover + " is neither an exact version nor a ~ dotted-prefix");
		}
		return covers;
	}

	private static String string(JsonObject object, String field) {
		JsonElement element = object.get(field);
		if (element == null || !element.isJsonPrimitive() || !element.getAsJsonPrimitive().isString())
			throw new IllegalStateException("Impl manifest carries no usable " + field + " in " + object);
		return element.getAsString();
	}

	private static String sha1(JsonObject impl) {
		String sha1 = string(impl, "sha1");
		if (!SHA1_HEX.matcher(sha1).matches()) throw new IllegalStateException("Impl manifest slice digest " + sha1 + " is not a SHA-1 hex digest");
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
		return entries.stream().map(entry -> entry.id() + " [" + String.join(", ", entry.covers()) + "]").sorted().collect(Collectors.joining(", "));
	}
}
