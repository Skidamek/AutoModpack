package pl.skidam.automodpack_core.auth.dnssec;

import org.junit.jupiter.api.Test;
import org.xbill.DNS.DClass;
import org.xbill.DNS.Flags;
import org.xbill.DNS.Message;
import org.xbill.DNS.Name;
import org.xbill.DNS.Rcode;
import org.xbill.DNS.Resolver;
import org.xbill.DNS.Section;
import org.xbill.DNS.TSIG;
import org.xbill.DNS.TXTRecord;
import pl.skidam.automodpack_core.config.Jsons;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DnsjavaDnssecEndpointVerifierTest {
    private static final String ENDPOINT_ID = "00112233445566778899aabbccddeeff00112233445566778899aabbccddeeff";
    private static final String OTHER_ENDPOINT_ID = "ffeeddccbbaa99887766554433221100ffeeddccbbaa99887766554433221100";

    @Test
    void parseTxtEndpointValueAcceptsCanonicalPrefix() {
        assertEquals(ENDPOINT_ID, DnsjavaDnssecEndpointVerifier.parseTxtEndpointValue(List.of(
            "endpoint=v1:" + ENDPOINT_ID
        )));
    }

    @Test
    void parseTxtEndpointValueIgnoresUnrelatedTxtValues() {
        String parsed = DnsjavaDnssecEndpointVerifier.parseTxtEndpointValue(List.of(
            "not-automodpack",
            "endpoint=v1:" + ENDPOINT_ID
        ));
        assertEquals(ENDPOINT_ID, parsed);
    }

    @Test
    void parseTxtEndpointValueRejectsConflictingOrMalformedAutomodpackValues() {
        assertEquals("", DnsjavaDnssecEndpointVerifier.parseTxtEndpointValue(List.of(
            "endpoint=v1:" + ENDPOINT_ID,
            "endpoint=v1:" + OTHER_ENDPOINT_ID
        )));
        assertEquals(null, DnsjavaDnssecEndpointVerifier.parseTxtEndpointValue(List.of(
            "amp-endpoint=v1:" + ENDPOINT_ID
        )));
        assertEquals("", DnsjavaDnssecEndpointVerifier.parseTxtEndpointValue(List.of(
            "endpoint=v1:not-hex"
        )));
    }

    @Test
    void normalizeHostnameDedupesCaseAndTrailingDot() {
        assertEquals("mods.example.com", DnsjavaDnssecEndpointVerifier.normalizeHostname("Mods.Example.com."));
    }

    @Test
    void ipLiteralDetectionRecognizesIpv4AndIpv6() {
        assertTrue(DnsjavaDnssecEndpointVerifier.isIpLiteral("198.51.100.10"));
        assertTrue(DnsjavaDnssecEndpointVerifier.isIpLiteral("[2001:db8::1]"));
        assertFalse(DnsjavaDnssecEndpointVerifier.isIpLiteral("mods.example.com"));
    }

    @Test
    void verifyMarksAuthenticatedMatchingTxtAsSecure() throws Exception {
        DnsjavaDnssecEndpointVerifier verifier = new DnsjavaDnssecEndpointVerifier(new FakeResolver(Map.of(
            "_automodpack.play.example.", txtResponse("_automodpack.play.example.", true, Rcode.NOERROR, "endpoint=v1:" + ENDPOINT_ID)
        )));

        DnssecVerificationResult result = verifier.verify(new DnssecVerificationRequest(
            new InetSocketAddress("play.example", 25565),
            null,
            ENDPOINT_ID
        ));

        Jsons.DnssecDomainRecord record = result.domainResults().get("play.example");
        assertEquals(Jsons.DnssecStatus.SECURE_MATCH, record.status);
        assertEquals(ENDPOINT_ID, record.endpointId);
    }

    @Test
    void verifyTreatsMissingAuthenticatedDataAsUnavailable() throws Exception {
        DnsjavaDnssecEndpointVerifier verifier = new DnsjavaDnssecEndpointVerifier(new FakeResolver(Map.of(
            "_automodpack.play.example.", txtResponse("_automodpack.play.example.", false, Rcode.NOERROR, "endpoint=v1:" + ENDPOINT_ID)
        )));

        DnssecVerificationResult result = verifier.verify(new DnssecVerificationRequest(
            new InetSocketAddress("play.example", 25565),
            null,
            ENDPOINT_ID
        ));

        Jsons.DnssecDomainRecord record = result.domainResults().get("play.example");
        assertEquals(Jsons.DnssecStatus.UNAVAILABLE, record.status);
    }

    @Test
    void verifyTreatsAuthenticatedConflictsAsMalformed() throws Exception {
        DnsjavaDnssecEndpointVerifier verifier = new DnsjavaDnssecEndpointVerifier(new FakeResolver(Map.of(
            "_automodpack.play.example.", txtResponse(
                "_automodpack.play.example.",
                true,
                Rcode.NOERROR,
                "endpoint=v1:" + ENDPOINT_ID,
                "endpoint=v1:" + OTHER_ENDPOINT_ID
            )
        )));

        DnssecVerificationResult result = verifier.verify(new DnssecVerificationRequest(
            new InetSocketAddress("play.example", 25565),
            null,
            ENDPOINT_ID
        ));

        Jsons.DnssecDomainRecord record = result.domainResults().get("play.example");
        assertEquals(Jsons.DnssecStatus.MALFORMED, record.status);
    }

    @Test
    void verifyFallsBackToSystemResolverWhenCloudflareIsUnavailable() throws Exception {
        DnsjavaDnssecEndpointVerifier verifier = new DnsjavaDnssecEndpointVerifier(
            new ThrowingResolver("Cloudflare blocked"),
            new FakeResolver(Map.of(
                "_automodpack.play.example.", txtResponse("_automodpack.play.example.", true, Rcode.NOERROR, "endpoint=v1:" + ENDPOINT_ID)
            ))
        );

        DnssecVerificationResult result = verifier.verify(new DnssecVerificationRequest(
            new InetSocketAddress("play.example", 25565),
            null,
            ENDPOINT_ID
        ));

        Jsons.DnssecDomainRecord record = result.domainResults().get("play.example");
        assertEquals(Jsons.DnssecStatus.SECURE_MATCH, record.status);
        assertTrue(record.reason.contains("Cloudflare DoH unavailable"));
        assertTrue(record.reason.contains("System resolver"));
    }

    @Test
    void verifyDoesNotFallbackWhenCloudflareReturnsUnauthenticatedAnswer() throws Exception {
        DnsjavaDnssecEndpointVerifier verifier = new DnsjavaDnssecEndpointVerifier(
            new FakeResolver(Map.of(
                "_automodpack.play.example.", txtResponse("_automodpack.play.example.", false, Rcode.NOERROR, "endpoint=v1:" + ENDPOINT_ID)
            )),
            new FakeResolver(Map.of(
                "_automodpack.play.example.", txtResponse("_automodpack.play.example.", true, Rcode.NOERROR, "endpoint=v1:" + ENDPOINT_ID)
            ))
        );

        DnssecVerificationResult result = verifier.verify(new DnssecVerificationRequest(
            new InetSocketAddress("play.example", 25565),
            null,
            ENDPOINT_ID
        ));

        Jsons.DnssecDomainRecord record = result.domainResults().get("play.example");
        assertEquals(Jsons.DnssecStatus.UNAVAILABLE, record.status);
        assertFalse(record.reason.contains("System resolver"));
    }

    private static Message txtResponse(String txtName, boolean authenticated, int rcode, String... values) throws Exception {
        Message response = new Message();
        response.getHeader().setRcode(rcode);
        if (authenticated) {
            response.getHeader().setFlag(Flags.AD);
        }
        if (rcode == Rcode.NOERROR) {
            Name name = Name.fromString(txtName);
            for (String value : values) {
                response.addRecord(new TXTRecord(name, DClass.IN, 300, value), Section.ANSWER);
            }
        }
        return response;
    }

    private static final class FakeResolver implements Resolver {
        private final Map<String, Message> responses = new LinkedHashMap<>();

        private FakeResolver(Map<String, Message> responses) {
            this.responses.putAll(responses);
        }

        @Override
        public void setPort(int port) {
        }

        @Override
        public void setTCP(boolean flag) {
        }

        @Override
        public void setIgnoreTruncation(boolean flag) {
        }

        @Override
        public void setEDNS(int version, int payloadSize, int flags, List options) {
        }

        @Override
        public void setTSIGKey(TSIG key) {
        }

        @Override
        public void setTimeout(Duration timeout) {
        }

        @Override
        public Message send(Message query) throws IOException {
            String name = query.getQuestion().getName().toString();
            Message response = responses.get(name);
            if (response == null) {
                throw new IOException("No fake response for " + name);
            }
            return response;
        }
    }

    private static final class ThrowingResolver implements Resolver {
        private final String message;

        private ThrowingResolver(String message) {
            this.message = message;
        }

        @Override
        public void setPort(int port) {
        }

        @Override
        public void setTCP(boolean flag) {
        }

        @Override
        public void setIgnoreTruncation(boolean flag) {
        }

        @Override
        public void setEDNS(int version, int payloadSize, int flags, List options) {
        }

        @Override
        public void setTSIGKey(TSIG key) {
        }

        @Override
        public void setTimeout(Duration timeout) {
        }

        @Override
        public Message send(Message query) throws IOException {
            throw new IOException(message);
        }
    }
}
