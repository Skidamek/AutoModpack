package pl.skidam.automodpack_core.protocol;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

import org.junit.jupiter.api.Test;

class DerTest {

	@Test
	void oidWritesTheDottedFormTheStandardsName() {
		assertArrayEquals(new byte[]{0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x0B}, Der.oid("1.2.840.113549.1.1.11"),
				"id-sha256WithRSAEncryption");
		assertArrayEquals(new byte[]{0x06, 0x08, 0x2A, (byte) 0x86, 0x48, (byte) 0xCE, 0x3D, 0x04, 0x03, 0x02}, Der.oid("1.2.840.10045.4.3.2"), "ecdsa-with-SHA256");
		// A second arc past 127 spans more than one base-128 octet, which the first arc's join does not hide.
		assertArrayEquals(new byte[]{0x06, 0x03, (byte) 0x82, 0x18, 0x03}, Der.oid("2.200.3"));
	}
}
