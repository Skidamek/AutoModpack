package pl.skidam.automodpack_core.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static pl.skidam.automodpack_core.Constants.LOGGER;

/**
 * Resolves an admin-published certificate pin from DNS:
 * {@code _automodpack.<host>. TXT "v=amp1;fp=<sha256-hex>"}
 *
 * The record is fetched over DNS-over-HTTPS from two independent resolvers and is only
 * used when BOTH report the answer as DNSSEC-validated (AD flag) and agree on the pin.
 * Trust is therefore anchored out-of-band: in the WebPKI certificates of the resolvers
 * and in the DNSSEC chain of the server's zone - never in anything the modpack host
 * itself sends. Anything less than full agreement yields empty, falling back to the
 * regular manual fingerprint verification.
 */
public class DnsPinResolver {

	public static final String RECORD_PREFIX = "_automodpack.";
	public static final String RECORD_VERSION = "v=amp1";
	public static final String RECORD_FINGERPRINT_PREFIX = "fp=";

	private static final List<String> DOH_RESOLVERS = List.of(
			"https://cloudflare-dns.com/dns-query",
			"https://doh.mullvad.net/dns-query"
	);
	private static final Duration TIMEOUT = Duration.ofSeconds(5);
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	private static final Map<String, CompletableFuture<Optional<String>>> CACHE = new ConcurrentHashMap<>();

	public static Optional<String> resolvePin(String host) {
		if (host == null || host.isBlank() || isIpLiteral(host)) {
			return Optional.empty();
		}

		String normalizedHost = host.trim().toLowerCase(Locale.ROOT);

		return CACHE.computeIfAbsent(
				normalizedHost,
				DnsPinResolver::queryAllResolversAsync
		).join();
	}

	private static CompletableFuture<Optional<String>> queryAllResolversAsync(String host) {
		String name = RECORD_PREFIX + host;

		List<CompletableFuture<Optional<String>>> futures = DOH_RESOLVERS.stream()
				.map(resolver -> queryValidatedPinAsync(resolver, name))
				.toList();

		return CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))
				.thenApply(v -> {
					String agreedPin = null;

					for (CompletableFuture<Optional<String>> future : futures) {
						Optional<String> pin = future.join();

						if (pin.isEmpty()) {
							return Optional.empty();
						}

						if (agreedPin == null) {
							agreedPin = pin.get();
						} else if (!agreedPin.equals(pin.get())) {
							LOGGER.warn("DNS resolvers disagree on the certificate pin for {}", host);
							return Optional.empty();
						}
					}

					return Optional.ofNullable(agreedPin);
				});
	}

	private static CompletableFuture<Optional<String>> queryValidatedPinAsync(String resolver, String name) {
		try {
			HttpRequest request = HttpRequest.newBuilder()
					.uri(URI.create(resolver + "?name=" + URLEncoder.encode(name, StandardCharsets.UTF_8) + "&type=TXT"))
					.header("Accept", "application/dns-json")
					.timeout(TIMEOUT)
					.GET()
					.build();

			return HTTP_CLIENT
					.sendAsync(request, HttpResponse.BodyHandlers.ofString())
					.thenApply(HttpResponse::body)
					.thenApply(DnsPinResolver::parseDnsResponse)
					.exceptionally(e -> {
						LOGGER.debug("DNS pin lookup for {} via {} failed", name, resolver, e);
						return Optional.empty();
					});

		} catch (Exception e) {
			LOGGER.debug("Failed to build or initialize request for {} via {}", name, resolver, e);
			return CompletableFuture.completedFuture(Optional.empty());
		}
	}

	private static Optional<String> parseDnsResponse(String body) {
		try {
			JsonObject json = JsonParser.parseString(body).getAsJsonObject();

			if (json.get("Status").getAsInt() != 0) {
				return Optional.empty();
			}

			boolean hasAnswer = json.has("Answer") && !json.getAsJsonArray("Answer").isEmpty();
			boolean isAuthenticData = json.has("AD") && json.get("AD").getAsBoolean();

			if (hasAnswer && !isAuthenticData) {
				LOGGER.warn("Found automodpack TXT record, but the zone is NOT DNSSEC-validated (AD flag missing). Ignoring unsafe record.");
				return Optional.empty();
			}

			if (!isAuthenticData || !hasAnswer) {
				return Optional.empty();
			}

			for (JsonElement element : json.getAsJsonArray("Answer")) {
				JsonObject answer = element.getAsJsonObject();

				if (answer.get("type").getAsInt() != 16) {
					continue;
				}

				Optional<String> pin = parsePin(
						answer.get("data").getAsString()
								.replace("\"", "")
								.trim()
				);

				if (pin.isPresent()) {
					return pin;
				}
			}
		} catch (Exception e) {
			LOGGER.debug("Failed to parse DNS response body JSON structure", e);
		}

		return Optional.empty();
	}

	static Optional<String> parsePin(String txt) {
		if (txt == null || !txt.startsWith(RECORD_VERSION)) {
			return Optional.empty();
		}

		for (String part : txt.split(";")) {
			part = part.trim();
			if (!part.startsWith(RECORD_FINGERPRINT_PREFIX)) {
				continue;
			}

			String fingerprint = part.substring(3).replace(":", "").toLowerCase(Locale.ROOT);
			if (fingerprint.matches("[0-9a-f]{64}")) {
				return Optional.of(fingerprint);
			}
		}

		return Optional.empty();
	}

	static boolean isIpLiteral(String host) {
		return host.contains(":") || host.matches("\\d{1,3}(\\.\\d{1,3}){3}");
	}
}