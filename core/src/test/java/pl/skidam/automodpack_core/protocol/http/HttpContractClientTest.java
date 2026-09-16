package pl.skidam.automodpack_core.protocol.http;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.protocol.http.HttpContractClient.DocumentFetchDecision;
import pl.skidam.automodpack_core.protocol.http.HttpContractClient.ObjectWriteMode;

/** The request and decision vocabulary of the URL-contract client, exercised without any socket. */
class HttpContractClientTest {
	private static final String SHA = "0123456789abcdef0123456789abcdef01234567";

	@Test
	void documentPathsAndEtagsFollowTheContract() {
		assertEquals("/head", HttpContractClient.documentPath("head"));
		assertEquals("/journal", HttpContractClient.documentPath("journal"));
		assertEquals("/objects/" + SHA, HttpContractClient.objectPath(SHA));
		assertEquals("\"" + SHA + "\"", HttpContractClient.quoteEtag(SHA));
		assertNull(HttpContractClient.rangeHeaderValue(0));
		assertNull(HttpContractClient.rangeHeaderValue(-1));
		assertEquals("bytes=1024-", HttpContractClient.rangeHeaderValue(1024));
	}

	@Test
	void documentVerdictsTreatTheBodyHashAsGroundTruth() {
		assertEquals(DocumentFetchDecision.UNCHANGED_FROM_LOCAL, HttpContractClient.decideDocument(304, SHA, null));
		// A 304 without a sent expectation is contract garbage, not an answer.
		assertEquals(DocumentFetchDecision.FAILED, HttpContractClient.decideDocument(304, null, null));
		// The stateless-host path: 200 with the full body that hashes to the expectation still reads as unchanged.
		assertEquals(DocumentFetchDecision.UNCHANGED_FROM_BODY, HttpContractClient.decideDocument(200, SHA, SHA));
		assertEquals(DocumentFetchDecision.FETCHED, HttpContractClient.decideDocument(200, SHA, "ffffffffff0123456789abcdef0123456789abcdef"));
		assertEquals(DocumentFetchDecision.FETCHED, HttpContractClient.decideDocument(200, null, SHA));
		assertEquals(DocumentFetchDecision.FAILED, HttpContractClient.decideDocument(404, SHA, null));
		assertEquals(DocumentFetchDecision.FAILED, HttpContractClient.decideDocument(302, null, null));
	}

	@Test
	void objectVerdictsSplitAppendReplaceAndStaleRange() {
		assertEquals(ObjectWriteMode.APPEND, HttpContractClient.decideObject(206, true));
		assertEquals(ObjectWriteMode.REPLACE, HttpContractClient.decideObject(200, true));
		assertEquals(ObjectWriteMode.STALE_RANGE, HttpContractClient.decideObject(416, true));
		assertEquals(ObjectWriteMode.FAILED, HttpContractClient.decideObject(404, true));
		assertEquals(ObjectWriteMode.FAILED, HttpContractClient.decideObject(500, true));
		// A 416 without a sent Range says nothing about a stored prefix; it is an ordinary failure like any other.
		assertEquals(ObjectWriteMode.FAILED, HttpContractClient.decideObject(416, false));
	}
}
