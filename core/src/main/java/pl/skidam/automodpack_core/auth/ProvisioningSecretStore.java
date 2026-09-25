package pl.skidam.automodpack_core.auth;

import static pl.skidam.automodpack_core.storage.StoragePaths.PROVISIONING_SECRET_FILE;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.utils.DurableFiles;

/** Operator provisioning secret. Lives in credentials/, not server.conf. */
public final class ProvisioningSecretStore {
	private static String cached;
	private static boolean loaded;
	private static boolean fileBacked;

	private ProvisioningSecretStore() {}

	/**
	 * The secret, or null when none is provisioned. A disk-backed secret re-checks the file's presence on every
	 * validation miss (one stat, no read), so deleting the credential file revokes the capability in the running
	 * process within the positive-validation cache window instead of at the next restart; {@code ensure()} still
	 * regenerates on the next start either way. An in-process seed no file backs (the test seam) is answered from
	 * memory.
	 */
	public static synchronized String get() {
		if (!loaded) {
			cached = readFile();
			loaded = true;
			fileBacked = cached != null;
		} else if (fileBacked && !Files.exists(PROVISIONING_SECRET_FILE, LinkOption.NOFOLLOW_LINKS)) {
			return null;
		}
		return cached;
	}

	public static synchronized String ensure() {
		String existing = get();
		if (existing != null) return existing;
		cached = Secrets.generateSecret().secret();
		loaded = true;
		writeFile(cached);
		fileBacked = true;
		return cached;
	}

	static synchronized void load(String secret) {
		cached = secret == null ? null : Secrets.normalizeProvisioningSecret(secret);
		loaded = true;
		fileBacked = false;
	}

	static synchronized void reset() {
		cached = null;
		loaded = false;
		fileBacked = false;
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
		try {
			DurableFiles.writeAtomic(file, (secret + "\n").getBytes(StandardCharsets.UTF_8));
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save provisioning secret", e);
		}
	}
}
