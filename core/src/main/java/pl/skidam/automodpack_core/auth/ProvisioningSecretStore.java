package pl.skidam.automodpack_core.auth;

import static pl.skidam.automodpack_core.storage.StoragePaths.PROVISIONING_SECRET_FILE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.FileTrees;

/** Operator provisioning secret. Lives in credentials/, not server-config.json. */
public final class ProvisioningSecretStore {
	private static String cached;
	private static boolean loaded;

	private ProvisioningSecretStore() {}

	public static synchronized String get() {
		if (!loaded) {
			cached = readFile();
			loaded = true;
		}
		return cached;
	}

	public static synchronized String ensure() {
		String existing = get();
		if (existing != null) return existing;
		cached = Secrets.generateSecret().secret();
		loaded = true;
		writeFile(cached);
		return cached;
	}

	static synchronized void load(String secret) {
		cached = secret == null ? null : Secrets.normalizeProvisioningSecret(secret);
		loaded = true;
	}

	static synchronized void reset() {
		cached = null;
		loaded = false;
	}

	private static String readFile() {
		Path file = PROVISIONING_SECRET_FILE;
		if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return null;
		if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS))
			throw new ConfigTools.ConfigException("Provisioning secret is not a regular file: " + file.toAbsolutePath().normalize());
		try {
			String value = Files.readString(file, StandardCharsets.UTF_8).trim();
			if (value.isEmpty()) return null;
			return Secrets.normalizeProvisioningSecret(value);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to read provisioning secret", e);
		}
	}

	private static void writeFile(String secret) {
		Path file = PROVISIONING_SECRET_FILE.toAbsolutePath().normalize();
		Path parent = file.getParent();
		if (parent == null) throw new ConfigTools.ConfigException("Provisioning secret path has no parent: " + file);
		try {
			Files.createDirectories(parent);
			Path temporary = parent.resolve("." + file.getFileName() + "." + UUID.randomUUID() + ".tmp");
			try {
				Files.writeString(temporary, secret + "\n", StandardCharsets.UTF_8, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE);
				try {
					DurableFiles.replace(temporary, file);
				} catch (AtomicMoveNotSupportedException e) {
					throw new IOException("The filesystem cannot durably replace " + file + "; use a major local filesystem with atomic rename support", e);
				}
				FileTrees.forceDirectory(parent);
			} finally {
				Files.deleteIfExists(temporary);
			}
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save provisioning secret", e);
		}
	}
}
