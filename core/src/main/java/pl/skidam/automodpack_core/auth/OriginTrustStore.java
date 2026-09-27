package pl.skidam.automodpack_core.auth;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Objects;
import java.util.function.Function;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.FileLocks;

/** Shared exact certificate trust keyed by the original Minecraft server address. */
public final class OriginTrustStore {
	private OriginTrustStore() {}

	public static ConnectionJsons.CertificateTrustEntry get(ClientStorage storage, InetSocketAddress origin) throws IOException {
		if (origin == null) return null;
		return FileLocks.withLock(storage.knownHostsLockFile(), () -> readUnlocked(storage.knownHostsFile()).hosts.get(AddressHelpers.formatAddress(origin)));
	}

	public static void save(ClientStorage storage, InetSocketAddress origin, ConnectionJsons.CertificateTrustEntry trust) throws IOException {
		if (origin == null || trust == null) throw new IllegalArgumentException("Origin and trust entry are required");
		String key = AddressHelpers.formatAddress(origin);
		FileLocks.withLock(storage.knownHostsLockFile(), () -> {
			Path file = storage.knownHostsFile();
			ConnectionJsons.KnownHostsFields fields = readUnlocked(file);
			ConnectionJsons.CertificateTrustEntry existing = fields.hosts.put(key, trust);
			if (existing == null || !Objects.equals(existing.fingerprint, trust.fingerprint) || !Objects.equals(existing.reason, trust.reason)) {
				Files.createDirectories(file.getParent());
				ConfigTools.writeAtomic(file, fields);
			}
			return null;
		});
	}

	public static void remove(ClientStorage storage, InetSocketAddress origin) throws IOException {
		if (origin == null) return;
		FileLocks.withLock(storage.knownHostsLockFile(), () -> {
			Path file = storage.knownHostsFile();
			ConnectionJsons.KnownHostsFields fields = readUnlocked(file);
			if (fields.hosts.remove(AddressHelpers.formatAddress(origin)) != null) {
				Files.createDirectories(file.getParent());
				ConfigTools.writeAtomic(file, fields);
			}
			return null;
		});
	}

	private static ConnectionJsons.KnownHostsFields readUnlocked(Path file) throws IOException {
		ConnectionJsons.KnownHostsFields fields = ConfigTools.readState(file, ConnectionJsons.KnownHostsFields.class, "Known-hosts file", Function.identity()).orElseGet(ConnectionJsons.KnownHostsFields::new);
		if (fields.hosts == null) fields.hosts = new HashMap<>();
		return fields;
	}
}
