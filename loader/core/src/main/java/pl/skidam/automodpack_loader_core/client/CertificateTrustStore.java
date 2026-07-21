package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.HashMap;
import java.util.Objects;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.utils.AddressHelpers;

/** Exact certificate pins owned by the original Minecraft server identity. */
public final class CertificateTrustStore {
	public enum Reason {
		ADDRESS_PIN, TOFU, SEED
	}

	private CertificateTrustStore() {}

	public static synchronized Jsons.CertificateTrustEntry get(InetSocketAddress origin) {
		ensureInitialized();
		String key = AddressHelpers.formatAddress(origin);
		Jsons.CertificateTrustEntry entry = knownHosts.hosts.get(key);
		if (entry != null) return entry;

		// Production releases stored TOFU entries by hostname. Migrate one to the
		// concrete Minecraft origin when that origin is next used.
		String legacyKey = normalizeHost(origin.getHostString());
		entry = knownHosts.hosts.remove(legacyKey);
		if (entry != null) {
			knownHosts.hosts.put(key, entry);
			save();
		}
		return entry;
	}

	public static synchronized String getFingerprint(InetSocketAddress origin) {
		Jsons.CertificateTrustEntry entry = get(origin);
		return entry == null ? null : entry.fingerprint;
	}

	public static synchronized boolean matches(InetSocketAddress origin, String fingerprint) {
		return Objects.equals(getFingerprint(origin), fingerprint);
	}

	public static synchronized void save(InetSocketAddress origin, String fingerprint, Reason reason) {
		ensureInitialized();
		String key = AddressHelpers.formatAddress(origin);
		Jsons.CertificateTrustEntry existing = knownHosts.hosts.get(key);
		String normalized = NetUtils.normalizeFingerprint(fingerprint);
		if (existing != null && Objects.equals(existing.fingerprint, normalized) && Objects.equals(existing.reason, reason.name())) return;
		knownHosts.hosts.put(key, new Jsons.CertificateTrustEntry(normalized, reason.name()));
		save();
	}

	public static synchronized void remove(InetSocketAddress origin) {
		ensureInitialized();
		boolean changed = knownHosts.hosts.remove(AddressHelpers.formatAddress(origin)) != null;
		changed |= knownHosts.hosts.remove(normalizeHost(origin.getHostString())) != null;
		if (changed) save();
	}

	private static String normalizeHost(String host) {
		return AddressHelpers.format(host, 0).getHostString();
	}

	private static void ensureInitialized() {
		if (knownHosts == null) knownHosts = new Jsons.KnownHostsFields();
		if (knownHosts.hosts == null) knownHosts.hosts = new HashMap<>();
	}

	private static void save() {
		try {
			ConfigTools.writeAtomic(knownHostsFile, knownHosts);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save known hosts", e);
		}
	}
}
