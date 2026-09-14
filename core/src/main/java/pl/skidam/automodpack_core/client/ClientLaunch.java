package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.clientConfig;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Objects;

import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;

/**
 * After pending recovery, how this client boot starts the selected pack: load the local projection,
 * or contact the server and run {@link ModpackUpdater}. Preload stays the entrypoint that recovers
 * and then calls this.
 */
public final class ClientLaunch {
	private final ClientStorage storage;
	private final boolean trustedBootstrapApply;
	private final boolean rolledBackStuckUpdate;

	public ClientLaunch(ClientStorage storage, boolean trustedBootstrapApply, boolean rolledBackStuckUpdate) {
		this.storage = storage;
		this.trustedBootstrapApply = trustedBootstrapApply;
		this.rolledBackStuckUpdate = rolledBackStuckUpdate;
	}

	public void run() {
		if (rolledBackStuckUpdate) {
			LOGGER.info("Booting the restored modpack without contacting the server");
			if (hasActiveProjection()) loadLocalModpack(null, null);
			return;
		}

		StoredModpackConnection.Seeded seeded = seedSelected();
		if (seeded == null || !seeded.connection().isComplete()) {
			bootLocalOrSelfUpdate(null, null);
			return;
		}

		ConnectionJsons.ConnectionInfo connectionInfo = seeded.connection();
		Secrets.Secret secret = seeded.secret();
		if (seeded.anonymousSecret()) LOGGER.info("No saved secret for seeded/selected origin {}; using an anonymous preload secret", AddressHelpers.formatAddress(connectionInfo.origin));

		// updateSelectedModpackOnLaunch=false loads the current projection and does not contact the
		// server, so extra jars in mods/ stay put (binary search, pinning experiments). A trusted
		// bootstrap file is an explicit install request and still applies.
		if (!trustedBootstrapApply && !clientConfig.updateSelectedModpackOnLaunch) {
			bootLocalOrSelfUpdate(connectionInfo, secret);
			return;
		}

		if (!trustedBootstrapApply && isDetachedFromServer()) {
			LOGGER.info("Selected modpack is detached; booting the local pack without server sync");
			bootLocalOrSelfUpdate(connectionInfo, secret);
			return;
		}

		syncFromServer(connectionInfo, secret);
	}

	private StoredModpackConnection.Seeded seedSelected() {
		if (!clientConfig.hasSelectedModpack()) return null;
		if (!ModpackId.isValid(clientConfig.selectedModpackId)) {
			LOGGER.error("Ignoring invalid selected modpack ID: {}", clientConfig.selectedModpackId);
			clientConfig = clientConfig.withSelectedModpackId("");
			writeConfig(storage.clientConfigFile(), clientConfig);
			return null;
		}
		try {
			return StoredModpackConnection.seed(storage, clientConfig.selectedModpackId);
		} catch (IOException e) {
			LOGGER.error("Failed to load selected modpack connection state", e);
			return null;
		}
	}

	private void syncFromServer(ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret) {
		var manifestResult = ManifestFetcher.requestServerModpackContent(storage, connectionInfo, secret, false);
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
			ModpackJsons.ModpackContentFields latestModpackContent = selectedTarget.flatTarget();
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

		ModpackUpdater updater = new ModpackUpdater(selectedTarget, connectionInfo, secret, storage, downloadClient);
		if (trustedBootstrapApply) updater.applyTrustedInstall();
		else updater.processModpackUpdate(true);
	}

	private void bootLocalOrSelfUpdate(ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret) {
		if (hasActiveProjection()) loadLocalModpack(connectionInfo, secret);
		else SelfUpdater.update();
	}

	private void loadLocalModpack(ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret) {
		if (!hasActiveProjection()) return;
		try {
			new ModpackUpdater(connectionInfo, secret, storage).loadModpack();
		} catch (Exception e) {
			LOGGER.error("Failed to load local modpack", e);
		}
	}

	private boolean hasActiveProjection() {
		try {
			if (!clientConfig.hasSelectedModpack()) return false;
			if (!ModpackId.isValid(clientConfig.selectedModpackId)) {
				LOGGER.warn("Skipping active modpack load because the configured selected modpack ID is invalid: {}", clientConfig.selectedModpackId);
				return false;
			}
			if (!Files.isDirectory(storage.activeDirectory(), LinkOption.NOFOLLOW_LINKS)) return false;
			ClientStorageJsons.ClientGenerationStateFields state = storage.readActiveState();
			if (state == null) {
				LOGGER.warn("Skipping active modpack load because the active projection has no active state");
				return false;
			}
			if (!clientConfig.selectedModpackId.equals(state.modpackId)) {
				LOGGER.warn("Skipping active modpack load because active state belongs to {}, but the selected modpack is {}", state.modpackId, clientConfig.selectedModpackId);
				return false;
			}
			return true;
		} catch (IOException e) {
			LOGGER.warn("Cannot read active client projection state", e);
			return false;
		}
	}

	private boolean isDetachedFromServer() {
		try {
			return new ClientGenerationStore(storage).isDetached(clientConfig.selectedModpackId);
		} catch (IOException | RuntimeException e) {
			LOGGER.warn("Cannot read the detached flag of the selected modpack", e);
			return false;
		}
	}

	private SelectedModpackTarget loadStoredTarget() {
		try {
			SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current()).orElse(null);
			if (target != null && !Objects.equals(clientConfig.selectedModpackId, target.manifest().modpackId())) {
				LOGGER.warn("Ignoring stored modpack target {} because the selected modpack is {}", target.manifest().modpackId(), clientConfig.selectedModpackId);
				return null;
			}
			return target;
		} catch (IOException | RuntimeException e) {
			LOGGER.error("Failed to resolve the stored modpack catalogue and group selection", e);
			return null;
		}
	}

	private static void writeConfig(Path path, Object value) {
		try {
			ConfigTools.writeAtomic(path, value);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save configuration " + path.toAbsolutePath().normalize(), e);
		}
	}
}
