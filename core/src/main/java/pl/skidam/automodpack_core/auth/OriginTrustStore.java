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

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;

/** Shared exact certificate trust keyed by the original Minecraft server address. */
public final class OriginTrustStore {
	private OriginTrustStore() {}

	public static Jsons.CertificateTrustEntry get(ClientStorage storage, InetSocketAddress origin) throws IOException {
		if (origin == null) return null;
		return withLock(storage, () -> readUnlocked(storage.knownHostsFile()).hosts.get(AddressHelpers.formatAddress(origin)));
	}

	public static void save(ClientStorage storage, InetSocketAddress origin, Jsons.CertificateTrustEntry trust) throws IOException {
		if (origin == null || trust == null) throw new IllegalArgumentException("Origin and trust entry are required");
		String key = AddressHelpers.formatAddress(origin);
		withLock(storage, () -> {
			Path file = storage.knownHostsFile();
			Jsons.KnownHostsFields fields = readUnlocked(file);
			Jsons.CertificateTrustEntry existing = fields.hosts.put(key, trust);
			if (existing == null || !Objects.equals(existing.fingerprint, trust.fingerprint) || !Objects.equals(existing.reason, trust.reason)) {
				Files.createDirectories(file.getParent());
				ConfigTools.writeAtomic(file, fields);
			}
			return null;
		});
	}

	public static void remove(ClientStorage storage, InetSocketAddress origin) throws IOException {
		if (origin == null) return;
		withLock(storage, () -> {
			Path file = storage.knownHostsFile();
			Jsons.KnownHostsFields fields = readUnlocked(file);
			if (fields.hosts.remove(AddressHelpers.formatAddress(origin)) != null) {
				Files.createDirectories(file.getParent());
				ConfigTools.writeAtomic(file, fields);
			}
			return null;
		});
	}

	private static Jsons.KnownHostsFields readUnlocked(Path file) throws IOException {
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return new Jsons.KnownHostsFields();
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Known-hosts file is not a regular file: " + file);
		Jsons.KnownHostsFields fields = ConfigTools.read(file, Jsons.KnownHostsFields.class)
				.orElseThrow(() -> new IOException("Known-hosts file is empty: " + file));
		if (fields.hosts == null) fields.hosts = new HashMap<>();
		return fields;
	}

	private static <T> T withLock(ClientStorage storage, LockedOperation<T> operation) throws IOException {
		Path lockPath = storage.knownHostsLockFile();
		Files.createDirectories(lockPath.getParent());
		try (FileChannel channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE); FileLock ignored = channel.lock()) {
			return operation.get();
		}
	}

	@FunctionalInterface
	private interface LockedOperation<T> {
		T get() throws IOException;
	}
}
