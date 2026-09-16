package pl.skidam.automodpack_core.modpack.candidate;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.utils.OsPaths;

class StableSourceSnapshotterTest {
	@Test
	void reservedWindowsNamesMatchBeforeAnyExtension() {
		assertTrue(OsPaths.isReservedWindowsDeviceName("CON"));
		assertTrue(OsPaths.isReservedWindowsDeviceName("con"));
		assertTrue(OsPaths.isReservedWindowsDeviceName("con.txt"));
		assertTrue(OsPaths.isReservedWindowsDeviceName("AUX.tar.gz"));
		assertTrue(OsPaths.isReservedWindowsDeviceName("Nul"));
		assertTrue(OsPaths.isReservedWindowsDeviceName("lpt0"));
		assertTrue(OsPaths.isReservedWindowsDeviceName("lpt9"));
		assertTrue(OsPaths.isReservedWindowsDeviceName("com0.tar.gz"));
		assertFalse(OsPaths.isReservedWindowsDeviceName("com10"));
		assertFalse(OsPaths.isReservedWindowsDeviceName("connection"));
		assertFalse(OsPaths.isReservedWindowsDeviceName("auxiliary.tar.gz"));
		assertFalse(OsPaths.isReservedWindowsDeviceName("normal.txt"));
	}
}
