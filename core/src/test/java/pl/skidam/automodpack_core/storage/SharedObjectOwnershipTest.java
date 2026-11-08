package pl.skidam.automodpack_core.storage;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.storage.DataRootResolver.Location;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The ownership receipts are what stops one instance deleting bytes another instance still references, so every
 * property here is a deletion-safety property: what a collection decision can see, it keeps.
 */
class SharedObjectOwnershipTest {

	@Test
	void aDecisionSeesTheObjectsAnotherInstanceStillReferences(@TempDir Path temp) throws Exception {
		Location first = location(temp, "first");
		Location second = location(temp, "second");
		String shared = hash("shared");

		SharedObjectOwnership.publish(first, "client", Set.of(shared));

		assertTrue(snapshotSeenBy(second).contains(shared), "a collection decision that cannot see another instance's reference deletes bytes that instance still needs");
	}

	@Test
	void aDecisionSeesTheReferencesItJustPublished(@TempDir Path temp) throws Exception {
		Location owner = location(temp, "owner");
		String fresh = hash("fresh");
		Set<String> seen = new HashSet<>();

		SharedObjectOwnership.withGlobalReferences(owner, "client", Set.of(fresh), snapshot -> {
			seen.addAll(snapshot);
			return null;
		});

		assertTrue(seen.contains(fresh), "the owner's receipt is written before the decision reads the snapshot, or it would collect the bytes it just claimed");
	}

	@Test
	void publishingReplacesTheOwnersPreviousReferences(@TempDir Path temp) throws Exception {
		Location owner = location(temp, "owner");
		Location other = location(temp, "other");
		String dropped = hash("dropped");
		String kept = hash("kept");

		SharedObjectOwnership.publish(owner, "client", Set.of(dropped));
		SharedObjectOwnership.publish(owner, "client", Set.of(kept));

		Set<String> snapshot = snapshotSeenBy(other);
		assertTrue(snapshot.contains(kept));
		assertFalse(snapshot.contains(dropped), "a receipt is a complete statement of what the owner holds, not a growing log");
	}

	private static Set<String> snapshotSeenBy(Location location) throws Exception {
		Set<String> seen = new HashSet<>();
		SharedObjectOwnership.withGlobalReferences(location, "client", Set.of(), snapshot -> {
			seen.addAll(snapshot);
			return null;
		});
		return seen;
	}

	private static Location location(Path temp, String instance) {
		Path root = temp.resolve("data");
		Path instancePath = temp.resolve(instance);
		return new Location(root, HashUtils.sha1(instancePath.toString().getBytes(StandardCharsets.UTF_8)), instancePath);
	}

	private static String hash(String value) {
		return HashUtils.sha1(value.getBytes(StandardCharsets.UTF_8));
	}
}
