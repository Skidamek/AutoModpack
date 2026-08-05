package pl.skidam.automodpack_loader_core.client;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Objects;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
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
		String modpackId = selectedModpackId();
		return modpackId == null ? null : get(modpackId, origin);
	}

	public static synchronized Jsons.CertificateTrustEntry get(String modpackId, InetSocketAddress origin) {
		if (modpackId == null || !ModpackId.isValid(modpackId) || origin == null) return null;
		try {
			return ConnectionStore.getTrust(storage(), modpackId, origin);
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

	public static synchronized boolean matches(String modpackId, InetSocketAddress origin, String fingerprint) {
		Jsons.CertificateTrustEntry entry = get(modpackId, origin);
		return entry != null && Objects.equals(entry.fingerprint, NetUtils.normalizeFingerprint(fingerprint));
	}

	public static synchronized void save(InetSocketAddress origin, String fingerprint, Reason reason) {
		String modpackId = selectedModpackId();
		if (modpackId != null) save(modpackId, origin, fingerprint, reason);
	}

	public static synchronized void save(String modpackId, InetSocketAddress origin, String fingerprint, Reason reason) {
		if (modpackId == null || origin == null || reason == null) throw new IllegalArgumentException("Modpack, origin, and trust reason are required");
		String normalized = NetUtils.normalizeFingerprint(fingerprint);
		Jsons.CertificateTrustEntry existing = get(modpackId, origin);
		if (existing != null && Objects.equals(existing.fingerprint, normalized) && Objects.equals(existing.reason, reason.name())) return;
		try {
			ConnectionStore.saveTrust(storage(), modpackId, origin, new Jsons.CertificateTrustEntry(normalized, reason.name()));
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save certificate trust", e);
		}
	}

	public static synchronized void remove(InetSocketAddress origin) {
		String modpackId = selectedModpackId();
		if (modpackId != null) remove(modpackId, origin);
	}

	public static synchronized void remove(String modpackId, InetSocketAddress origin) {
		if (modpackId == null || origin == null) return;
		try {
			ConnectionStore.removeTrust(storage(), modpackId, origin);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to remove certificate trust", e);
		}
	}

	private static ClientStorage storage() {
		return ClientStorage.fromGameDirectory(SmartFileUtils.CWD);
	}

	private static String selectedModpackId() {
		try {
			Jsons.ClientConfigFieldsV3 config = ConfigTools.read(storage().clientConfigFile(), Jsons.ClientConfigFieldsV3.class).orElse(null);
			return config != null && ModpackId.isValid(config.selectedModpackId) ? config.selectedModpackId : null;
		} catch (RuntimeException e) {
			Constants.LOGGER.debug("Cannot resolve selected modpack for certificate trust", e);
			return null;
		}
	}
}
