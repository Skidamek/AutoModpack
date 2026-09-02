package pl.skidam.automodpack_loader_core.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;

import pl.skidam.automodpack_core.auth.OriginTrustStore;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

/** Exact certificate pins owned by the original Minecraft server identity. */
public final class CertificateTrustStore {
	public enum Reason {
		ADDRESS_PIN, TOFU, SEED
	}

	private CertificateTrustStore() {}

	public static synchronized Jsons.CertificateTrustEntry get(InetSocketAddress origin) {
		if (origin == null) return null;
		try {
			return OriginTrustStore.get(storage(), origin);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to load certificate trust for " + AddressHelpers.formatAddress(origin), e);
		}
	}

	public static synchronized String getFingerprint(InetSocketAddress origin) {
		Jsons.CertificateTrustEntry entry = get(origin);
		return entry == null ? null : entry.fingerprint;
	}

	public static synchronized boolean matches(InetSocketAddress origin, String fingerprint) {
		return Objects.equals(getFingerprint(origin), fingerprint);
	}

	public static synchronized void save(InetSocketAddress origin, String fingerprint, Reason reason) {
		if (origin == null || reason == null) throw new IllegalArgumentException("Origin and trust reason are required");
		String normalized = NetUtils.normalizeFingerprint(fingerprint);
		Jsons.CertificateTrustEntry existing = get(origin);
		if (existing != null && Objects.equals(existing.fingerprint, normalized) && Objects.equals(existing.reason, reason.name())) return;
		try {
			OriginTrustStore.save(storage(), origin, new Jsons.CertificateTrustEntry(normalized, reason.name()));
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save certificate trust", e);
		}
	}

	public static synchronized void remove(InetSocketAddress origin) {
		if (origin == null) return;
		try {
			OriginTrustStore.remove(storage(), origin);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to remove certificate trust", e);
		}
	}

	private static ClientStorage storage() {
		return ClientStorage.fromGameDirectory(SmartFileUtils.CWD);
	}
}
