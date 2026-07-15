package pl.skidam.automodpack_core.auth;

import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

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
        assertThrows(IllegalArgumentException.class,
                () -> DnsPinResolver.parsePin("fp=" + FP_A));
        assertThrows(IllegalArgumentException.class,
                () -> DnsPinResolver.parsePin("v=amp2;fp=" + FP_A));
        assertThrows(IllegalArgumentException.class,
                () -> DnsPinResolver.parsePin("v=amp1"));
        assertThrows(IllegalArgumentException.class,
                () -> DnsPinResolver.parsePin("v=amp1;fp=" + "a".repeat(63)));
        assertThrows(IllegalArgumentException.class,
                () -> DnsPinResolver.parsePin("v=amp1;fp=" + FP_A + ";host=downloads.example.com"));
    }

    @Test
    void rejectsDuplicateFields() {
        assertThrows(IllegalArgumentException.class,
                () -> DnsPinResolver.parsePin("v=amp1;v=amp1;fp=" + FP_A));
        assertThrows(IllegalArgumentException.class,
                () -> DnsPinResolver.parsePin("v=amp1;fp=" + FP_A + ";fp=" + FP_B));
    }

    @Test
    void rejectsMultipleAmp1Records() {
        var result = DnsPinResolver.parseTxtRecords(List.of(
                "v=amp1;fp=" + FP_A,
                "v=amp1;fp=" + FP_B));

        assertInstanceOf(DnsPinResolver.ResolverMisconfigured.class, result);
    }

    @Test
    void ignoresUnrelatedTxtRecords() {
        var result = DnsPinResolver.parseTxtRecords(List.of(
                "google-site-verification=example",
                "v=amp1;fp=" + FP_A));

        var pin = assertInstanceOf(DnsPinResolver.ResolverPin.class, result);
        assertEquals(FP_A, pin.fingerprint());
    }

    @Test
    void decodesSplitTxtChunks() {
        assertEquals(
                "v=amp1;fp=" + FP_A,
                DnsPinResolver.decodeTxtData("\"v=amp1;\" \"fp=" + FP_A + "\""));
    }

    @Test
    void formatsCanonicalRecord() {
        assertEquals(
                "_automodpack.play.example.com. IN TXT \"v=amp1;fp=" + FP_A + "\"",
                DnsPinResolver.formatRecord("Play.Example.COM.", FP_A));
    }

    @Test
    void rejectsIpIdentityWhenFormatting() {
        assertThrows(IllegalArgumentException.class,
                () -> DnsPinResolver.formatRecord("192.0.2.1", FP_A));
    }

    @Test
    void detectsOnlyValidIpLiterals() {
        assertTrue(DnsPinResolver.isIpLiteral("192.168.1.1"));
        assertTrue(DnsPinResolver.isIpLiteral("::1"));
        assertTrue(DnsPinResolver.isIpLiteral("[2001:db8::1]"));
        assertFalse(DnsPinResolver.isIpLiteral("999.168.1.1"));
        assertFalse(DnsPinResolver.isIpLiteral("example.com"));
    }
}
