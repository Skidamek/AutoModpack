package pl.skidam.automodpack_core.config;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.storage.StoragePaths.BOOTSTRAP_FILE;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.OriginTrustStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.ServerListFile;

/** One-shot import of a human-dropped instance-root bootstrap file into client runtime state. */
public final class BootstrapInstaller {
	private BootstrapInstaller() {}

	public static Optional<Receipt> importIfPresent(ClientStorage storage, ClientConfigJsons.ClientConfigFieldsV3 clientConfig) {
		Path instanceRootBootstrap = storage.gameDirectory().resolve(BOOTSTRAP_FILE.getFileName());
		if (Files.isRegularFile(instanceRootBootstrap) && !instanceRootBootstrap.equals(storage.bootstrapFile()))
			LOGGER.warn("Ignoring instance-root {}; packs can write that path. Place the file at {}", BOOTSTRAP_FILE.getFileName(), BOOTSTRAP_FILE);
		if (!Files.isRegularFile(storage.bootstrapFile())) return Optional.empty();

		ConnectionJsons.KnownHostsBootstrapFields fields = ConfigTools.read(storage.bootstrapFile(), ConnectionJsons.KnownHostsBootstrapFields.class)
				.orElseThrow(() -> new ConfigTools.ConfigException("Bootstrap file is not a regular file"));
		final BootstrapConfig.Validated bootstrap;
		try {
			bootstrap = BootstrapConfig.validate(fields);
		} catch (IllegalArgumentException e) {
			throw new ConfigTools.ConfigException("Invalid bootstrap file " + storage.bootstrapFile().toAbsolutePath().normalize(), e);
		}

		String originKey = AddressHelpers.formatAddress(bootstrap.origin());
		String previousSelectedModpackId = clientConfig.selectedModpackId;
		ConnectionJsons.ConnectionInfo previousConnection = null;
		ConnectionJsons.CertificateTrustEntry previousTrust;
		try {
			previousTrust = OriginTrustStore.get(storage, bootstrap.origin());
			if (bootstrap.hasFingerprint()) OriginTrustStore.save(storage, bootstrap.origin(), new ConnectionJsons.CertificateTrustEntry(bootstrap.fingerprint(), "SEED"));
			if (bootstrap.installsModpack()) {
				previousConnection = ConnectionStore.getConnection(storage, bootstrap.modpackId());
				ConnectionJsons.ConnectionInfo seeded = new ConnectionJsons.ConnectionInfo(bootstrap.origin(), bootstrap.endpoint(), bootstrap.connectionMode(), null, null);
				seeded.approveOrigin(originKey);
				ConnectionStore.saveConnection(storage, bootstrap.modpackId(), seeded);
				clientConfig = clientConfig.withSelectedModpackId(bootstrap.modpackId());
				ConfigTools.writeAtomic(storage.clientConfigFile(), clientConfig);
			}
			if (bootstrap.hasSecret()) {
				if (!bootstrap.installsModpack()) throw new ConfigTools.ConfigException("Bootstrap secret requires an installable modpack");
				ConnectionStore.saveClientSecret(storage, bootstrap.modpackId(), bootstrap.origin(), new Secrets.Secret(bootstrap.secret(), 0L));
			}
			if (bootstrap.hasServerName()) ServerListFile.upsert(storage.gameDirectory().resolve("servers.dat"), bootstrap.serverName(), originKey);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to import bootstrap connection state", e);
		}

		if (bootstrap.hasFingerprint()) {
			if (previousTrust == null) LOGGER.info("Imported seeded certificate pin for origin {} ({})", originKey, NetUtils.shortenFingerprint(bootstrap.fingerprint()));
			else
				LOGGER.info("Replaced seeded certificate pin for origin {}: {} -> {}", originKey, NetUtils.shortenFingerprint(previousTrust.fingerprint),
						NetUtils.shortenFingerprint(bootstrap.fingerprint()));
		}
		if (bootstrap.installsModpack()) {
			String oldOrigin = previousConnection == null || previousConnection.origin == null ? "none" : AddressHelpers.formatAddress(previousConnection.origin);
			String oldEndpoint = previousConnection == null || previousConnection.endpoint == null ? "none" : AddressHelpers.formatAddress(previousConnection.endpoint);
			LOGGER.info("Seed selection {} -> {}; connection origin {} -> {}; endpoint {} -> {}", previousSelectedModpackId, bootstrap.modpackId(), oldOrigin, originKey,
					oldEndpoint, AddressHelpers.formatAddress(bootstrap.endpoint()));
		}

		try {
			Files.delete(storage.bootstrapFile());
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Bootstrap state was saved but the bootstrap file could not be deleted", e);
		}

		return Optional.of(new Receipt(bootstrap, clientConfig));
	}

	public record Receipt(BootstrapConfig.Validated bootstrap, ClientConfigJsons.ClientConfigFieldsV3 clientConfig) {
		public boolean installsModpack() {
			return bootstrap.installsModpack();
		}

		public boolean hasSecret() {
			return bootstrap.hasSecret();
		}
	}
}
