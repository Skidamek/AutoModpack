package pl.skidam.automodpack_core.auth;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Consumer;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;

/** Shared per-user route, trust, and client-secret state keyed by modpack identity. */
public final class ConnectionStore {
	private ConnectionStore() {}

	public static Jsons.ConnectionRecordFields read(ClientStorage storage, String modpackId) throws IOException {
		Path file = file(storage, modpackId);
		return withLock(storage.connectionLockFile(modpackId), () -> readUnlocked(file));
	}

	public static void update(ClientStorage storage, String modpackId, Consumer<Jsons.ConnectionRecordFields> update) throws IOException {
		Objects.requireNonNull(update, "update");
		Path file = file(storage, modpackId);
		withLock(storage.connectionLockFile(modpackId), () -> {
			Jsons.ConnectionRecordFields fields = readUnlocked(file);
			normalize(fields);
			update.accept(fields);
			Files.createDirectories(file.getParent());
			ConfigTools.writeAtomic(file, fields);
			return null;
		});
	}

	public static Jsons.ConnectionInfo getConnection(ClientStorage storage, String modpackId) throws IOException {
		return read(storage, modpackId).connection;
	}

	public static void saveConnection(ClientStorage storage, String modpackId, Jsons.ConnectionInfo connection) throws IOException {
		ModpackId.requireValid(modpackId);
		if (connection == null || !connection.isComplete()) throw new IllegalArgumentException("Connection origin or endpoint is missing");
		update(storage, modpackId, fields -> fields.connection = connection);
	}

	public static Jsons.CertificateTrustEntry getTrust(ClientStorage storage, String modpackId, InetSocketAddress origin) throws IOException {
		if (origin == null) return null;
		return read(storage, modpackId).trusts.get(AddressHelpers.formatAddress(origin));
	}

	public static void saveTrust(ClientStorage storage, String modpackId, InetSocketAddress origin, Jsons.CertificateTrustEntry trust) throws IOException {
		if (origin == null || trust == null) throw new IllegalArgumentException("Origin and trust entry are required");
		update(storage, modpackId, fields -> fields.trusts.put(AddressHelpers.formatAddress(origin), trust));
	}

	public static void removeTrust(ClientStorage storage, String modpackId, InetSocketAddress origin) throws IOException {
		if (origin == null) return;
		update(storage, modpackId, fields -> fields.trusts.remove(AddressHelpers.formatAddress(origin)));
	}

	public static Secrets.Secret getClientSecret(ClientStorage storage, String modpackId, InetSocketAddress origin) throws IOException {
		if (origin == null) return null;
		return read(storage, modpackId).secrets.get(AddressHelpers.formatAddress(origin));
	}

	public static void saveClientSecret(ClientStorage storage, String modpackId, InetSocketAddress origin, Secrets.Secret secret) throws IOException {
		if (origin == null || secret == null || secret.secret().isBlank()) throw new IllegalArgumentException("Origin and secret are required");
		update(storage, modpackId, fields -> fields.secrets.put(AddressHelpers.formatAddress(origin), secret));
	}

	private static Path file(ClientStorage storage, String modpackId) {
		return storage.connectionFile(ModpackId.requireValid(modpackId));
	}

	private static Jsons.ConnectionRecordFields readUnlocked(Path file) throws IOException {
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Jsons.ConnectionRecordFields();
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Connection record is not a regular file: " + file);
		Jsons.ConnectionRecordFields fields = ConfigTools.read(file, Jsons.ConnectionRecordFields.class)
				.orElseThrow(() -> new IOException("Connection record is empty: " + file));
		normalize(fields);
		return fields;
	}

	private static void normalize(Jsons.ConnectionRecordFields fields) {
		if (fields.trusts == null) fields.trusts = new HashMap<>();
		if (fields.secrets == null) fields.secrets = new HashMap<>();
	}

	private static <T> T withLock(Path lockPath, LockedOperation<T> operation) throws IOException {
		Files.createDirectories(lockPath.getParent());
		try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
			return operation.run();
		}
	}

	@FunctionalInterface
	private interface LockedOperation<T> {
		T run() throws IOException;
	}
}
