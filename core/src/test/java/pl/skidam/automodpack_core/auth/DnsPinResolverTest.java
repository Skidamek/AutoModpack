package pl.skidam.automodpack_core.auth;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

public class DnsPinResolverTest {

    private static final String FP = "a".repeat(64);

    @Test
    void parsesValidRecord() {
        assertEquals(Optional.of(FP), DnsPinResolver.parsePin("v=amp1;fp=" + FP));
    }

    @Test
    void parsesColonSeparatedUppercaseFingerprint() {
        StringBuilder pretty = new StringBuilder();
        for (int i = 0; i < 64; i += 2) {
            if (i > 0) pretty.append(':');
            pretty.append("AB");
        }
        assertEquals(Optional.of("ab".repeat(32)), DnsPinResolver.parsePin("v=amp1; fp=" + pretty));
    }

    @Test
    void ignoresExtraFields() {
        assertEquals(Optional.of(FP), DnsPinResolver.parsePin("v=amp1;foo=bar;fp=" + FP + ";baz=1"));
    }

    @Test
    void rejectsMissingOrWrongVersion() {
        assertTrue(DnsPinResolver.parsePin("fp=" + FP).isEmpty());
        assertTrue(DnsPinResolver.parsePin("v=amp2;fp=" + FP).isEmpty());
    }

    @Test
    void rejectsMalformedFingerprints() {
        assertTrue(DnsPinResolver.parsePin("v=amp1;fp=zz" + "a".repeat(62)).isEmpty());
        assertTrue(DnsPinResolver.parsePin("v=amp1;fp=" + "a".repeat(63)).isEmpty());
        assertTrue(DnsPinResolver.parsePin("v=amp1;fp=").isEmpty());
        assertTrue(DnsPinResolver.parsePin("v=amp1").isEmpty());
    }

    @Test
    void skipsIpLiterals() {
        assertTrue(DnsPinResolver.isIpLiteral("192.168.1.1"));
        assertTrue(DnsPinResolver.isIpLiteral("::1"));
        assertTrue(DnsPinResolver.isIpLiteral("2001:db8::1"));
        assertFalse(DnsPinResolver.isIpLiteral("example.com"));
        assertFalse(DnsPinResolver.isIpLiteral("mc.my-server.net"));
    }
}
