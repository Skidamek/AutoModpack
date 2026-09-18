package pl.skidam.automodpack_core.config;

import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.IOException;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.auth.IssuedSecret;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.PlatformCache;

/**
 * Minecraft 1.18 ships Gson 2.8.9, which cannot deserialize records. Every type ConfigTools (or a packet) fromJson's
 * must stay a class. In-memory records are fine as long as they are not on this graph.
 */
class DurableGsonTypesTest {
	@Test
	void gsonFacingTypesAreNotRecords() throws IOException {
		Set<Class<?>> visited = new HashSet<>();
		for (Class<?> type : List.of(UpdateTransaction.class, ClientConfigJsons.ClientConfigFieldsV3.class, ServerConfigJsons.ServerConfigFieldsV3.class, AuthJsons.SecretsFields.class,
				IssuedSecret.class, Secrets.Secret.class, ConnectionJsons.ConnectionRecordFields.class, ConnectionJsons.KnownHostsFields.class, ConnectionJsons.KnownHostsBootstrapFields.class,
				SelectionJsons.ClientSelectionStoreFields.class, GenerationJsons.HeadDocumentFields.class, GenerationJsons.JournalEntryFields.class, ModpackJsons.CompleteModpackContentFields.class,
				ModpackJsons.ModpackContentFields.class, StorageJsons.ObjectOwnershipFields.class, StorageJsons.SelfUpdateFields.class, ClientStorageJsons.ClientGeneratedCopiesFields.class,
				ClientStorageJsons.ClientGenerationStateFields.class, ClientStorageJsons.SnapshotFields.class, ClientStorageJsons.InstanceTreeFields.class, ClientStorageJsons.ClientOverlayFields.class,
				ClientStorageJsons.ClientCompactionReceiptFields.class,
				ClientStorageJsons.OfflineRepairJournalFields.class, FileCache.CachedFile.class,
				PlatformCache.Record.class))
			assertNoRecords(type, visited);
	}

	private static void assertNoRecords(Class<?> type, Set<Class<?>> visited) throws IOException {
		if (type.isPrimitive() || type.isEnum() || type.isArray() || type.getName().startsWith("java.")) return;
		assertFalse(type.isRecord(), "Gson-facing types must not be records: " + type.getName());
		if (!visited.add(type)) return;
		for (Field field : type.getDeclaredFields()) {
			int modifiers = field.getModifiers();
			if (Modifier.isStatic(modifiers) || Modifier.isTransient(modifiers)) continue;
			Class<?> fieldType = field.getType();
			if (List.class.isAssignableFrom(fieldType) || Set.class.isAssignableFrom(fieldType) || Map.class.isAssignableFrom(fieldType)) {
				Type generic = field.getGenericType();
				if (generic instanceof ParameterizedType parameterized)
					for (Type argument : parameterized.getActualTypeArguments())
						if (argument instanceof Class<?> argumentType) assertNoRecords(argumentType, visited);
			} else {
				assertNoRecords(fieldType, visited);
			}
		}
	}
}
