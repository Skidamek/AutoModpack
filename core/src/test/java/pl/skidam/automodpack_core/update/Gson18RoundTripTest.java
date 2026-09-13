package pl.skidam.automodpack_core.update;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.OperationType;
import pl.skidam.automodpack_core.update.UpdatePlan.Root;

/**
 * Minecraft 1.18 ships Gson 2.8.9, which we do not shade. Durable documents must round-trip through that copy, and
 * records must not.
 */
class Gson18RoundTripTest {
	private static final String OBJECT_HASH = "1111111111111111111111111111111111111111";

	@TempDir
	Path temporaryDirectory;

	@Test
	void aRealTransactionRoundTripsThroughMinecraft18Gson() throws Exception {
		OwnershipLedger ledger = OwnershipLedger.empty("packaa1");
		ChangeSet consequences = ChangeSet.of(new ChangeSet.Change("mods/a.jar", ChangeSet.Kind.ADDED,
				List.of(new ChangeSet.Occurrence("PROJECTION", "mods/a.jar", 1, null, null, OBJECT_HASH, "mod", List.of(), List.of()))));
		UpdatePlan plan = new UpdatePlan("packaa1", new PackTarget("packaa1", "a".repeat(40), "b".repeat(40), ledger.digest()),
				List.of(new Operation(Root.PROJECTION, "mods/a.jar", OperationType.INSTALL_OBJECT, OBJECT_HASH, 1, null)),
				List.of(new UpdatePlan.ProjectedFile(Root.PROJECTION, "mods/a.jar", true, OBJECT_HASH, 1)), new ClientConfigJsons.ClientConfigFieldsV3(),
				Set.of(UpdatePlan.RestartReason.SELECTED_MODPACK), List.of(), List.of(), List.of(),
				List.of(new UpdatePlan.NestedCopy("mods/nested.jar", OBJECT_HASH, 2, Set.of("sodium"))), consequences);
		UpdateTransaction transaction = UpdateTransaction.createRemoval(plan, ClientPlatform.LINUX, null, ledger.toFields(), "", new ClientConfigJsons.ClientConfigFieldsV3());
		String json = ConfigTools.GSON.toJson(transaction);

		UpdateTransaction roundTripped = (UpdateTransaction) gson18FromJson(json, UpdateTransaction.class);

		assertNotNull(roundTripped);
		assertEquals(UpdateTransaction.Purpose.MODPACK_REMOVAL, roundTripped.purpose);
		assertEquals("packaa1", roundTripped.plan().modpackId());
		assertEquals(OBJECT_HASH, roundTripped.plan().operations().get(0).expectedObjectHash());
		assertEquals(ChangeSet.Kind.ADDED, roundTripped.plan().consequences().changes().get(0).kind());
	}

	@Test
	void minecraft18GsonCannotReadARecord() {
		record Probe(String value) {}
		InvocationTargetException failure = assertThrows(InvocationTargetException.class, () -> gson18FromJson("{\"value\":\"x\"}", Probe.class));
		assertNotNull(failure.getCause());
		assertTrue(failure.getCause().toString().contains("IllegalAccessException") || failure.getCause() instanceof RuntimeException, failure.getCause().toString());
	}

	private static Object gson18FromJson(String json, Class<?> type) throws Exception {
		String jar = System.getProperty("gson18.jar");
		if (jar == null || jar.isBlank()) throw new IllegalStateException("gson18.jar was not set; the test task must pass the Minecraft 1.18 Gson jar");
		try (URLClassLoader loader = new URLClassLoader(new URL[]{Path.of(jar).toUri().toURL()}, ClassLoader.getPlatformClassLoader())) {
			Class<?> gsonClass = loader.loadClass("com.google.gson.Gson");
			Object gson = gsonClass.getConstructor().newInstance();
			return gsonClass.getMethod("fromJson", String.class, Class.class).invoke(gson, json, type);
		}
	}
}
