package pl.skidam.automodpack_core.auth;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.utils.AddressHelpers;

class DnsPinResolverTest {

	private static final String FP_A = "a".repeat(64);
	private static final String FP_B = "b".repeat(64);
	private static final int FLAG_RESPONSE = 0x8000, FLAG_RECURSION = 0x0080, FLAG_AUTHENTICATED = 0x0020, FLAG_TRUNCATED = 0x0200;
	private static final int FLAG_SERVFAIL = 0x0002, FLAG_NXDOMAIN = 0x0003;

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
	void parsesWireformatTxtAnswerWithTtl() {
		byte[] response = dnsResponse(FLAG_RESPONSE | FLAG_RECURSION | FLAG_AUTHENTICATED, List.of(txtRecord(nameBytes("_automodpack.play.example.com"), 600, "v=amp1;fp=" + FP_A)), List.of());

		DnsPinResolver.ResolverPin pin = assertInstanceOf(DnsPinResolver.ResolverPin.class, DnsPinResolver.parseDnsResponse(response));
		assertEquals(FP_A, pin.fingerprint());
		assertEquals(600, pin.ttlSeconds());
	}

	@Test
	void joinsSplitTxtCharacterStrings() {
		byte[] response = dnsResponse(FLAG_RESPONSE | FLAG_RECURSION | FLAG_AUTHENTICATED, List.of(txtRecord(nameBytes("_automodpack.play.example.com"), 600, "v=amp1;", "fp=" + FP_A)), List.of());

		DnsPinResolver.ResolverPin pin = assertInstanceOf(DnsPinResolver.ResolverPin.class, DnsPinResolver.parseDnsResponse(response));
		assertEquals(FP_A, pin.fingerprint());
	}

	@Test
	void derivesNegativeTtlFromSoaAuthority() {
		byte[] soa = record(nameBytes("play.example.com"), 6, 120, soaRdata(nameBytes("ns.example.com"), nameBytes("hostmaster.example.com"), 90));
		byte[] response = dnsResponse(FLAG_RESPONSE | FLAG_RECURSION | FLAG_AUTHENTICATED | FLAG_NXDOMAIN, List.of(), List.of(soa));

		DnsPinResolver.ResolverAbsent absent = assertInstanceOf(DnsPinResolver.ResolverAbsent.class, DnsPinResolver.parseDnsResponse(response));
		assertEquals(90, absent.ttlSeconds());
	}

	@Test
	void followsBackwardsCompressionPointers() {
		// the question name sits at offset 12, so pointers to it are legal backwards references
		byte[] pointed = pointerBytes(12);
		byte[] soa = record(nameBytes("play.example.com"), 6, 120, soaRdata(pointed, pointed, 90));
		byte[] response = dnsResponse(FLAG_RESPONSE | FLAG_RECURSION | FLAG_AUTHENTICATED, List.of(txtRecord(pointed, 600, "v=amp1;fp=" + FP_A)), List.of(soa));

		DnsPinResolver.ResolverPin pin = assertInstanceOf(DnsPinResolver.ResolverPin.class, DnsPinResolver.parseDnsResponse(response));
		assertEquals(FP_A, pin.fingerprint());
	}

	@Test
	void rejectsUnauthenticatedAnswers() {
		byte[] response = dnsResponse(FLAG_RESPONSE | FLAG_RECURSION, List.of(txtRecord(nameBytes("_automodpack.play.example.com"), 600, "v=amp1;fp=" + FP_A)), List.of());

		assertInstanceOf(DnsPinResolver.ResolverUnavailable.class, DnsPinResolver.parseDnsResponse(response));
	}

	@Test
	void rejectsErrorCodesAndTruncation() {
		List<byte[]> answer = List.of(txtRecord(nameBytes("_automodpack.play.example.com"), 600, "v=amp1;fp=" + FP_A));

		assertInstanceOf(DnsPinResolver.ResolverUnavailable.class, DnsPinResolver.parseDnsResponse(dnsResponse(FLAG_RESPONSE | FLAG_RECURSION | FLAG_AUTHENTICATED | FLAG_SERVFAIL, answer, List.of())));
		assertInstanceOf(DnsPinResolver.ResolverUnavailable.class, DnsPinResolver.parseDnsResponse(dnsResponse(FLAG_RESPONSE | FLAG_RECURSION | FLAG_AUTHENTICATED | FLAG_TRUNCATED, answer, List.of())));
	}

	@Test
	void rejectsMalformedWireformatResponses() {
		// the standard question section ends at offset 46, so answers begin at 47
		byte[] cyclicOwner = new byte[22];
		cyclicOwner[0] = 19;
		Arrays.fill(cyclicOwner, 1, 20, (byte) 'a');
		cyclicOwner[20] = (byte) 0xC0;
		cyclicOwner[21] = 47; // legal backwards pointer through a long label re-enters itself - only the jump budget terminates this
		byte[] headerOnly = new byte[]{0, 0, (byte) 0x81, (byte) 0xA0, 0, 1, 0, 1, 0, 0, 0, 0};
		List<byte[]> malformed = List.of(headerOnly, record(new byte[]{(byte) 0x80, 3}, 16, 600, new byte[]{4, 'a', 'b', 'c', 'd'}), record(new byte[]{(byte) 0xC0, 47}, 16, 600, new byte[]{4, 'a', 'b', 'c', 'd'}),
				record(cyclicOwner, 16, 600, new byte[]{4, 'a', 'b', 'c', 'd'}),
				record(nameBytes("play.example.com"), 16, 600, new byte[]{20, 'a', 'b'}), record(nameBytes("play.example.com"), 6, 120, new byte[]{9, 'n', 's', 2, 'n', 's', 0, 1, 2, 3}));

		for (int i = 0; i < malformed.size(); i++) {
			assertInstanceOf(DnsPinResolver.ResolverUnavailable.class, DnsPinResolver.parseDnsResponse(malformed.get(i)), "expected rejection at index " + i);
		}
	}

	@Test
	void formatsCanonicalRecord() {
		assertEquals("_automodpack.play.example.com. IN TXT \"v=amp1;fp=" + FP_A + "\"", DnsPinResolver.formatRecord("Play.Example.COM.", FP_A));
	}

	@Test
	void aMisconfiguredResolverPoisonsTheCombination() {
		var poisoned = DnsPinResolver.combineResolverResults("play.example.com",
				List.of(new DnsPinResolver.ResolverMisconfigured("unknown amp1 field: host"), new DnsPinResolver.ResolverPin(FP_A, 300)));

		assertEquals("unknown amp1 field: host", assertInstanceOf(DnsPinResolver.Misconfigured.class, poisoned.result()).reason());
	}

	@Test
	void disagreeingPinsPoisonTheCombination() {
		var disagreeing = DnsPinResolver.combineResolverResults("play.example.com",
				List.of(new DnsPinResolver.ResolverPin(FP_A, 300), new DnsPinResolver.ResolverPin(FP_B, 300)));

		assertEquals("resolvers disagree on the fingerprint", assertInstanceOf(DnsPinResolver.Misconfigured.class, disagreeing.result()).reason());
	}

	@Test
	void agreeingPinsAreAuthoritativeEvenBesideAbsence() {
		var unanimous = DnsPinResolver.combineResolverResults("play.example.com",
				List.of(new DnsPinResolver.ResolverPin(FP_A, 300), new DnsPinResolver.ResolverPin(FP_A, 600)));
		var pinnedBesideAbsence = DnsPinResolver.combineResolverResults("play.example.com",
				List.of(new DnsPinResolver.ResolverPin(FP_A, 300), new DnsPinResolver.ResolverAbsent(120)));

		assertEquals(FP_A, assertInstanceOf(DnsPinResolver.Authoritative.class, unanimous.result()).fingerprint());
		assertEquals(300, unanimous.ttlSeconds());
		assertEquals(FP_A, assertInstanceOf(DnsPinResolver.Authoritative.class, pinnedBesideAbsence.result()).fingerprint());
	}

	@Test
	void unanimousAbsenceReadsAsNoPolicy() {
		var absent = DnsPinResolver.combineResolverResults("play.example.com",
				List.of(new DnsPinResolver.ResolverAbsent(120), new DnsPinResolver.ResolverAbsent(60)));

		assertEquals(DnsPinResolver.NoPolicyReason.ABSENT, assertInstanceOf(DnsPinResolver.NoPolicy.class, absent.result()).reason());
		assertEquals(60, absent.ttlSeconds());
	}

	@Test
	void aPinBesideMisconfigurationFailsClosed() {
		var poisoned = DnsPinResolver.combineResolverResults("play.example.com",
				List.of(new DnsPinResolver.ResolverPin(FP_A, 300), new DnsPinResolver.ResolverMisconfigured("unsupported amp1 version")));

		assertInstanceOf(DnsPinResolver.Misconfigured.class, poisoned.result());
	}

	@Test
	void mixedUnavailableReadsAsUnavailableAndNeverAsAbsence() {
		var unavailable = DnsPinResolver.combineResolverResults("play.example.com",
				List.of(new DnsPinResolver.ResolverUnavailable(), new DnsPinResolver.ResolverAbsent(120)));
		var allUnavailable = DnsPinResolver.combineResolverResults("play.example.com",
				List.of(new DnsPinResolver.ResolverUnavailable(), new DnsPinResolver.ResolverUnavailable()));

		assertEquals(DnsPinResolver.NoPolicyReason.UNAVAILABLE, assertInstanceOf(DnsPinResolver.NoPolicy.class, unavailable.result()).reason());
		assertEquals(DnsPinResolver.NoPolicyReason.UNAVAILABLE, assertInstanceOf(DnsPinResolver.NoPolicy.class, allUnavailable.result()).reason());
	}

	@Test
	void rejectsIpIdentityWhenFormatting() {
		assertThrows(IllegalArgumentException.class, () -> DnsPinResolver.formatRecord("192.0.2.1", FP_A));
	}

	@Test
	void detectsOnlyValidIpLiterals() {
		assertTrue(AddressHelpers.isIpLiteral("192.168.1.1"));
		assertTrue(AddressHelpers.isIpLiteral("::1"));
		assertTrue(AddressHelpers.isIpLiteral("[2001:db8::1]"));
		assertFalse(AddressHelpers.isIpLiteral("999.168.1.1"));
		assertFalse(AddressHelpers.isIpLiteral("example.com"));
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

	private static byte[] dnsResponse(int flags, List<byte[]> answers, List<byte[]> authority) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		putShort(out, 0);
		putShort(out, flags);
		putShort(out, 1); // question count
		putShort(out, answers.size());
		putShort(out, authority.size());
		putShort(out, 0); // additional count
		nameBytesInto(out, "_automodpack.play.example.com");
		putShort(out, 16); // TXT
		putShort(out, 1); // IN
		answers.forEach(out::writeBytes);
		authority.forEach(out::writeBytes);
		return out.toByteArray();
	}

	private static byte[] txtRecord(byte[] owner, long ttl, String... chunks) {
		ByteArrayOutputStream rdata = new ByteArrayOutputStream();
		for (String chunk : chunks) {
			byte[] bytes = chunk.getBytes(StandardCharsets.UTF_8);
			if (bytes.length > 255) throw new IllegalArgumentException("chunk too long");
			rdata.write(bytes.length);
			rdata.writeBytes(bytes);
		}
		return record(owner, 16, ttl, rdata.toByteArray());
	}

	private static byte[] record(byte[] owner, int type, long ttl, byte[] rdata) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(owner);
		putShort(out, type);
		putShort(out, 1); // IN
		putInt(out, ttl);
		putShort(out, rdata.length);
		out.writeBytes(rdata);
		return out.toByteArray();
	}

	private static byte[] soaRdata(byte[] mname, byte[] rname, long minimum) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		out.writeBytes(mname);
		out.writeBytes(rname);
		putInt(out, 1); // serial
		putInt(out, 2); // refresh
		putInt(out, 3); // retry
		putInt(out, 4); // expire
		putInt(out, minimum);
		return out.toByteArray();
	}

	private static byte[] nameBytes(String name) {
		ByteArrayOutputStream out = new ByteArrayOutputStream();
		nameBytesInto(out, name);
		return out.toByteArray();
	}

	private static void nameBytesInto(ByteArrayOutputStream out, String name) {
		for (String label : name.split("\\.")) {
			out.write(label.length());
			out.writeBytes(label.getBytes(StandardCharsets.US_ASCII));
		}
		out.write(0);
	}

	private static byte[] pointerBytes(int offset) {
		return new byte[]{(byte) (0xC0 | offset >> 8), (byte) offset};
	}

	private static void putShort(ByteArrayOutputStream out, int value) {
		out.write(value >>> 8 & 0xFF);
		out.write(value & 0xFF);
	}

	private static void putInt(ByteArrayOutputStream out, long value) {
		out.write((int) (value >>> 24 & 0xFF));
		out.write((int) (value >>> 16 & 0xFF));
		out.write((int) (value >>> 8 & 0xFF));
		out.write((int) (value & 0xFF));
	}
}
