package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.utils.FetchCache;

/**
 * Mozilla OneCRL's data: the bundled snapshot is the MITM-resistant floor, and the last Mozilla feed {@link FetchCache}
 * holds overlays it, refetched once it is older than {@link FetchCache#MAX_AGE}. The blocklist itself lives in
 * {@link Trust}, which is the only place a certificate is keyed against it. Refresh the source with
 * core/src/main/onecrl/update.py; the build compresses it to /onecrl.json.zst.
 */
public final class OneCrl {

	private static final int FETCH_PAGE_LIMIT = 10_000;
	static final String SOURCE_URL = "https://firefox.settings.services.mozilla.com/v1/buckets/security-state/collections/onecrl/records?_limit=" + FETCH_PAGE_LIMIT;
	private static final String SNAPSHOT_RESOURCE = "/onecrl.json.zst";

	private OneCrl() {}

	/** Overlays the cached Mozilla feed on the snapshot, refetching it when it is stale. The snapshot already blocks without it. */
	public static void startRefresh() {
		FetchCache.shared().get(List.of(SOURCE_URL), OneCrl::parseMozilla, Duration.ofMinutes(2)).thenAccept(feed -> {
			if (feed != null) Trust.feed(feed.issuerSerial(), feed.spkiHex());
		});
	}

	/** The enabled records of a Mozilla feed; null for a feed that is empty or fills the page limit, which may be truncated. */
	static Blocklist parseMozilla(byte[] body) {
		JsonArray data = JsonParser.parseString(new String(body, StandardCharsets.UTF_8)).getAsJsonObject().getAsJsonArray("data");
		if (data == null || data.size() >= FETCH_PAGE_LIMIT) return null;
		Set<String> issuerSerial = new HashSet<>();
		Set<String> spkiHex = new HashSet<>();
		for (JsonElement element : data) {
			if (!element.isJsonObject()) continue;
			JsonObject record = element.getAsJsonObject();
			if (record.has("enabled") && !record.get("enabled").getAsBoolean()) continue;
			if (record.has("issuerName") && record.has("serialNumber")) issuerSerial.add(Trust.key(b64(record.get("issuerName").getAsString()), b64(record.get("serialNumber").getAsString())));
			if (record.has("pubKeyHash")) spkiHex.add(hex(b64(record.get("pubKeyHash").getAsString())));
		}
		if (issuerSerial.isEmpty() && spkiHex.isEmpty()) return null;
		return new Blocklist(Set.copyOf(issuerSerial), Set.copyOf(spkiHex));
	}

	/** A fresh copy of the bundled floor; {@link Trust#start()} parses it once, tests parse it independently. */
	static Blocklist loadBundledSnapshot() {
		try (InputStream raw = OneCrl.class.getResourceAsStream(SNAPSHOT_RESOURCE)) {
			if (raw == null) throw new IllegalStateException("Missing OneCRL snapshot " + SNAPSHOT_RESOURCE);
			try (InputStream in = WireCodec.ZSTD.unwrap(raw); InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
				return parseCompact(JsonParser.parseReader(reader).getAsJsonObject());
			}
		} catch (IOException e) {
			throw new IllegalStateException("Failed to read OneCRL snapshot", e);
		}
	}

	static Blocklist parseCompact(JsonObject root) {
		Set<String> issuerSerial = new HashSet<>();
		for (JsonElement pair : root.getAsJsonArray("is")) {
			JsonArray entry = pair.getAsJsonArray();
			issuerSerial.add(Trust.key(b64(entry.get(0).getAsString()), b64(entry.get(1).getAsString())));
		}
		Set<String> spkiHex = new HashSet<>();
		for (JsonElement hash : root.getAsJsonArray("spki")) spkiHex.add(hex(b64(hash.getAsString())));
		return new Blocklist(Set.copyOf(issuerSerial), Set.copyOf(spkiHex));
	}

	private static String hex(byte[] value) {
		return HexFormat.of().formatHex(value);
	}

	private static byte[] b64(String value) {
		int pad = (4 - value.length() % 4) % 4;
		return Base64.getDecoder().decode(pad == 0 ? value : value + "=".repeat(pad));
	}

	record Blocklist(Set<String> issuerSerial, Set<String> spkiHex) {}
}
