package pl.skidam.automodpack_core.protocol;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.Base64;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.utils.FetchCache;

/**
 * Mozilla OneCRL. The bundled snapshot is the MITM-resistant floor; the last Mozilla feed {@link FetchCache} holds
 * overlays it, refetched in the background once it is older than {@link FetchCache#MAX_AGE}. A hit removes the WebPKI
 * vouch; the saved-pin and DNSSEC ladder still decides pack TLS. Refresh the source with core/src/main/onecrl/update.py;
 * the build compresses it to /onecrl.json.zst.
 */
public final class OneCrl {

	private static final int FETCH_PAGE_LIMIT = 10_000;
	static final String SOURCE_URL = "https://firefox.settings.services.mozilla.com/v1/buckets/security-state/collections/onecrl/records?_limit=" + FETCH_PAGE_LIMIT;
	private static final String SNAPSHOT_RESOURCE = "/onecrl.json.zst";
	private static final Blocklist SNAPSHOT = loadBundledSnapshot();
	private static volatile Blocklist live = SNAPSHOT;
	private static final AtomicBoolean KEYING_FAILED = new AtomicBoolean();

	private OneCrl() {}

	/** Overlays the cached Mozilla feed on the snapshot, refetching it in the background when it is stale. The snapshot already blocks without it. */
	public static void startRefresh() {
		FetchCache.shared().get(List.of(SOURCE_URL), OneCrl::parseMozilla, Duration.ofMinutes(2)).thenAccept(feed -> {
			if (feed != null) live = SNAPSHOT.union(feed);
		});
	}

	/** True when the certificate is on the live list. A certificate that cannot be keyed fails closed, once. */
	static boolean blocks(X509Certificate certificate) {
		try {
			return live.blocks(certificate);
		} catch (RuntimeException unkeyable) {
			if (KEYING_FAILED.compareAndSet(false, true)) {
				LOGGER.warn("Could not key a certificate for OneCRL and refused it; logged once per process", unkeyable);
			}
			return true;
		}
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
			if (record.has("issuerName") && record.has("serialNumber")) issuerSerial.add(issuerSerialKey(b64(record.get("issuerName").getAsString()), b64(record.get("serialNumber").getAsString())));
			if (record.has("pubKeyHash")) spkiHex.add(HexFormat.of().formatHex(b64(record.get("pubKeyHash").getAsString())));
		}
		if (issuerSerial.isEmpty() && spkiHex.isEmpty()) return null;
		return new Blocklist(Set.copyOf(issuerSerial), Set.copyOf(spkiHex));
	}

	/** A fresh copy of the bundled floor; production parses it once at class init, tests parse it independently. */
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
			issuerSerial.add(issuerSerialKey(b64(entry.get(0).getAsString()), b64(entry.get(1).getAsString())));
		}
		Set<String> spkiHex = new HashSet<>();
		for (JsonElement hash : root.getAsJsonArray("spki")) spkiHex.add(HexFormat.of().formatHex(b64(hash.getAsString())));
		return new Blocklist(Set.copyOf(issuerSerial), Set.copyOf(spkiHex));
	}

	static String issuerSerialKey(X509Certificate certificate) {
		return issuerSerialKey(certificate.getIssuerX500Principal().getEncoded(), certificate.getSerialNumber().toByteArray());
	}

	static String issuerSerialKey(byte[] issuerDer, byte[] serial) {
		return HexFormat.of().formatHex(issuerDer) + "|" + HexFormat.of().formatHex(serial);
	}

	private static String spkiHex(X509Certificate certificate) {
		byte[] encoded = certificate.getPublicKey().getEncoded();
		if (encoded == null) return "";
		try {
			return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(encoded));
		} catch (NoSuchAlgorithmException e) {
			throw new IllegalStateException("SHA-256 is unavailable", e);
		}
	}

	private static byte[] b64(String value) {
		int pad = (4 - value.length() % 4) % 4;
		return Base64.getDecoder().decode(pad == 0 ? value : value + "=".repeat(pad));
	}

	record Blocklist(Set<String> issuerSerial, Set<String> spkiHex) {
		boolean blocks(X509Certificate certificate) {
			return spkiHex.contains(OneCrl.spkiHex(certificate)) || issuerSerial.contains(issuerSerialKey(certificate));
		}

		Blocklist union(Blocklist other) {
			Set<String> issuers = new HashSet<>(issuerSerial);
			issuers.addAll(other.issuerSerial);
			Set<String> spki = new HashSet<>(spkiHex);
			spki.addAll(other.spkiHex);
			return new Blocklist(Set.copyOf(issuers), Set.copyOf(spki));
		}
	}
}
