package pl.skidam.automodpack_core.modpack.candidate;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class StableSourceSnapshotterTest {
	@Test
	void reservedWindowsNamesMatchBeforeAnyExtension() {
		assertTrue(StableSourceSnapshotter.isReservedWindowsName("CON"));
		assertTrue(StableSourceSnapshotter.isReservedWindowsName("con"));
		assertTrue(StableSourceSnapshotter.isReservedWindowsName("con.txt"));
		assertTrue(StableSourceSnapshotter.isReservedWindowsName("AUX.tar.gz"));
		assertTrue(StableSourceSnapshotter.isReservedWindowsName("Nul"));
		assertTrue(StableSourceSnapshotter.isReservedWindowsName("lpt9"));
		assertFalse(StableSourceSnapshotter.isReservedWindowsName("com10"));
		assertFalse(StableSourceSnapshotter.isReservedWindowsName("connection"));
		assertFalse(StableSourceSnapshotter.isReservedWindowsName("auxiliary.tar.gz"));
		assertFalse(StableSourceSnapshotter.isReservedWindowsName("normal.txt"));
	}
}
