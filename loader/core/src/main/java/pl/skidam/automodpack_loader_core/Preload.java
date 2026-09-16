package pl.skidam.automodpack_loader_core;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.*;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.BootstrapConfig;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConfigUtils;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.loader.LoaderManagerService;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.GroupSelectionResolver;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.protocol.NetUtils;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateDeferredException;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.*;
import pl.skidam.automodpack_loader_core.client.CertificateTrustStore;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.ModpackUtils;
import pl.skidam.automodpack_loader_core.loader.LoaderManager;
import pl.skidam.automodpack_loader_core.mods.ModpackLoader;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public class Preload {
	private ClientStorage storage;

	public Preload() {
		try {
			long start = System.currentTimeMillis();
			LOGGER.info("Prelaunching AutoModpack...");
			storage = ClientStorage.fromGameDirectory(SmartFileUtils.CWD);
			initializeConstants();
			loadConfigs();
			DetachedUpdateHelper.cleanupOldHelperJars();
			recoverPendingTransaction();
			if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.CLIENT) {
				LegacyDummyCleanup.migrate();
				importBootstrap();
			}
			updateAll();
			LOGGER.info("AutoModpack prelaunched! took " + (System.currentTimeMillis() - start) + "ms");
		} catch (Exception e) {
			e.printStackTrace();
			throw new RuntimeException(e);
		}
	}

	private static void writeConfig(Path path, Object value) {
		try {
			ConfigTools.writeAtomic(path, value);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save configuration " + path.toAbsolutePath().normalize(), e);
		}
	}

	private void recoverPendingTransaction() throws IOException {
		if (!Files.exists(storage.transactionFile(), LinkOption.NOFOLLOW_LINKS)) return;

		UpdateTransaction transaction;
		try {
			transaction = ConfigTools.read(storage.transactionFile(), UpdateTransaction.class)
					.orElseThrow(() -> new ConfigTools.ConfigException("Transaction file is missing"));
		} catch (RuntimeException e) {
			quarantineTransaction(e);
			return;
		}

		UpdateTransactionExecutor executor;
		try {
			executor = UpdateTransactionSupport.executor(transaction);
			executor.validate(transaction);
		} catch (IOException | RuntimeException e) {
			quarantineTransaction(e);
			return;
		}

		UpdateTransactionExecutor.Execution execution = executor.recover(transaction);
		if (!execution.success()) {
			DetachedUpdateHelper.launch(transaction);
			Path projection = transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE ? storage.activeDirectory() : null;
			new ReLauncher(projection, UpdateType.UPDATE, null).restart(true);
			throw new UpdateDeferredException(transaction.transactionId, execution.blockedPath(), execution.message());
		}
		if (transaction.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) {
			clientConfig = ConfigTools.read(storage.clientConfigFile(), Jsons.ClientConfigFieldsV3.class)
					.orElseThrow(() -> new ConfigTools.ConfigException("Recovered client config is missing"));
		}
		LOGGER.info("Recovered update transaction {}", transaction.transactionId);
	}

	private void quarantineTransaction(Exception reason) throws IOException {
		Files.createDirectories(storage.privateDirectory());
		Path quarantine = storage.privateDirectory().resolve("update-transaction.invalid-" + UUID.randomUUID() + ".json");
		Files.move(storage.transactionFile(), quarantine, StandardCopyOption.REPLACE_EXISTING);
		LOGGER.error("Quarantined invalid update transaction at {}", quarantine.toAbsolutePath().normalize(), reason);
	}

	private void updateAll() {
		if (LOADER_MANAGER.getEnvironmentType() == LoaderManagerService.EnvironmentType.SERVER) {
			SelfUpdater.update();
			return;
		}

		Jsons.ConnectionInfo storedConnectionInfo = null;
		if (clientConfig.selectedModpackId != null && !clientConfig.selectedModpackId.isBlank()) {
			if (!ModpackId.isValid(clientConfig.selectedModpackId)) {
				LOGGER.error("Ignoring invalid selected modpack ID: {}", clientConfig.selectedModpackId);
				clientConfig.selectedModpackId = "";
				writeConfig(storage.clientConfigFile(), clientConfig);
			} else {
				storedConnectionInfo = clientConfig.modpackConnections.get(clientConfig.selectedModpackId);
			}
		}

		if (storedConnectionInfo == null || !storedConnectionInfo.isComplete()) {
			SelfUpdater.update();
			return;
		}

		String expectedFingerprint = CertificateTrustStore.getFingerprint(storedConnectionInfo.origin);
		Jsons.ConnectionInfo connectionInfo = new Jsons.ConnectionInfo(storedConnectionInfo.origin, storedConnectionInfo.endpoint,
				storedConnectionInfo.connectionMode, expectedFingerprint, null);
		Secrets.Secret secret = SecretsStore.getClientSecret(storedConnectionInfo.origin);
		if (secret == null) {
			secret = Secrets.anonymousSecret();
			LOGGER.info("No saved secret for seeded/selected origin {}; using an anonymous preload secret", AddressHelpers.formatAddress(storedConnectionInfo.origin));
		}

		// When update-on-launch is disabled, just load the already-installed
		// modpack: don't contact the server and don't reconcile local files,
		// so the user can freely add/remove mods (e.g. a binary search).
		if (!clientConfig.updateSelectedModpackOnLaunch) {
			if (hasActiveProjection()) {
				loadLocalModpack(connectionInfo, secret);
			} else {
				SelfUpdater.update();
			}
			return;
		}

		var manifestResult = ModpackUtils.requestServerModpackContent(storage, connectionInfo, secret, false);
		SelectedModpackTarget selectedTarget = loadStoredTarget();
		DownloadClient downloadClient = null;
		if (manifestResult.successful()) {
			downloadClient = manifestResult.client();
			try {
				selectedTarget = SelectedModpackTarget.prepare(manifestResult.content(), new ClientSelectionStore(storage.selectionFile()), ClientPlatform.current());
			} catch (RuntimeException e) {
				LOGGER.error("Failed to resolve the downloaded modpack catalogue and group selection", e);
				downloadClient.close();
				loadLocalModpack(connectionInfo, secret);
				return;
			}
			Jsons.ModpackContentFields latestModpackContent = selectedTarget.flatTarget();
			if (!Objects.equals(clientConfig.selectedModpackId, latestModpackContent.modpackId)) {
				LOGGER.error("Selected modpack catalogue changed ID from {} to {}", clientConfig.selectedModpackId, latestModpackContent.modpackId);
				downloadClient.close();
				loadLocalModpack(connectionInfo, secret);
				return;
			}
			if (SelfUpdater.update(latestModpackContent)) {
				downloadClient.close();
				return;
			}
		}
		if (selectedTarget == null) {
			loadLocalModpack(connectionInfo, secret);
			return;
		}

		new ModpackUpdater(selectedTarget, connectionInfo, secret, storage, downloadClient).processModpackUpdate(null);
	}

	private void loadLocalModpack(Jsons.ConnectionInfo connectionInfo, Secrets.Secret secret) {
		try {
			new ModpackUpdater(connectionInfo, secret, storage).loadModpack();
		} catch (Exception e) {
			LOGGER.error("Failed to load local modpack", e);
		}
	}

	private boolean hasActiveProjection() {
		try {
			return Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS) && storage.readActiveState() != null;
		} catch (IOException e) {
			LOGGER.warn("Cannot read active client projection state", e);
			return false;
		}
	}

	private SelectedModpackTarget loadStoredTarget() {
		try {
			Jsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state == null) return null;
			GenerationRecord record = new ClientGenerationStore(storage).read(state.generationId).orElse(null);
			if (record == null) return null;
			SelectionIntent intent = new ClientSelectionStore(storage.selectionFile()).get(state.modpackId)
					.orElseGet(() -> GroupSelectionResolver.defaultIntent(record.manifest()));
			return SelectedModpackTarget.prepare(record.toFields(), null, intent, ClientPlatform.parse(state.platform));
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Failed to resolve the stored modpack catalogue and group selection", e);
			return null;
		}
	}

	private void initializeConstants() {
		// Initialize global variables
		preload = true;
		PRELOAD_TIME = System.currentTimeMillis();
		LOADER_MANAGER = new LoaderManager();
		MODPACK_LOADER = new ModpackLoader();
		MC_VERSION = LOADER_MANAGER.getModVersion("minecraft");
		LOADER_VERSION = LOADER_MANAGER.getLoaderVersion();
		LOADER = LOADER_MANAGER.getPlatformType().toString().toLowerCase(Locale.ROOT);
		THIS_MOD_JAR = JarUtils.getJarPath(this.getClass());
		AM_VERSION = FileInspection.getModVersion(THIS_MOD_JAR);
	}

	private void loadConfigs() {
		long startTime = System.currentTimeMillis();
		boolean shouldSaveClientConfig = false;

		// load client config
		var clientConfigVersion = ConfigTools.read(storage.clientConfigFile(), Jsons.VersionConfigField.class).orElse(null);
		if (clientConfigVersion != null && clientConfigVersion.DO_NOT_CHANGE_IT < 3) {
			clientConfig = new Jsons.ClientConfigFieldsV3();
			shouldSaveClientConfig = true;
			LOGGER.warn("Legacy client config detected. Stable modpack IDs require a one-time modpack redownload.");
		} else {
			clientConfig = ConfigTools.readOrCreate(storage.clientConfigFile(), Jsons.ClientConfigFieldsV3.class, Jsons.ClientConfigFieldsV3::new);
		}

		// load server config
		serverConfig = ConfigTools.readOrCreate(serverConfigFile, Jsons.ServerConfigFieldsV3.class, Jsons.ServerConfigFieldsV3::new);

		if (serverConfig != null) {
			String serverConfigBefore = ConfigTools.GSON.toJson(serverConfig);
			if (serverConfig.acceptedLoaders == null) {
				serverConfig.acceptedLoaders = new HashSet<>(Set.of(LOADER));
			} else {
				serverConfig.acceptedLoaders.add(LOADER);
			}

			ConfigUtils.normalizeServerConfig(serverConfig);
			if (!serverConfigBefore.equals(ConfigTools.GSON.toJson(serverConfig))) writeConfig(serverConfigFile, serverConfig);
		}

		if (clientConfig != null) {
			if (clientConfig.modpackConnections == null) {
				clientConfig.modpackConnections = new HashMap<>();
				shouldSaveClientConfig = true;
			}
			if (clientConfig.selectedModpackId == null) {
				clientConfig.selectedModpackId = "";
				shouldSaveClientConfig = true;
			}
			if (shouldSaveClientConfig) writeConfig(storage.clientConfigFile(), clientConfig);
		}

		knownHosts = ConfigTools.readOrCreate(storage.knownHostsFile(), Jsons.KnownHostsFields.class, Jsons.KnownHostsFields::new);
		if (knownHosts != null && knownHosts.hosts == null) {
			knownHosts.hosts = new HashMap<>();
			writeConfig(storage.knownHostsFile(), knownHosts);
		}
		try {
			Files.createDirectories(storage.privateDirectory());
			String os = System.getProperty("os.name").toLowerCase(Locale.ROOT);
			try {
				if (os.contains("win")) {
					Files.setAttribute(storage.privateDirectory(), "dos:hidden", true);
				} else if (os.contains("nix") || os.contains("nux") || os.contains("aix") || os.contains("mac")) {
					Set<PosixFilePermission> perms = PosixFilePermissions.fromString("rwx------"); // Corresponds to 0700
					Files.setPosixFilePermissions(storage.privateDirectory(), perms);
				}
			} catch (UnsupportedOperationException | IOException e) {
				LOGGER.debug("Failed to set private directory attributes for os: {}", os);
			}
		} catch (IOException e) {
			LOGGER.error("Failed to create private directory", e);
		}

		if (serverConfig == null || clientConfig == null) throw new RuntimeException("Failed to load config!");

		LOGGER.info("Loaded config! took {}ms", System.currentTimeMillis() - startTime);
	}

	private void importBootstrap() {
		if (!Files.isRegularFile(storage.knownHostsBootstrapFile())) return;

		Jsons.KnownHostsBootstrapFields fields = ConfigTools.read(storage.knownHostsBootstrapFile(), Jsons.KnownHostsBootstrapFields.class)
				.orElseThrow(() -> new ConfigTools.ConfigException("Bootstrap file is not a regular file"));
		final BootstrapConfig.Validated bootstrap;
		try {
			bootstrap = BootstrapConfig.validate(fields);
		} catch (IllegalArgumentException e) {
			throw new ConfigTools.ConfigException("Invalid bootstrap file " + storage.knownHostsBootstrapFile().toAbsolutePath().normalize(), e);
		}

		Jsons.KnownHostsFields updatedKnownHosts = new Jsons.KnownHostsFields();
		updatedKnownHosts.hosts = new HashMap<>(knownHosts.hosts);
		String originKey = AddressHelpers.formatAddress(bootstrap.origin());
		Jsons.CertificateTrustEntry previousTrust = updatedKnownHosts.hosts.put(originKey,
				new Jsons.CertificateTrustEntry(bootstrap.fingerprint(), CertificateTrustStore.Reason.SEED.name()));

		String previousSelectedModpackId = clientConfig.selectedModpackId;
		Jsons.ClientConfigFieldsV3 updatedClientConfig = clientConfig;
		Jsons.ConnectionInfo previousConnection = null;
		if (bootstrap.installsModpack()) {
			updatedClientConfig = new Jsons.ClientConfigFieldsV3(clientConfig);
			previousConnection = updatedClientConfig.modpackConnections.put(bootstrap.modpackId(),
					new Jsons.ConnectionInfo(bootstrap.origin(), bootstrap.endpoint(), bootstrap.connectionMode(), null, null));
			updatedClientConfig.selectedModpackId = bootstrap.modpackId();
		}

		writeConfig(storage.knownHostsFile(), updatedKnownHosts);
		if (bootstrap.installsModpack()) writeConfig(storage.clientConfigFile(), updatedClientConfig);

		knownHosts = updatedKnownHosts;
		clientConfig = updatedClientConfig;
		try {
			Files.delete(storage.knownHostsBootstrapFile());
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Bootstrap state was saved but the bootstrap file could not be deleted", e);
		}

		if (previousTrust == null) {
			LOGGER.info("Imported seeded certificate pin for origin {} ({})", originKey, NetUtils.shortenFingerprint(bootstrap.fingerprint()));
		} else {
			LOGGER.info("Replaced seeded certificate pin for origin {}: {} -> {}", originKey, NetUtils.shortenFingerprint(previousTrust.fingerprint),
					NetUtils.shortenFingerprint(bootstrap.fingerprint()));
		}
		if (bootstrap.installsModpack()) {
			String oldOrigin = previousConnection == null || previousConnection.origin == null ? "none" : AddressHelpers.formatAddress(previousConnection.origin);
			String oldEndpoint = previousConnection == null || previousConnection.endpoint == null ? "none" : AddressHelpers.formatAddress(previousConnection.endpoint);
			LOGGER.info("Seed selection {} -> {}; connection origin {} -> {}; endpoint {} -> {}", previousSelectedModpackId, bootstrap.modpackId(), oldOrigin,
					AddressHelpers.formatAddress(bootstrap.origin()), oldEndpoint, AddressHelpers.formatAddress(bootstrap.endpoint()));
		}
	}
}
