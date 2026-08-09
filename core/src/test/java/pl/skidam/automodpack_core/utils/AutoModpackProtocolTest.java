package pl.skidam.automodpack_core.utils;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class AutoModpackProtocolTest {

    @Test
    void acceptsExactVersions() {
        assertTrue(AutoModpackProtocol.acceptsClient("4.1.0", "4.1.0"));
    }

    @Test
    void acceptsLegacyStableFourZeroVersions() {
        assertTrue(AutoModpackProtocol.acceptsClient("4.0.5", "4.0.6"));
        assertTrue(AutoModpackProtocol.acceptsClient("4.0.6", "4.0.5"));
    }

    @Test
    void rejectsLegacyVersionsOutsideFourZeroFamily() {
        assertFalse(AutoModpackProtocol.acceptsClient("4.0.5", "4.1.0"));
        assertFalse(AutoModpackProtocol.acceptsClient("4.0.5", "3.9.9"));
        assertFalse(AutoModpackProtocol.acceptsClient("4.0.5-beta1", "4.0.6"));
    }

    @Test
    void aliasesStableFourZeroVersions() {
        assertEquals(
                "4.0.5",
                AutoModpackProtocol.getHandshakeVersion("4.0.5", "4.0.6")
        );
        assertEquals(
                "4.0.6",
                AutoModpackProtocol.getHandshakeVersion("4.0.6", "4.0.6")
        );
        assertEquals(
                "4.0.6",
                AutoModpackProtocol.getHandshakeVersion("4.1.0", "4.0.6")
        );
    }
}
