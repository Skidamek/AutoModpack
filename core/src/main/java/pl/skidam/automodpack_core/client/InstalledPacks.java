package pl.skidam.automodpack_core.client;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.MissingGenerationContentException;
import pl.skidam.automodpack_core.utils.AddressHelpers;

/**
 * The installed packs this client can see, and why it cannot read them when it cannot. A pack is listed when the
 * generation store says the client installed it and its newest generation still has documents here; a pack awaiting its
 * first sync is not broken storage, so it is skipped quietly while a real read failure is reported once with the list.
 */
public final class InstalledPacks {
	private static final Logger LOGGER = LogManager.getLogger(InstalledPacks.class);

	/** The vanilla server-list name for an origin, or blank; the one thing the catalogue needs from the game. */
	@FunctionalInterface
	public interface ServerNames {
		String nameOf(String origin);

		ServerNames NONE = origin -> "";
	}

	public record Pack(PackDocument record, String name, boolean active, boolean detached, String connectionOrigin, String connectionDetail, int fileCount, long fileBytes) {
		public boolean connectionAvailable() {
			return connectionOrigin != null;
		}

		public String modpackId() {
			return record.manifest().modpackId();
		}

		public int groupCount() {
			return record.manifest().groups().size();
		}
	}

	/** One installed pack this origin no longer serves, with its player-facing name or raw id. */
	public record Stale(String modpackId, String name) {}

	/** Every pack the client can see, with the first read failure that hid any of them. */
	public record Catalogue(List<Pack> packs, Throwable failure) {
		public Pack byId(String modpackId) {
			for (Pack pack : packs) if (pack.modpackId().equals(modpackId)) return pack;
			return null;
		}
	}

	private InstalledPacks() {}

	public static Catalogue read(ClientStorage storage, ServerNames serverNames) {
		Throwable[] failure = {null};
		ClientStorageJsons.ClientGenerationStateFields activeState;
		try {
			activeState = storage.readActiveState();
		} catch (IOException | RuntimeException e) {
			failure[0] = e;
			activeState = null;
		}
		try {
			ClientSelectionStore selections = new ClientSelectionStore(storage.selectionFile());
			ClientGenerationStore generations = new ClientGenerationStore(storage);
			List<Pack> pending = new ArrayList<>();
			for (String modpackId : generations.installedPackIds(activeState, selections)) {
				PackDocument record;
				try {
					record = generations.newestDocument(modpackId, activeState);
				} catch (MissingGenerationContentException e) {
					// The generation's own documents are not on this machine yet. That is what a pack awaiting its
					// first sync looks like, not broken storage, so the list keeps every pack it could read.
					LOGGER.info("Installed modpack {} has no stored documents for its newest generation yet, so it is not listed until the next sync: {}", modpackId, e.getMessage());
					continue;
				} catch (IOException | RuntimeException e) {
					failure[0] = e;
					continue;
				}
				pending.add(pack(record, modpackId.equals(activeId(activeState)), detached(activeState, modpackId), connection(storage, modpackId, failure), serverNames));
			}
			pending.sort(Comparator.comparing(Pack::name, String.CASE_INSENSITIVE_ORDER));
			return new Catalogue(List.copyOf(pending), failure[0]);
		} catch (IOException | RuntimeException e) {
			return new Catalogue(List.of(), e);
		}
	}

	public static Pack read(ClientStorage storage, String modpackId, ServerNames serverNames) {
		return read(storage, serverNames).byId(modpackId);
	}

	/** The light name lookup: one document read, no catalogue projection. For labels; never for pack actions. */
	public static String nameOf(ClientStorage storage, String modpackId, ServerNames serverNames) {
		try {
			PackDocument record = new ClientGenerationStore(storage).newestDocument(modpackId);
			if (record != null) return displayName(record, originOf(storage, modpackId), serverNames);
		} catch (IOException | RuntimeException ignored) {
			// The name is cosmetic; the raw id is the honest fallback, not a discovery failure.
		}
		return modpackId;
	}

	/** Installed packs whose saved origin now serves the active pack instead: the leftovers of a republished server. */
	public static List<Stale> stale(ClientStorage storage, ServerNames serverNames) throws IOException {
		String activeId = activeId(storage.readActiveState());
		if (activeId.isBlank()) return List.of();
		List<Stale> stale = new ArrayList<>();
		for (String modpackId : ConnectionStore.staleSameOriginPackIds(storage, activeId)) stale.add(new Stale(modpackId, nameOf(storage, modpackId, serverNames)));
		return List.copyOf(stale);
	}

	/** The origin to show for a pack: the saved origin without the default port, or "" when there is none. */
	public static String displayOrigin(String origin) {
		if (origin == null || origin.isBlank()) return "";
		return origin.endsWith(":25565") ? origin.substring(0, origin.length() - 6) : origin;
	}

	private static String originOf(ClientStorage storage, String modpackId) {
		try {
			ConnectionJsons.ConnectionRecordFields fields = ConnectionStore.read(storage, modpackId);
			return fields.connection == null ? null : displayOrigin(AddressHelpers.formatAddress(fields.connection.origin));
		} catch (IOException | RuntimeException ignored) {
			return null;
		}
	}

	private static ConnectionJsons.ConnectionInfo connection(ClientStorage storage, String modpackId, Throwable[] failure) {
		try {
			ConnectionJsons.ConnectionRecordFields fields = ConnectionStore.read(storage, modpackId);
			return fields.connection != null && fields.connection.isComplete() ? fields.connection : null;
		} catch (IOException | RuntimeException e) {
			failure[0] = e;
			return null;
		}
	}

	private static Pack pack(PackDocument record, boolean active, boolean detached, ConnectionJsons.ConnectionInfo connection, ServerNames serverNames) {
		int fileCount = 0;
		long byteCount = 0;
		for (GroupManifest.Group group : record.manifest().groups().values())
			for (GroupManifest.GroupFile file : group.files().values()) {
				fileCount++;
				byteCount += file.size();
			}
		String origin = connection == null ? null : displayOrigin(AddressHelpers.formatAddress(connection.origin));
		return new Pack(record, displayName(record, origin, serverNames), active, detached, origin, connectionDetail(connection), fileCount, byteCount);
	}

	private static String displayName(PackDocument record, String connectionOrigin, ServerNames serverNames) {
		String name = record.manifest().modpackName();
		if (!name.isBlank()) return name;
		if (connectionOrigin != null) {
			String server = serverNames.nameOf(connectionOrigin);
			if (server != null && !server.isBlank()) return server;
			return connectionOrigin;
		}
		return record.manifest().modpackId();
	}

	private static String connectionDetail(ConnectionJsons.ConnectionInfo connection) {
		return connection == null ? null : AddressHelpers.formatAddress(connection.endpoint) + " · " + connection.connectionMode.name().toLowerCase(Locale.ROOT).replace('_', ' ');
	}

	private static boolean detached(ClientStorageJsons.ClientGenerationStateFields activeState, String modpackId) {
		return activeState != null && activeState.modpackId.equals(modpackId) && activeState.detached;
	}

	private static String activeId(ClientStorageJsons.ClientGenerationStateFields state) {
		return state == null || state.modpackId == null ? "" : state.modpackId;
	}
}
