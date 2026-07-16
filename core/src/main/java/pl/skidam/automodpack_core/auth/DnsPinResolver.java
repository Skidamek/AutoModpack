package pl.skidam.automodpack_core.auth;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.normalizeFingerprint;

import java.net.IDN;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * Resolves an admin-published certificate fingerprint from DNS under the
 * Minecraft hostname selected by the user.
 */
public final class DnsPinResolver {

	public static final String RECORD_PREFIX = "_automodpack.";
	public static final String RECORD_VERSION = "amp1";

	private static final List<String> DOH_RESOLVERS = List.of("https://cloudflare-dns.com/dns-query", "https://doh.mullvad.net/dns-query");
	private static final Duration TIMEOUT = Duration.ofSeconds(5);
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();

	private DnsPinResolver() {}

	public sealed interface LookupResult permits Authoritative, NoPolicy, Misconfigured {}

	public record Authoritative(String fingerprint) implements LookupResult {}

	public record NoPolicy(NoPolicyReason reason) implements LookupResult {}

	public record Misconfigured(String reason) implements LookupResult {}

	public enum NoPolicyReason {
		IP_LITERAL, ABSENT, UNAVAILABLE
	}

	sealed interface ResolverResult permits ResolverPin, ResolverAbsent, ResolverUnavailable, ResolverMisconfigured {}

	record ResolverPin(String fingerprint) implements ResolverResult {}

	record ResolverAbsent() implements ResolverResult {}

	record ResolverUnavailable() implements ResolverResult {}

	record ResolverMisconfigured(String reason) implements ResolverResult {}

	public static CompletableFuture<LookupResult> resolvePinAsync(String minecraftHost) {
		Optional<String> normalizedHost = normalizeDnsHost(minecraftHost);
		if (normalizedHost.isEmpty()) { return CompletableFuture.completedFuture(new NoPolicy(NoPolicyReason.IP_LITERAL)); }

		String host = normalizedHost.get();
		String name = RECORD_PREFIX + host;
		List<CompletableFuture<ResolverResult>> futures = DOH_RESOLVERS.stream().map(resolver -> queryResolverAsync(resolver, name)).toList();

		return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
				.thenApply(ignored -> combineResolverResults(host, futures.stream().map(CompletableFuture::join).toList()));
	}

	private static LookupResult combineResolverResults(String host, List<ResolverResult> results) {
		if (results.stream().allMatch(ResolverAbsent.class::isInstance)) { return new NoPolicy(NoPolicyReason.ABSENT); }

		if (results.stream().allMatch(ResolverMisconfigured.class::isInstance)) {
			String reason = ((ResolverMisconfigured) results.get(0)).reason();
			LOGGER.error("DNSSEC AutoModpack fingerprint for {} is invalid: {}", host, reason);
			return new Misconfigured(reason);
		}

		if (results.stream().allMatch(ResolverPin.class::isInstance)) {
			String expected = ((ResolverPin) results.get(0)).fingerprint();
			boolean agrees = results.stream().map(ResolverPin.class::cast).allMatch(result -> result.fingerprint().equals(expected));
			if (agrees) { return new Authoritative(expected); }
			LOGGER.warn("DNS resolvers disagree on the AutoModpack fingerprint for {}", host);
		}

		return new NoPolicy(NoPolicyReason.UNAVAILABLE);
	}

	private static CompletableFuture<ResolverResult> queryResolverAsync(String resolver, String name) {
		try {
			HttpRequest request = HttpRequest.newBuilder().uri(URI.create(resolver + "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&type=TXT"))
					.header("Accept", "application/dns-json").timeout(TIMEOUT).GET().build();

			return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
				if (response.statusCode() < 200 || response.statusCode() >= 300) { return new ResolverUnavailable(); }
				return parseDnsResponse(response.body());
			}).exceptionally(error -> {
				LOGGER.debug("DNS fingerprint lookup for {} via {} failed", name, resolver, error);
				return new ResolverUnavailable();
			});
		} catch (Exception e) {
			LOGGER.debug("Failed to build DNS fingerprint request for {} via {}", name, resolver, e);
			return CompletableFuture.completedFuture(new ResolverUnavailable());
		}
	}

	static ResolverResult parseDnsResponse(String body) {
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();
			int status = json.has("Status") ? json.get("Status").getAsInt() : -1;
			boolean authenticated = json.has("AD") && json.get("AD").getAsBoolean();

			if (!authenticated) { return new ResolverUnavailable(); }

			if (status == 3) { return new ResolverAbsent(); }
			if (status != 0) { return new ResolverUnavailable(); }

			List<String> txtRecords = new ArrayList<>();
			if (json.has("Answer")) {
				for (JsonElement element : json.getAsJsonArray("Answer")) {
					JsonObject answer = element.getAsJsonObject();
					if (answer.has("type") && answer.get("type").getAsInt() == 16 && answer.has("data")) {
						txtRecords.add(decodeTxtData(answer.get("data").getAsString()));
					}
				}
			}
			return parseTxtRecords(txtRecords);
		} catch (Exception e) {
			LOGGER.debug("Failed to parse DNS fingerprint response", e);
			return new ResolverUnavailable();
		}
	}

	static ResolverResult parseTxtRecords(List<String> txtRecords) {
		String fingerprint = null;
		for (String txt : txtRecords) {
			if (!isAmp1Record(txt)) { continue; }
			if (fingerprint != null) { return new ResolverMisconfigured("multiple amp1 records are not allowed"); }

			try {
				fingerprint = parsePin(txt);
			} catch (IllegalArgumentException e) {
				return new ResolverMisconfigured(e.getMessage());
			}
		}

		return fingerprint == null ? new ResolverAbsent() : new ResolverPin(fingerprint);
	}

	static String parsePin(String txt) {
		if (txt == null) { throw new IllegalArgumentException("empty amp1 record"); }

		String version = null;
		String fingerprint = null;

		for (String rawPart : txt.split(";", -1)) {
			String part = rawPart.trim();
			int separator = part.indexOf('=');
			if (separator <= 0) { throw new IllegalArgumentException("invalid amp1 field: " + part); }

			String key = part.substring(0, separator).trim().toLowerCase(Locale.ROOT);
			String value = part.substring(separator + 1).trim();
			switch (key) {
				case "v" -> {
					if (version != null) throw new IllegalArgumentException("duplicate amp1 version");
					version = value;
				}
				case "fp" -> {
					if (fingerprint != null) throw new IllegalArgumentException("duplicate amp1 fingerprint");
					fingerprint = normalizeFingerprint(value);
				}
				default -> throw new IllegalArgumentException("unknown amp1 field: " + key);
			}
		}

		if (!RECORD_VERSION.equals(version)) throw new IllegalArgumentException("unsupported amp1 version");
		if (fingerprint == null) throw new IllegalArgumentException("amp1 fingerprint is missing");
		return fingerprint;
	}

	public static String formatRecord(String minecraftHost, String fingerprint) {
		String owner = normalizeDnsHost(minecraftHost).orElseThrow(() -> new IllegalArgumentException("Minecraft address must be a DNS hostname"));
		return RECORD_PREFIX + owner + ". IN TXT \"v=" + RECORD_VERSION + ";fp=" + normalizeFingerprint(fingerprint) + "\"";
	}

	static String decodeTxtData(String data) {
		if (data == null) return "";
		String trimmed = data.trim();
		StringBuilder decoded = new StringBuilder();
		boolean quoted = false;
		boolean escaping = false;

		for (int i = 0; i < trimmed.length(); i++) {
			char c = trimmed.charAt(i);
			if (escaping) {
				decoded.append(c);
				escaping = false;
			} else if (c == '\\' && quoted) {
				escaping = true;
			} else if (c == '"') {
				quoted = !quoted;
			} else if (!quoted && Character.isWhitespace(c)) {
				continue;
			} else {
				decoded.append(c);
			}
		}
		if (quoted || escaping) throw new IllegalArgumentException("malformed TXT quoting");
		return decoded.toString().trim();
	}

	static boolean isIpLiteral(String host) {
		if (host == null) return false;
		String value = stripIpv6Brackets(host.trim());
		if (value.contains(":")) return true;
		if (!value.matches("\\d{1,3}(\\.\\d{1,3}){3}")) return false;
		for (String octet : value.split("\\.")) {
			if (Integer.parseInt(octet) > 255) return false;
		}
		return true;
	}

	private static Optional<String> normalizeDnsHost(String host) {
		if (host == null) return Optional.empty();
		String normalized = host.trim();
		if (normalized.isEmpty() || isIpLiteral(normalized)) return Optional.empty();
		if (normalized.endsWith(".")) normalized = normalized.substring(0, normalized.length() - 1);
		try {
			normalized = IDN.toASCII(normalized, IDN.USE_STD3_ASCII_RULES).toLowerCase(Locale.ROOT);
		} catch (IllegalArgumentException e) {
			return Optional.empty();
		}
		return normalized.isBlank() ? Optional.empty() : Optional.of(normalized);
	}

	private static boolean isAmp1Record(String txt) {
		if (txt == null) return false;
		for (String rawPart : txt.split(";", -1)) {
			String part = rawPart.trim();
			int separator = part.indexOf('=');
			if (separator > 0 && part.substring(0, separator).trim().equalsIgnoreCase("v")
					&& part.substring(separator + 1).trim().equalsIgnoreCase(RECORD_VERSION)) {
				return true;
			}
		}
		return false;
	}

	private static String stripIpv6Brackets(String host) {
		return host.startsWith("[") && host.endsWith("]") ? host.substring(1, host.length() - 1) : host;
	}
}
