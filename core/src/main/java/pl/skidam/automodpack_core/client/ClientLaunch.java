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
import pl.skidam.automodpack_core.protocol.PackTransport;
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

	public void run() throws Exception {
		if (rolledBackStuckUpdate) {
			LOGGER.info("Booting the restored modpack without contacting the server");
			boolean projectionActive = hasActiveProjection();
			if (projectionActive) loadLocalModpack(null, null, projectionActive);
			return;
		}

		StoredModpackConnection.Seeded seeded = seedSelected();
		if (seeded == null || !seeded.connection().isComplete()) {
			bootLocalOrSelfUpdate(null, null);
			return;
		}

		ConnectionJsons.ConnectionInfo connectionInfo = seeded.connection();
		Secrets.Secret secret = seeded.secret();
		if (secret == null) LOGGER.info("No saved secret yet for origin {}; the server will decide", AddressHelpers.formatAddress(connectionInfo.origin));

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

	private void syncFromServer(ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret) throws Exception {
		var manifestResult = ManifestFetcher.requestServerModpackContent(storage, connectionInfo, secret, false, clientConfig.selectedModpackId);
		if (!manifestResult.successful()) {
			// An unreachable server is a normal boot condition, not a failure: the installed pack keeps working and the
			// next launch with the server up catches up. One calm line says so; the cause stays at debug.
			LOGGER.info("Modpack server {} is unreachable; continuing with the local state without a sync", AddressHelpers.formatAddress(connectionInfo.origin));
			LOGGER.debug("Manifest fetch did not succeed", manifestResult.failure());
			reconcileStoredTarget(connectionInfo, secret);
			return;
		}

		PackTransport transport = manifestResult.transport();
		SelectedModpackTarget selectedTarget;
		try {
			selectedTarget = SelectedModpackTarget.prepare(manifestResult.content(), new ClientSelectionStore(storage.selectionFile()), ClientPlatform.current());
		} catch (RuntimeException e) {
			LOGGER.error("Failed to resolve the downloaded modpack catalogue and group selection", e);
			transport.close();
			loadLocalModpack(connectionInfo, secret, hasActiveProjection());
			return;
		}
		ModpackJsons.ModpackContentFields latestModpackContent = selectedTarget.flatTarget();
		if (!Objects.equals(clientConfig.selectedModpackId, latestModpackContent.modpackId)) {
			LOGGER.error("Selected modpack catalogue changed ID from {} to {}", clientConfig.selectedModpackId, latestModpackContent.modpackId);
			transport.close();
			loadLocalModpack(connectionInfo, secret, hasActiveProjection());
			return;
		}
		if (SelfUpdater.update(latestModpackContent)) {
			transport.close();
			return;
		}
		if (manifestResult.unchanged()) {
			// The installed head is the server head by hash: no planning or object work can be pending, so boot the local pack.
			LOGGER.info("Server head matches the installed generation of {}; booting the local pack", latestModpackContent.modpackId);
			transport.close();
			loadLocalModpack(connectionInfo, secret, hasActiveProjection());
			return;
		}

		ModpackUpdater updater = new ModpackUpdater(selectedTarget, connectionInfo, secret, storage, transport);
		if (trustedBootstrapApply) updater.applyTrustedInstall();
		else updater.processModpackUpdate(true);
	}

	private void bootLocalOrSelfUpdate(ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret) throws Exception {
		boolean projectionActive = hasActiveProjection();
		if (projectionActive) loadLocalModpack(connectionInfo, secret, projectionActive);
		else SelfUpdater.update();
	}

	private void loadLocalModpack(ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret, boolean projectionActive) throws Exception {
		if (!projectionActive) return;
		new ModpackUpdater(connectionInfo, secret, storage).loadModpack();
	}

	/**
	 * The server is unreachable, so the launch apply targets the stored active projection: its force-copy corrections
	 * (e.g. early-window service jars that must not load in place) still have to land in the standard mods folder
	 * offline. The updater's launch-apply tails hot-load the projection afterwards, and a target that cannot be
	 * resolved falls back to the plain projection load.
	 */
	private void reconcileStoredTarget(ConnectionJsons.ConnectionInfo connectionInfo, Secrets.Secret secret) throws Exception {
		if (!hasActiveProjection()) return;
		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget().orElse(null);
		if (target == null) {
			loadLocalModpack(connectionInfo, secret, true);
			return;
		}
		new ModpackUpdater(target, connectionInfo, secret, storage).applyStoredTargetOffline();
	}

	/** The active pointer is unique state whose unusable content fails the boot in place, so its read failure propagates instead of reading as no projection. */
	private boolean hasActiveProjection() throws IOException {
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
	}

	private boolean isDetachedFromServer() throws IOException {
		return new ClientGenerationStore(storage).isDetached(clientConfig.selectedModpackId);
	}

	private static void writeConfig(Path path, Object value) {
		try {
			ConfigTools.writeAtomic(path, value);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save configuration " + path.toAbsolutePath().normalize(), e);
		}
	}
}
