package pl.skidam.automodpack_core.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

class DnsPinResolverTest {

	private static final String FP_A = "a".repeat(64);
	private static final String FP_B = "b".repeat(64);

	@Test
	void parsesSingleFingerprint() {
		assertEquals(FP_A, DnsPinResolver.parsePin("v=amp1;fp=" + FP_A));
	}

	@Test
	void normalizesColonSeparatedUppercaseFingerprint() {
		String pretty = String.join(":", Collections.nCopies(32, "AB"));

		assertEquals("ab".repeat(32), DnsPinResolver.parsePin("v=amp1;fp=" + pretty));
	}

	@Test
	void rejectsMissingMalformedAndUnknownFields() {
		assertThrows(IllegalArgumentException.class, () -> DnsPinResolver.parsePin("fp=" + FP_A));
		assertThrows(IllegalArgumentException.class, () -> DnsPinResolver.parsePin("v=amp2;fp=" + FP_A));
		assertThrows(IllegalArgumentException.class, () -> DnsPinResolver.parsePin("v=amp1"));
		assertThrows(IllegalArgumentException.class, () -> DnsPinResolver.parsePin("v=amp1;fp=" + "a".repeat(63)));
		assertThrows(IllegalArgumentException.class, () -> DnsPinResolver.parsePin("v=amp1;fp=" + FP_A + ";host=downloads.example.com"));
	}

	@Test
	void rejectsDuplicateFields() {
		assertThrows(IllegalArgumentException.class, () -> DnsPinResolver.parsePin("v=amp1;v=amp1;fp=" + FP_A));
		assertThrows(IllegalArgumentException.class, () -> DnsPinResolver.parsePin("v=amp1;fp=" + FP_A + ";fp=" + FP_B));
	}

	@Test
	void rejectsMultipleAmp1Records() {
		var result = DnsPinResolver.parseTxtRecords(List.of("v=amp1;fp=" + FP_A, "v=amp1;fp=" + FP_B));

		assertInstanceOf(DnsPinResolver.ResolverMisconfigured.class, result);
	}

	@Test
	void ignoresUnrelatedTxtRecords() {
		var result = DnsPinResolver.parseTxtRecords(List.of("google-site-verification=example", "v=amp1;fp=" + FP_A));

		var pin = assertInstanceOf(DnsPinResolver.ResolverPin.class, result);
		assertEquals(FP_A, pin.fingerprint());
	}

	@Test
	void decodesSplitTxtChunks() {
		assertEquals("v=amp1;fp=" + FP_A, DnsPinResolver.decodeTxtData("\"v=amp1;\" \"fp=" + FP_A + "\""));
	}

	@Test
	void formatsCanonicalRecord() {
		assertEquals("_automodpack.play.example.com. IN TXT \"v=amp1;fp=" + FP_A + "\"", DnsPinResolver.formatRecord("Play.Example.COM.", FP_A));
	}

	@Test
	void rejectsIpIdentityWhenFormatting() {
		assertThrows(IllegalArgumentException.class, () -> DnsPinResolver.formatRecord("192.0.2.1", FP_A));
	}

	@Test
	void detectsOnlyValidIpLiterals() {
		assertTrue(DnsPinResolver.isIpLiteral("192.168.1.1"));
		assertTrue(DnsPinResolver.isIpLiteral("::1"));
		assertTrue(DnsPinResolver.isIpLiteral("[2001:db8::1]"));
		assertFalse(DnsPinResolver.isIpLiteral("999.168.1.1"));
		assertFalse(DnsPinResolver.isIpLiteral("example.com"));
	}

	@Test
	void coalescesParallelResolverPairsAndCapsPositiveCache() {
		AtomicLong clock = new AtomicLong();
		AtomicInteger calls = new AtomicInteger();
		List<CompletableFuture<DnsPinResolver.ResolverResult>> pending = new ArrayList<>();
		DnsPinResolver.Resolver resolver = new DnsPinResolver.Resolver(List.of("one", "two"), (ignoredResolver, ignoredName) -> {
			calls.incrementAndGet();
			CompletableFuture<DnsPinResolver.ResolverResult> future = new CompletableFuture<>();
			pending.add(future);
			return future;
		}, clock::get);

		CompletableFuture<DnsPinResolver.LookupResult> first = resolver.resolvePinAsync("play.example.com");
		CompletableFuture<DnsPinResolver.LookupResult> second = resolver.resolvePinAsync("PLAY.EXAMPLE.COM.");

		assertSame(first, second);
		assertEquals(2, calls.get());
		pending.get(0).complete(new DnsPinResolver.ResolverPin(FP_A, 3600));
		assertFalse(first.isDone());
		pending.get(1).complete(new DnsPinResolver.ResolverPin(FP_A, 3600));
		assertEquals(FP_A, assertInstanceOf(DnsPinResolver.Authoritative.class, first.join()).fingerprint());

		clock.set(299_999);
		assertEquals(FP_A, assertInstanceOf(DnsPinResolver.Authoritative.class, resolver.resolvePinAsync("play.example.com").join()).fingerprint());
		assertEquals(2, calls.get());

		clock.set(300_001);
		resolver.resolvePinAsync("play.example.com");
		assertEquals(4, calls.get());
	}

	@Test
	void brieflyCachesAuthoritativeAbsenceButNotUnavailableResults() {
		AtomicLong clock = new AtomicLong();
		AtomicInteger absentCalls = new AtomicInteger();
		DnsPinResolver.Resolver absentResolver = new DnsPinResolver.Resolver(List.of("one", "two"), (ignoredResolver, ignoredName) -> {
			absentCalls.incrementAndGet();
			return CompletableFuture.completedFuture(new DnsPinResolver.ResolverAbsent(120));
		}, clock::get);

		assertEquals(DnsPinResolver.NoPolicyReason.ABSENT,
				assertInstanceOf(DnsPinResolver.NoPolicy.class, absentResolver.resolvePinAsync("play.example.com").join()).reason());
		clock.set(29_999);
		absentResolver.resolvePinAsync("play.example.com").join();
		assertEquals(2, absentCalls.get());
		clock.set(30_001);
		absentResolver.resolvePinAsync("play.example.com").join();
		assertEquals(4, absentCalls.get());

		AtomicInteger unavailableCalls = new AtomicInteger();
		DnsPinResolver.Resolver unavailableResolver = new DnsPinResolver.Resolver(List.of("one", "two"), (ignoredResolver, ignoredName) -> {
			unavailableCalls.incrementAndGet();
			return CompletableFuture.completedFuture(new DnsPinResolver.ResolverUnavailable());
		}, clock::get);
		unavailableResolver.resolvePinAsync("other.example.com").join();
		unavailableResolver.resolvePinAsync("other.example.com").join();
		assertEquals(4, unavailableCalls.get());
	}

	@Test
	void parsesPositiveAndNegativeTtls() {
		String positive = "{\"Status\":0,\"AD\":true,\"Answer\":[{\"type\":16,\"TTL\":600,\"data\":\"\\\"v=amp1;fp=" + FP_A + "\\\"\"}]}";
		DnsPinResolver.ResolverPin pin = assertInstanceOf(DnsPinResolver.ResolverPin.class, DnsPinResolver.parseDnsResponse(positive));
		assertEquals(600, pin.ttlSeconds());

		String absent = "{\"Status\":3,\"AD\":true,\"Authority\":[{\"type\":6,\"TTL\":120,\"data\":\"ns.example. hostmaster.example. 1 2 3 4 90\"}]}";
		DnsPinResolver.ResolverAbsent noPolicy = assertInstanceOf(DnsPinResolver.ResolverAbsent.class, DnsPinResolver.parseDnsResponse(absent));
		assertEquals(90, noPolicy.ttlSeconds());
	}
}
