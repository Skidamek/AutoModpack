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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.protocol.NetUtils;

/**
 * Resolves an admin-published certificate fingerprint from DNS under the
 * Minecraft hostname selected by the user.
 */
public final class DnsPinResolver {

	public static final String RECORD_PREFIX = "_automodpack.";
	public static final String RECORD_VERSION = "amp1";

	private static final List<String> DOH_RESOLVERS = List.of("https://cloudflare-dns.com/dns-query", "https://doh.mullvad.net/dns-query");
	private static final Duration TIMEOUT = NetUtils.HTTP_TIMEOUT;
	private static final Duration MAX_PIN_CACHE_TIME = Duration.ofMinutes(5);
	private static final Duration MAX_ABSENCE_CACHE_TIME = Duration.ofSeconds(30);
	private static final int MAX_CACHE_ENTRIES = 128;
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	private static final Resolver RESOLVER = new Resolver(DOH_RESOLVERS, DnsPinResolver::queryResolverAsync, System::currentTimeMillis);

	private DnsPinResolver() {}

	public sealed interface LookupResult permits Authoritative, NoPolicy, Misconfigured {}

	public record Authoritative(String fingerprint) implements LookupResult {}

	public record NoPolicy(NoPolicyReason reason) implements LookupResult {}

	public record Misconfigured(String reason) implements LookupResult {}

	public enum NoPolicyReason {
		IP_LITERAL, ABSENT, UNAVAILABLE
	}

	sealed interface ResolverResult permits ResolverPin, ResolverAbsent, ResolverUnavailable, ResolverMisconfigured {}

	record ResolverPin(String fingerprint, long ttlSeconds) implements ResolverResult {
		ResolverPin(String fingerprint) {
			this(fingerprint, 0);
		}
	}

	record ResolverAbsent(long ttlSeconds) implements ResolverResult {
		ResolverAbsent() {
			this(0);
		}
	}

	record ResolverUnavailable() implements ResolverResult {}

	record ResolverMisconfigured(String reason) implements ResolverResult {}

	private record ResolverTxt(String value, long ttlSeconds) {}

	private record CombinedResult(LookupResult result, long ttlSeconds) {}

	private record CacheEntry(LookupResult result, long expiresAtMillis) {}

	@FunctionalInterface
	interface ResolverQuery {
		CompletableFuture<ResolverResult> query(String resolver, String name);
	}

	public static CompletableFuture<LookupResult> resolvePinAsync(String minecraftHost) {
		return RESOLVER.resolvePinAsync(minecraftHost);
	}

	static final class Resolver {
		private final List<String> resolvers;
		private final ResolverQuery query;
		private final LongSupplier currentTimeMillis;
		private final Map<String, CacheEntry> cache = new LinkedHashMap<>(16, 0.75f, true);
		private final ConcurrentHashMap<String, CompletableFuture<LookupResult>> inFlight = new ConcurrentHashMap<>();

		Resolver(List<String> resolvers, ResolverQuery query, LongSupplier currentTimeMillis) {
			this.resolvers = List.copyOf(resolvers);
			this.query = query;
			this.currentTimeMillis = currentTimeMillis;
		}

		CompletableFuture<LookupResult> resolvePinAsync(String minecraftHost) {
			Optional<String> normalizedHost = normalizeDnsHost(minecraftHost);
			if (normalizedHost.isEmpty()) return CompletableFuture.completedFuture(new NoPolicy(NoPolicyReason.IP_LITERAL));

			String host = normalizedHost.get();
			LookupResult cached = getCached(host);
			if (cached != null) return CompletableFuture.completedFuture(cached);

			CompletableFuture<LookupResult> existing = inFlight.get(host);
			if (existing != null) return existing;

			CompletableFuture<LookupResult> created = queryResolvers(host);
			CompletableFuture<LookupResult> raced = inFlight.putIfAbsent(host, created);
			if (raced != null) return raced;

			created.whenComplete((result, error) -> inFlight.remove(host, created));
			return created;
		}

		private CompletableFuture<LookupResult> queryResolvers(String host) {
			String name = RECORD_PREFIX + host;
			List<CompletableFuture<ResolverResult>> futures = resolvers.stream().map(resolver -> safeQuery(resolver, name)).toList();

			return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).thenApply(ignored -> {
				CombinedResult combined = combineResolverResults(host, futures.stream().map(CompletableFuture::join).toList());
				cache(host, combined);
				return combined.result();
			});
		}

		private CompletableFuture<ResolverResult> safeQuery(String resolver, String name) {
			try {
				return query.query(resolver, name).exceptionally(error -> new ResolverUnavailable());
			} catch (Exception e) {
				return CompletableFuture.completedFuture(new ResolverUnavailable());
			}
		}

		private LookupResult getCached(String host) {
			synchronized (cache) {
				CacheEntry entry = cache.get(host);
				if (entry == null) return null;
				if (entry.expiresAtMillis() <= currentTimeMillis.getAsLong()) {
					cache.remove(host);
					return null;
				}
				return entry.result();
			}
		}

		private void cache(String host, CombinedResult combined) {
			long maxMillis;
			if (combined.result() instanceof Authoritative) {
				maxMillis = MAX_PIN_CACHE_TIME.toMillis();
			} else if (combined.result() instanceof NoPolicy noPolicy && noPolicy.reason() == NoPolicyReason.ABSENT) {
				maxMillis = MAX_ABSENCE_CACHE_TIME.toMillis();
			} else {
				return;
			}
			if (combined.ttlSeconds() <= 0) return;

			long ttlMillis = Math.min(combined.ttlSeconds(), maxMillis / 1000) * 1000;
			long expiresAt = currentTimeMillis.getAsLong() + ttlMillis;
			synchronized (cache) {
				cache.put(host, new CacheEntry(combined.result(), expiresAt));
				while (cache.size() > MAX_CACHE_ENTRIES) {
					String eldest = cache.keySet().iterator().next();
					cache.remove(eldest);
				}
			}
		}
	}

	private static CombinedResult combineResolverResults(String host, List<ResolverResult> results) {
		if (results.stream().allMatch(ResolverAbsent.class::isInstance)) {
			return new CombinedResult(new NoPolicy(NoPolicyReason.ABSENT), minimumTtl(results));
		}

		if (results.stream().allMatch(ResolverMisconfigured.class::isInstance)) {
			String reason = ((ResolverMisconfigured) results.get(0)).reason();
			LOGGER.error("DNSSEC AutoModpack fingerprint for {} is invalid: {}", host, reason);
			return new CombinedResult(new Misconfigured(reason), 0);
		}

		if (results.stream().allMatch(ResolverPin.class::isInstance)) {
			String expected = ((ResolverPin) results.get(0)).fingerprint();
			boolean agrees = results.stream().map(ResolverPin.class::cast).allMatch(result -> result.fingerprint().equals(expected));
			if (agrees) return new CombinedResult(new Authoritative(expected), minimumTtl(results));
			LOGGER.warn("DNS resolvers disagree on the AutoModpack fingerprint for {}", host);
		}

		return new CombinedResult(new NoPolicy(NoPolicyReason.UNAVAILABLE), 0);
	}

	private static long minimumTtl(List<ResolverResult> results) {
		long minimum = Long.MAX_VALUE;
		for (ResolverResult result : results) {
			long ttl = result instanceof ResolverPin pin ? pin.ttlSeconds() : ((ResolverAbsent) result).ttlSeconds();
			if (ttl <= 0) return 0;
			minimum = Math.min(minimum, ttl);
		}
		return minimum == Long.MAX_VALUE ? 0 : minimum;
	}

	private static CompletableFuture<ResolverResult> queryResolverAsync(String resolver, String name) {
		try {
			HttpRequest request = HttpRequest.newBuilder().uri(URI.create(resolver + "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&type=TXT"))
					.header("Accept", "application/dns-json").timeout(TIMEOUT).GET().build();

			return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofString()).thenApply(response -> {
				if (response.statusCode() < 200 || response.statusCode() >= 300) return new ResolverUnavailable();
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

			if (!authenticated) return new ResolverUnavailable();

			long negativeTtl = parseNegativeTtl(json);
			if (status == 3) return new ResolverAbsent(negativeTtl);
			if (status != 0) return new ResolverUnavailable();

			List<ResolverTxt> txtRecords = new ArrayList<>();
			if (json.has("Answer")) {
				for (JsonElement element : json.getAsJsonArray("Answer")) {
					JsonObject answer = element.getAsJsonObject();
					if (answer.has("type") && answer.get("type").getAsInt() == 16 && answer.has("data")) {
						long ttl = answer.has("TTL") ? Math.max(0, answer.get("TTL").getAsLong()) : 0;
						txtRecords.add(new ResolverTxt(decodeTxtData(answer.get("data").getAsString()), ttl));
					}
				}
			}
			ResolverResult result = parseTxtRecordsWithTtl(txtRecords);
			if (result instanceof ResolverAbsent) return new ResolverAbsent(negativeTtl);
			return result;
		} catch (Exception e) {
			LOGGER.debug("Failed to parse DNS fingerprint response", e);
			return new ResolverUnavailable();
		}
	}

	private static long parseNegativeTtl(JsonObject json) {
		if (!json.has("Authority")) return 0;
		long minimum = Long.MAX_VALUE;
		for (JsonElement element : json.getAsJsonArray("Authority")) {
			JsonObject authority = element.getAsJsonObject();
			if (!authority.has("type") || authority.get("type").getAsInt() != 6 || !authority.has("data")) continue;

			long recordTtl = authority.has("TTL") ? Math.max(0, authority.get("TTL").getAsLong()) : 0;
			String[] fields = authority.get("data").getAsString().trim().split("\\s+");
			long soaMinimum = 0;
			if (fields.length > 0) {
				try {
					soaMinimum = Math.max(0, Long.parseLong(fields[fields.length - 1]));
				} catch (NumberFormatException ignored) {
				}
			}
			long ttl = recordTtl == 0 ? soaMinimum : soaMinimum == 0 ? recordTtl : Math.min(recordTtl, soaMinimum);
			if (ttl > 0) minimum = Math.min(minimum, ttl);
		}
		return minimum == Long.MAX_VALUE ? 0 : minimum;
	}

	static ResolverResult parseTxtRecords(List<String> txtRecords) {
		return parseTxtRecordsWithTtl(txtRecords.stream().map(value -> new ResolverTxt(value, 0)).toList());
	}

	private static ResolverResult parseTxtRecordsWithTtl(List<ResolverTxt> txtRecords) {
		String fingerprint = null;
		long ttl = 0;
		for (ResolverTxt record : txtRecords) {
			String txt = record.value();
			if (!isAmp1Record(txt)) continue;
			if (fingerprint != null) return new ResolverMisconfigured("multiple amp1 records are not allowed");

			try {
				fingerprint = parsePin(txt);
				ttl = record.ttlSeconds();
			} catch (IllegalArgumentException e) {
				return new ResolverMisconfigured(e.getMessage());
			}
		}

		return fingerprint == null ? new ResolverAbsent() : new ResolverPin(fingerprint, ttl);
	}

	static String parsePin(String txt) {
		if (txt == null) throw new IllegalArgumentException("empty amp1 record");

		String version = null;
		String fingerprint = null;

		for (String rawPart : txt.split(";", -1)) {
			String part = rawPart.trim();
			int separator = part.indexOf('=');
			if (separator <= 0) throw new IllegalArgumentException("invalid amp1 field: " + part);

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
