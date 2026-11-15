package pl.skidam.automodpack_core.utils;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.DosFileAttributeView;
import java.util.Set;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FileTreesTest {
	@TempDir
	Path tempDir;

	@Test
	void linkedDirectoryIsRefusedOnFirstResolution() throws IOException {
		Path root = Files.createDirectories(tempDir.resolve("managed"));
		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Files.writeString(outside.resolve("object"), "outside");
		Files.createSymbolicLink(root.resolve("config"), outside);

		IOException refused = assertThrows(IOException.class, () -> FileTrees.Confinement.under(root).resolve("config/object", "Transaction target"));

		assertEquals("Transaction target contains a symbolic link: " + root.resolve("config"), refused.getMessage());
	}

	@Test
	void leafSymlinkIsRefusedAfterItsDirectoryWasProven() throws IOException {
		Path root = Files.createDirectories(tempDir.resolve("managed"));
		Path config = Files.createDirectories(root.resolve("config"));
		Files.writeString(config.resolve("first.toml"), "first");
		FileTrees.Confinement pass = FileTrees.Confinement.under(root);
		assertEquals(config.resolve("first.toml"), pass.resolve("config/first.toml", "Transaction target"));

		Path elsewhere = Files.writeString(tempDir.resolve("elsewhere.toml"), "elsewhere");
		Files.createSymbolicLink(config.resolve("second.toml"), elsewhere);

		IOException refused = assertThrows(IOException.class, () -> pass.resolve("config/second.toml", "Transaction target"));

		assertEquals("Transaction target contains a symbolic link: " + config.resolve("second.toml"), refused.getMessage());
	}

	@Test
	void aFreshPassSeesADirectorySwappedAfterThePreviousOne() throws IOException {
		Path root = Files.createDirectories(tempDir.resolve("managed"));
		Path config = Files.createDirectories(root.resolve("config"));
		Files.writeString(config.resolve("first.toml"), "first");
		assertEquals(config.resolve("first.toml"), FileTrees.Confinement.under(root).resolve("config/first.toml", "Transaction target"));

		Path moved = tempDir.resolve("moved-config");
		Files.move(config, moved);
		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Files.writeString(outside.resolve("first.toml"), "outside");
		Files.createSymbolicLink(config, outside);

		IOException refused = assertThrows(IOException.class, () -> FileTrees.Confinement.under(root).resolve("config/second.toml", "Transaction target"));

		assertEquals("Transaction target contains a symbolic link: " + config, refused.getMessage());
	}

	@Test
	void invalidationRefusesADirectorySwappedAfterItsParentWasProven() throws IOException {
		Path root = Files.createDirectories(tempDir.resolve("managed"));
		Path config = Files.createDirectories(root.resolve("config"));
		Path removed = Files.writeString(config.resolve("removed.toml"), "removed");
		FileTrees.Confinement execution = FileTrees.Confinement.under(root);
		assertEquals(config.resolve("next.toml"), execution.resolve("config/next.toml", "Operation target"));

		// The removal this execution just landed could have pruned config/, and a later writer can recreate that name as
		// a link. A proof that survives the removal would resolve next.toml straight through the link.
		Path moved = tempDir.resolve("moved-config");
		Files.move(config, moved);
		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Files.createSymbolicLink(config, outside);
		execution.invalidate(removed);

		IOException refused = assertThrows(IOException.class, () -> execution.resolve("config/next.toml", "Operation target"));

		assertEquals("Operation target contains a symbolic link: " + config, refused.getMessage());
	}

	@Test
	void invalidationOfAnUnrelatedSiblingStillProvesTheRemovedDirectoryAgain() throws IOException {
		Path root = Files.createDirectories(tempDir.resolve("managed"));
		Path config = Files.createDirectories(root.resolve("config"));
		Path mods = Files.createDirectories(root.resolve("mods"));
		Files.writeString(config.resolve("removed.toml"), "removed");
		FileTrees.Confinement execution = FileTrees.Confinement.under(root);
		assertEquals(mods.resolve("kept.jar"), execution.resolve("mods/kept.jar", "Operation target"));
		assertEquals(config.resolve("next.toml"), execution.resolve("config/next.toml", "Operation target"));

		// A surviving sibling directory keeps its proof: invalidating one subtree must not throw the pass away.
		execution.invalidate(config.resolve("removed.toml"));
		Files.delete(config.resolve("removed.toml"));
		Files.delete(config);
		Files.createSymbolicLink(config, mods);

		assertEquals(mods.resolve("kept.jar"), execution.resolve("mods/kept.jar", "Operation target"));
		assertThrows(IOException.class, () -> execution.resolve("config/next.toml", "Operation target"));
	}

	@Test
	void aRelativePathCannotEscapeItsRoot() throws IOException {
		Path root = Files.createDirectories(tempDir.resolve("managed"));

		IOException refused = assertThrows(IOException.class, () -> FileTrees.Confinement.under(root).resolve("../outside", "Transaction target"));

		assertEquals("Transaction target escapes its root: " + root.resolve("../outside").normalize(), refused.getMessage());
	}

	@Test
	void deletesReadOnlyFileFromManagedTree() throws IOException {
		Path root = Files.createDirectories(tempDir.resolve("managed"));
		Path file = Files.writeString(root.resolve("object"), "immutable");
		DosFileAttributeView dos = Files.getFileAttributeView(file, DosFileAttributeView.class);
		Assumptions.assumeTrue(dos != null, "requires a DOS read-only attribute view");
		dos.setReadOnly(true);

		try {
			FileTrees.delete(root);
		} finally {
			if (Files.exists(file)) {
				dos.setReadOnly(false);
				Files.deleteIfExists(file);
			}
			Files.deleteIfExists(root);
		}

		assertFalse(Files.exists(root));
	}

	@Test
	void aPlayerConfiguredRootMayRedirectButItsDescendantsMayNot() throws IOException {
		Path real = Files.createDirectories(tempDir.resolve("real-game"));
		Path root = tempDir.resolve("linked-game");
		try {
			Files.createSymbolicLink(root, real);
		} catch (IOException | UnsupportedOperationException e) {
			Assumptions.assumeTrue(false, "symbolic links are not available here: " + e.getMessage());
		}

		FileTrees.Confinement trusted = FileTrees.Confinement.under(root, FileTrees.RootTrust.PLAYER_CONFIGURED);
		assertEquals(root.resolve("mods/alpha.jar"), trusted.resolve("mods/alpha.jar", "Transaction target"));
		assertThrows(IOException.class, () -> FileTrees.Confinement.under(root).resolve("mods/alpha.jar", "Transaction target"));

		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Files.writeString(outside.resolve("secret.txt"), "outside");
		Files.createSymbolicLink(real.resolve("escape"), outside);
		IOException refused = assertThrows(IOException.class, () -> trusted.resolve("escape/secret.txt", "Transaction target"));
		assertEquals("Transaction target contains a symbolic link: " + root.resolve("escape"), refused.getMessage());
	}

	@Test
	void aTrustedPlayerDirectoryMayRedirectButItsDescendantsMayNot() throws IOException {
		Path root = Files.createDirectories(tempDir.resolve("game"));
		Path relocatedMods = Files.createDirectories(tempDir.resolve("relocated-mods"));
		try {
			Files.createSymbolicLink(root.resolve("mods"), relocatedMods);
		} catch (IOException | UnsupportedOperationException e) {
			Assumptions.assumeTrue(false, "symbolic links are not available here: " + e.getMessage());
		}

		FileTrees.Confinement trusted = FileTrees.Confinement.under(root, FileTrees.RootTrust.PLAYER_CONFIGURED, Set.of(root.resolve("mods")));
		assertEquals(root.resolve("mods/alpha.jar"), trusted.resolve("mods/alpha.jar", "Pack path"));
		assertThrows(IOException.class, () -> FileTrees.Confinement.under(root, FileTrees.RootTrust.PLAYER_CONFIGURED).resolve("mods/alpha.jar", "Pack path"));

		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Files.writeString(outside.resolve("nested.jar"), "outside");
		Files.createSymbolicLink(relocatedMods.resolve("nested"), outside);
		IOException refused = assertThrows(IOException.class, () -> trusted.resolve("mods/nested/nested.jar", "Pack path"));
		assertEquals("Pack path contains a symbolic link: " + root.resolve("mods/nested"), refused.getMessage());
	}

	@Test
	void junctionPlayerRootIsTrustedButInnerJunctionIsRefused() throws Exception {
		Assumptions.assumeTrue(PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS, "junctions are a Windows reparse-point feature");
		Path real = Files.createDirectories(tempDir.resolve("real-game"));
		Path root = tempDir.resolve("linked-game");
		assertEquals(0, mklinkJunction(root, real), "mklink /J should succeed without elevation");
		FileTrees.requirePlayerDirectory(root, "Game directory");

		FileTrees.Confinement trusted = FileTrees.Confinement.under(root, FileTrees.RootTrust.PLAYER_CONFIGURED);
		assertEquals(root.resolve("mods/alpha.jar"), trusted.resolve("mods/alpha.jar", "Transaction target"));
		assertThrows(IOException.class, () -> FileTrees.Confinement.under(root).resolve("mods/alpha.jar", "Transaction target"));

		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Files.writeString(outside.resolve("secret.txt"), "outside");
		Path escape = real.resolve("escape");
		assertEquals(0, mklinkJunction(escape, outside), "mklink /J should succeed without elevation");
		IOException refused = assertThrows(IOException.class, () -> trusted.resolve("escape/secret.txt", "Transaction target"));
		assertEquals("Transaction target contains a symbolic link: " + root.resolve("escape"), refused.getMessage());
	}

	@Test
	void junctionInsideManagedRootIsRefused() throws Exception {
		Assumptions.assumeTrue(PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS, "junctions are a Windows reparse-point feature");
		Path root = Files.createDirectories(tempDir.resolve("managed"));
		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Files.writeString(outside.resolve("secret.txt"), "outside");
		Path junction = root.resolve("linked");
		assertEquals(0, mklinkJunction(junction, outside), "mklink /J should succeed without elevation");
		assertFalse(Files.isSymbolicLink(junction), "the defect this guards: Java does not call a junction a symbolic link");
		assertTrue(Files.isDirectory(junction, LinkOption.NOFOLLOW_LINKS));

		IOException refused = assertThrows(IOException.class, () -> FileTrees.Confinement.under(root).resolve("linked/secret.txt", "Transaction target"));

		assertEquals("Transaction target contains a symbolic link: " + junction, refused.getMessage());
	}

	@Test
	void deletingAManagedTreeRemovesAJunctionWithoutFollowingIt() throws Exception {
		Assumptions.assumeTrue(PlatformUtils.operatingSystem() == PlatformUtils.OperatingSystem.WINDOWS, "junctions are a Windows reparse-point feature");
		Path root = Files.createDirectories(tempDir.resolve("managed"));
		Path outside = Files.createDirectories(tempDir.resolve("outside"));
		Path kept = Files.writeString(outside.resolve("kept.txt"), "must survive");
		Path junction = root.resolve("linked");
		assertEquals(0, mklinkJunction(junction, outside), "mklink /J should succeed without elevation");

		FileTrees.delete(root);

		assertFalse(Files.exists(root));
		assertTrue(Files.exists(kept), "deleting the managed tree must remove the junction entry, not its target's contents");
	}

	private static int mklinkJunction(Path link, Path target) throws Exception {
		Process process = new ProcessBuilder("cmd", "/c", "mklink", "/J", link.toString(), target.toString()).redirectErrorStream(true).start();
		process.getInputStream().readAllBytes();
		return process.waitFor();
	}
}
