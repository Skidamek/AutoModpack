package pl.skidam.automodpack_core.auth;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Objects;
import java.util.function.Consumer;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.FileLocks;
import pl.skidam.automodpack_core.utils.FileTrees;

/** Shared per-user route and client-secret state keyed by modpack identity. */
public final class ConnectionStore {
	private ConnectionStore() {}

	public static ConnectionJsons.ConnectionRecordFields read(ClientStorage storage, String modpackId) throws IOException {
		Path file = file(storage, modpackId);
		return FileLocks.withLock(storage.connectionLockFile(modpackId), () -> readUnlocked(file));
	}

	public static void update(ClientStorage storage, String modpackId, Consumer<ConnectionJsons.ConnectionRecordFields> update) throws IOException {
		Objects.requireNonNull(update, "update");
		Path file = file(storage, modpackId);
		FileLocks.withLock(storage.connectionLockFile(modpackId), () -> {
			ConnectionJsons.ConnectionRecordFields fields = readUnlocked(file);
			normalize(fields);
			update.accept(fields);
			Files.createDirectories(file.getParent());
			ConfigTools.writeAtomic(file, fields);
			return null;
		});
	}

	public static ConnectionJsons.ConnectionInfo getConnection(ClientStorage storage, String modpackId) throws IOException {
		return read(storage, modpackId).connection;
	}

	public static void saveConnection(ClientStorage storage, String modpackId, ConnectionJsons.ConnectionInfo connection) throws IOException {
		ModpackId.requireValid(modpackId);
		if (connection == null || !connection.isComplete()) throw new IllegalArgumentException("Connection origin or endpoint is missing");
		update(storage, modpackId, fields -> fields.connection = connection);
	}

	public static Secrets.Secret getClientSecret(ClientStorage storage, String modpackId, InetSocketAddress origin) throws IOException {
		if (origin == null) return null;
		return read(storage, modpackId).secrets.get(AddressHelpers.formatAddress(origin));
	}

	/** Persists the origin's client secret; a null secret removes the stored one, because absence is stored as absence. */
	public static void saveClientSecret(ClientStorage storage, String modpackId, InetSocketAddress origin, Secrets.Secret secret) throws IOException {
		if (origin == null) throw new IllegalArgumentException("Origin is required");
		if (secret != null && secret.secret().isBlank()) throw new IllegalArgumentException("Secret is blank");
		update(storage, modpackId, fields -> {
			String formattedOrigin = AddressHelpers.formatAddress(origin);
			if (secret == null) fields.secrets.remove(formattedOrigin);
			else fields.secrets.put(formattedOrigin, secret);
		});
	}

	/**
	 * Installed packs whose saved connection origin equals the active pack's, excluding the active pack itself: the
	 * leftovers of an origin that lost its modpack identity and republished under a new id. The active pack's own
	 * connection record names the origin; an unreadable or origin-less record only hides its pack from the offer.
	 */
	public static List<String> staleSameOriginPackIds(ClientStorage storage, String activeModpackId) throws IOException {
		ConnectionJsons.ConnectionInfo active = getConnection(storage, activeModpackId);
		if (active == null || active.origin == null) return List.of();
		String origin = AddressHelpers.formatAddress(active.origin);
		List<String> stale = new ArrayList<>();
		for (String modpackId : new ClientGenerationStore(storage).installedPackIds()) {
			if (modpackId.equals(ModpackId.requireValid(activeModpackId))) continue;
			try {
				ConnectionJsons.ConnectionInfo connection = getConnection(storage, modpackId);
				if (connection != null && connection.origin != null && AddressHelpers.formatAddress(connection.origin).equals(origin)) stale.add(modpackId);
			} catch (IOException | RuntimeException e) {
				LOGGER.debug("Cannot read the connection record of modpack {}; it is not offered for cleanup", modpackId, e);
			}
		}
		return List.copyOf(stale);
	}

	/**
	 * Whether any installed pack's stored connection origin equals this joining origin: a client already synced from
	 * this server skips the join offer for an optional modpack. An unreadable or origin-less record only hides its
	 * pack, exactly like the stale-cleanup offer above.
	 */
	public static boolean hasOriginConnection(ClientStorage storage, InetSocketAddress origin) throws IOException {
		if (origin == null) return false;
		String joiningOrigin = AddressHelpers.formatAddress(origin);
		for (String modpackId : new ClientGenerationStore(storage).installedPackIds()) {
			try {
				ConnectionJsons.ConnectionInfo connection = getConnection(storage, modpackId);
				if (connection != null && connection.origin != null && AddressHelpers.formatAddress(connection.origin).equals(joiningOrigin)) return true;
			} catch (IOException | RuntimeException e) {
				LOGGER.debug("Cannot read the connection record of modpack {}; it does not count as synced here", modpackId, e);
			}
		}
		return false;
	}

	private static Path file(ClientStorage storage, String modpackId) {
		return storage.connectionFile(ModpackId.requireValid(modpackId));
	}

	private static ConnectionJsons.ConnectionRecordFields readUnlocked(Path file) throws IOException {
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new ConnectionJsons.ConnectionRecordFields();
		FileTrees.requireRegularFile(file, "Connection record");
		ConnectionJsons.ConnectionRecordFields fields = ConfigTools.read(file, ConnectionJsons.ConnectionRecordFields.class)
				.orElseThrow(() -> new IOException("Connection record is empty: " + file));
		normalize(fields);
		return fields;
	}

	private static void normalize(ConnectionJsons.ConnectionRecordFields fields) {
		if (fields.secrets == null) fields.secrets = new HashMap<>();
	}
}
