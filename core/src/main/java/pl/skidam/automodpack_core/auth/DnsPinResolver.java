package pl.skidam.automodpack_core.auth;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.protocol.NetUtils.normalizeFingerprint;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.AddressHelpers;

/**
 * Resolves an admin-published certificate fingerprint from DNS under the
 * Minecraft hostname selected by the user.
 */
public final class DnsPinResolver {

	public static final String RECORD_PREFIX = "_automodpack.";
	public static final String RECORD_VERSION = "amp1";

	private static final List<String> DOH_RESOLVERS = List.of("https://cloudflare-dns.com/dns-query", "https://dns.quad9.net/dns-query");
	private static final Duration TIMEOUT = NetUtils.HTTP_TIMEOUT;
	private static final Duration MAX_PIN_CACHE_TIME = Duration.ofMinutes(5);
	private static final Duration MAX_ABSENCE_CACHE_TIME = Duration.ofSeconds(30);
	private static final int MAX_CACHE_ENTRIES = 128;
	private static final HttpClient HTTP_CLIENT = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
	private static final Resolver RESOLVER = new Resolver(DOH_RESOLVERS, DnsPinResolver::queryResolverAsync, System::currentTimeMillis);
	private static final Base64.Encoder DOH_QUERY_ENCODING = Base64.getUrlEncoder().withoutPadding();
	private static final int TYPE_SOA = 6, TYPE_TXT = 16, TYPE_OPT = 41;
	private static final int CLASS_IN = 1;
	private static final int EDNS_PAYLOAD_SIZE = 1232;
	private static final int DNSSEC_OK_FLAG = 0x00008000;
	private static final int FLAGS_RESPONSE = 0x8000, FLAGS_TRUNCATED = 0x0200, FLAGS_AUTHENTICATED_DATA = 0x0020;
	private static final int OPCODE_SHIFT = 11, OPCODE_QUERY = 0;
	private static final int RCODE_MASK = 0xF, RCODE_NXDOMAIN = 3;
	private static final int MAX_LABEL_LENGTH = 63;

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
			Optional<String> normalizedHost = AddressHelpers.normalizeDnsHost(minecraftHost);
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
			HttpRequest request = HttpRequest.newBuilder().uri(URI.create(resolver + "?dns=" + DOH_QUERY_ENCODING.encodeToString(buildTxtQuery(name)))).header("Accept", "application/dns-message").timeout(TIMEOUT).GET()
					.build();

			return HTTP_CLIENT.sendAsync(request, HttpResponse.BodyHandlers.ofByteArray()).thenApply(response -> {
				if (response.statusCode() < 200 || response.statusCode() >= 300) {
					LOGGER.warn("DNS fingerprint resolver {} returned HTTP {} for {}", resolver, response.statusCode(), name);
					return new ResolverUnavailable();
				}
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

	/**
	 * Builds an RFC 1035 TXT query carrying an EDNS0 OPT record with the DNSSEC OK bit set. Validating
	 * resolvers only flag answers as authenticated (AD) when the query asks for DNSSEC - measured on both
	 * Cloudflare and Quad9: wireformat answers omit AD without it, and both set it with it.
	 */
	private static byte[] buildTxtQuery(String name) {
		try {
			ByteArrayOutputStream buffer = new ByteArrayOutputStream(32 + name.length());
			DataOutputStream out = new DataOutputStream(buffer);
			out.writeShort(0); // id, not matched - the TLS transport is what authenticates the response
			out.writeShort(0x0100); // recursion desired
			out.writeShort(1); // question count
			out.writeShort(0); // answer count
			out.writeShort(0); // authority count
			out.writeShort(1); // additional count
			writeName(out, name);
			out.writeShort(TYPE_TXT);
			out.writeShort(CLASS_IN);
			out.writeByte(0); // root
			out.writeShort(TYPE_OPT);
			out.writeShort(EDNS_PAYLOAD_SIZE);
			out.writeInt(DNSSEC_OK_FLAG);
			out.writeShort(0); // no OPT options
			return buffer.toByteArray();
		} catch (IOException e) {
			throw new UncheckedIOException(e); // DataOutputStream over a ByteArrayOutputStream never throws
		}
	}

	private static void writeName(DataOutputStream out, String name) throws IOException {
		for (String label : name.split("\\.")) {
			byte[] bytes = label.getBytes(StandardCharsets.US_ASCII);
			if (label.isEmpty() || bytes.length > MAX_LABEL_LENGTH) throw new IllegalArgumentException("invalid dns label: " + label);
			out.writeByte(bytes.length);
			out.write(bytes);
		}
		out.writeByte(0);
	}

	/**
	 * Parses an RFC 1035 DoH wireformat response (RFC 8484). Answers without DNSSEC validation (AD) are
	 * unusable, NXDOMAIN is a proven absence carrying the negative TTL from the authority SOA, and anything
	 * malformed is unavailable.
	 */
	static ResolverResult parseDnsResponse(byte[] message) {
		try {
			Reader reader = new Reader(message);
			reader.readUnsignedShort(); // id
			int flags = reader.readUnsignedShort();
			int questionCount = reader.readUnsignedShort();
			int answerCount = reader.readUnsignedShort();
			int authorityCount = reader.readUnsignedShort();
			reader.readUnsignedShort(); // additional count, resolvers echo the OPT record there

			if ((flags & FLAGS_RESPONSE) == 0 || (flags & FLAGS_TRUNCATED) != 0 || (flags >>> OPCODE_SHIFT & 0xF) != OPCODE_QUERY) return new ResolverUnavailable();
			if ((flags & FLAGS_AUTHENTICATED_DATA) == 0) return new ResolverUnavailable();

			for (int i = 0; i < questionCount; i++) {
				reader.skipName();
				reader.skip(4); // question type + class
			}

			List<ResolverTxt> txtRecords = new ArrayList<>();
			for (int i = 0; i < answerCount; i++) {
				reader.skipName();
				int type = reader.readUnsignedShort();
				reader.readUnsignedShort(); // class
				long ttl = reader.readUnsignedInt();
				int length = reader.readUnsignedShort();
				if (type == TYPE_TXT) txtRecords.add(new ResolverTxt(reader.readTxtStrings(length), ttl));
				else reader.skip(length);
			}
			long negativeTtl = readNegativeTtl(reader, authorityCount);

			int rcode = flags & RCODE_MASK;
			if (rcode == RCODE_NXDOMAIN) return new ResolverAbsent(negativeTtl);
			if (rcode != 0) return new ResolverUnavailable();

			ResolverResult result = parseTxtRecordsWithTtl(txtRecords);
			if (result instanceof ResolverAbsent) return new ResolverAbsent(negativeTtl);
			return result;
		} catch (Exception e) {
			LOGGER.debug("Failed to parse DNS fingerprint response", e);
			return new ResolverUnavailable();
		}
	}

	private static long readNegativeTtl(Reader reader, int count) {
		long minimum = Long.MAX_VALUE;
		for (int i = 0; i < count; i++) {
			reader.skipName();
			int type = reader.readUnsignedShort();
			reader.readUnsignedShort(); // class
			long recordTtl = reader.readUnsignedInt();
			int length = reader.readUnsignedShort();
			if (type != TYPE_SOA) {
				reader.skip(length);
				continue;
			}
			int end = reader.offset() + length;
			reader.skipName(); // primary nameserver
			reader.skipName(); // hostmaster mailbox
			reader.skip(16); // serial, refresh, retry, expire
			long soaMinimum = reader.readUnsignedInt();
			if (reader.offset() != end) throw new IllegalArgumentException("soa record length mismatch");
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
		String owner = AddressHelpers.normalizeDnsHost(minecraftHost).orElseThrow(() -> new IllegalArgumentException("Minecraft address must be a DNS hostname"));
		return RECORD_PREFIX + owner + ". IN TXT \"v=" + RECORD_VERSION + ";fp=" + normalizeFingerprint(fingerprint) + "\"";
	}

	/**
	 * Byte reader over an RFC 1035 message with name decompression. A name ends where its encoding ends -
	 * right after the first compression pointer, not at the end of the pointed-to name - so the field cursor
	 * and the name-walk cursor are kept apart. Pointers must point strictly backwards per RFC 1035, but that
	 * alone does not rule out cycles of legal backwards pointers, so the jump budget is what guarantees
	 * termination.
	 */
	private static final class Reader {
		private final byte[] message;
		private int offset;

		Reader(byte[] message) {
			this.message = message;
		}

		int offset() {
			return offset;
		}

		int readUnsignedByte() {
			if (offset >= message.length) throw new IllegalArgumentException("dns message truncated");
			return message[offset++] & 0xFF;
		}

		int readUnsignedShort() {
			if (offset + 2 > message.length) throw new IllegalArgumentException("dns message truncated");
			int value = (message[offset] & 0xFF) << 8 | (message[offset + 1] & 0xFF);
			offset += 2;
			return value;
		}

		long readUnsignedInt() {
			if (offset + 4 > message.length) throw new IllegalArgumentException("dns message truncated");
			long value = (long) (message[offset] & 0xFF) << 24 | (message[offset + 1] & 0xFF) << 16 | (message[offset + 2] & 0xFF) << 8 | (message[offset + 3] & 0xFF);
			offset += 4;
			return value;
		}

		void skip(int count) {
			if (count < 0 || offset + count > message.length) throw new IllegalArgumentException("dns message truncated");
			offset += count;
		}

		void skipName() {
			int walk = offset;
			int resume = -1;
			int jumpsLeft = 128;
			while (true) {
				if (walk >= message.length) throw new IllegalArgumentException("dns message truncated");
				int length = message[walk++] & 0xFF;
				if (length == 0) break;
				if ((length & 0xC0) == 0xC0) {
					if (walk >= message.length) throw new IllegalArgumentException("dns message truncated");
					int pointer = (length & 0x3F) << 8 | (message[walk++] & 0xFF);
					if (pointer >= walk - 2) throw new IllegalArgumentException("dns name pointer does not point backwards");
					if (resume < 0) resume = walk;
					if (--jumpsLeft == 0) throw new IllegalArgumentException("too many dns name pointers");
					walk = pointer;
				} else if ((length & 0xC0) != 0) {
					throw new IllegalArgumentException("reserved dns label type");
				} else {
					walk += length;
				}
			}
			offset = resume < 0 ? walk : resume;
		}

		String readTxtStrings(int recordLength) {
			int end = offset + recordLength;
			if (end > message.length) throw new IllegalArgumentException("dns message truncated");
			StringBuilder value = new StringBuilder();
			while (offset < end) {
				int chunkLength = readUnsignedByte();
				if (offset + chunkLength > end) throw new IllegalArgumentException("txt chunk overruns record");
				value.append(new String(message, offset, chunkLength, StandardCharsets.UTF_8));
				offset += chunkLength;
			}
			return value.toString();
		}
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
}
