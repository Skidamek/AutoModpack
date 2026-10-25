package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PathFoldTest {
	@TempDir
	Path directory;

	@Test
	void anInsensitiveFoldJoinsCaseAndNormalizationSpellings() {
		assertEquals(PathFold.key("config/Caf\u00e9.toml"), PathFold.key("CONFIG/cafe\u0301.TOML"));
	}

	@Test
	void anInsensitiveFoldJoinsNamesNtfsTreatsAsOneFile() {
		// NTFS compares names by its upcase table: final and medial sigma, and dotless i, upcase to the same letter.
		assertEquals(PathFold.key("config/\u03c3.txt"), PathFold.key("config/\u03c2.txt"));
		assertEquals(PathFold.key("config/\u0131.txt"), PathFold.key("config/i.txt"));
	}

	/** macOS folds names fully, expanding letters such as sharp s and the fi ligature, so packs must not ship both spellings. */
	@Test
	void anInsensitiveFoldJoinsNamesMacOsExpands() {
		assertEquals(PathFold.key("config/stra\u00dfe.txt"), PathFold.key("config/strasse.txt"));
		assertEquals(PathFold.key("config/\ufb01le.txt"), PathFold.key("config/file.txt"));
	}

	/** String.toLowerCase applies the final-sigma rule, so one name would fold two ways by what follows the sigma. */
	@Test
	void anInsensitiveFoldIsContextFree() {
		assertEquals(PathFold.key("config/\u039F\u0394\u039F\u03A3/a.toml"), PathFold.key("config/\u03BF\u03B4\u03BF\u03C3/a.toml"));
	}

	@Test
	void onlyTheFilesystemJoinsTwoSpellings() throws Exception {
		Files.writeString(directory.resolve("named"), "a");
		boolean folds = Files.exists(directory.resolve("NAMED"));

		assertTrue(PathFold.sameFile(directory, "named", "named"));
		assertEquals(folds, PathFold.sameFile(directory, "named", "NAMED"));
		assertFalse(PathFold.sameFile(directory, "named", "missing"));
	}

	/** Two entries of one hard-linked object are two names, as a pack's equal-byte projection rows are. */
	@Test
	void twoEntriesOfOneObjectStayTwoNames() throws Exception {
		Files.writeString(directory.resolve("Dup"), "x");
		Assumptions.assumeFalse(Files.exists(directory.resolve("dup")), "needs a case-sensitive filesystem");
		Files.createLink(directory.resolve("dup"), directory.resolve("Dup"));

		assertTrue(Files.isSameFile(directory.resolve("Dup"), directory.resolve("dup")));
		assertFalse(PathFold.sameFile(directory, "Dup", "dup"));
	}

	/** Casefolded ext4 (full Unicode case folding) names capital sharp s, small sharp s and "ss" as one file. */
	@Test
	void anInsensitiveFoldJoinsCapitalAndSmallSharpS() {
		assertEquals(PathFold.key("config/stra\u00DFe.toml"), PathFold.key("config/STRA\u1E9EE.toml"));
	}
}
