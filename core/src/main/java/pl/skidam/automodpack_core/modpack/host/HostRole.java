package pl.skidam.automodpack_core.modpack.host;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import pl.skidam.automodpack_core.config.ServerConfigStore;
import pl.skidam.automodpack_core.loader.LoaderManagerService.EnvironmentType;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.modpack.PackAbsence;
import pl.skidam.automodpack_core.modpack.candidate.PackOwnedFiles;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.RelayConsent;

/**
 * The publisher half of a process, fixed for one world session. Startup, commands, the handshake and bootstrap ask
 * this one object what is served, so a client never generates over a pack it runs nor relays one it was not told to.
 */
public final class HostRole {
	private static final Logger LOGGER = LogManager.getLogger(HostRole.class);

	private final HostSource source;
	private final ModpackExecutor executor;
	private final ClientStorage clientStorage;
	private final boolean integrated;

	private HostRole(HostSource source, ModpackExecutor executor, ClientStorage clientStorage, boolean integrated) {
		this.source = Objects.requireNonNull(source, "source");
		this.executor = executor;
		this.clientStorage = clientStorage;
		this.integrated = integrated;
	}

	public static HostRole open(EnvironmentType environment, Path gameDirectory, ModpackExecutor executor) throws IOException {
		if (environment != EnvironmentType.CLIENT) return new HostRole(HostSourcePolicy.select(environment, null, false), executor, null, false);
		ClientStorage storage = ClientStorage.open(gameDirectory);
		String active = new ClientGenerationStore(storage).hasActivePack() ? storage.readActiveState().modpackId : null;
		boolean shared = active != null && RelayConsent.isRelayed(storage, active);
		return new HostRole(HostSourcePolicy.select(environment, active, shared), executor, storage, true);
	}

	/** What an executor on this instance must leave out of its scans: nothing on a server, every installed pack's files on a client. */
	public static ModpackExecutor.OwnedFiles ownedElsewhere(EnvironmentType environment, Path gameDirectory) {
		if (environment != EnvironmentType.CLIENT) return () -> PackOwnedFiles.NONE;
		return () -> new ClientGenerationStore(ClientStorage.open(gameDirectory)).packOwnedFiles();
	}

	/** A session whose role could not be settled hosts nothing and says why. */
	public static HostRole unavailable(String reason) {
		return new HostRole(new HostSource.None(new PackAbsence(PackAbsence.Kind.HOST_UNAVAILABLE, "AutoModpack hosting is unavailable this session: " + reason)), null, null, true);
	}

	public HostSource source() {
		return source;
	}

	/** Whether this host lives inside a player's game, whose address is only good for one session. */
	public boolean integrated() {
		return integrated;
	}

	/** Binds what this role serves onto the host. A relay serves exactly the pack the decision named, or nothing. */
	public void bind(NettyServer host) throws IOException {
		if (source instanceof HostSource.Relay relay) {
			host.replacePaths(new ClientGenerationStore(clientStorage).hosting(relay.modpackId()));
			LOGGER.info("Hosting this client's shared modpack {} to joining players", relay.modpackId());
		} else if (source instanceof HostSource.None none) {
			LOGGER.info("{}", none.absence().detail());
		} else {
			bindGeneration();
		}
	}

	private void bindGeneration() {
		long start = System.currentTimeMillis();
		if (ServerConfigStore.current().generateModpackOnStart) {
			if (!integrated) LOGGER.info("Generating modpack...");
			var generation = executor.publish();
			if (generation instanceof ModpackExecutor.Published) {
				LOGGER.info("Modpack generation completed! took {}ms", System.currentTimeMillis() - start);
			} else if (generation instanceof ModpackExecutor.NoChanges) {
				if (integrated) LOGGER.debug("Modpack unchanged, took {}ms", System.currentTimeMillis() - start);
				else LOGGER.info("Modpack generation completed! took {}ms", System.currentTimeMillis() - start);
			} else if (generation instanceof ModpackExecutor.PublishResult.NothingToPublish nothing) {
				if (integrated) LOGGER.info("{}", nothing.absence().detail());
				else LOGGER.warn("{}", nothing.absence().detail());
			} else if (generation instanceof ModpackExecutor.Rejected rejected) {
				throw new IllegalStateException("Failed to generate modpack: " + rejected.detail(), rejected.cause());
			}
		} else {
			LOGGER.info("Loading last modpack...");
			var generation = executor.loadLast();
			if (generation instanceof ModpackExecutor.Loaded loaded) {
				LOGGER.info("Modpack loaded at content {}! took {}ms", loaded.current().contentToken(), System.currentTimeMillis() - start);
			} else if (generation instanceof ModpackExecutor.LoadResult.NothingPublished nothing) {
				LOGGER.warn("{}", nothing.absence().detail());
			} else if (generation instanceof ModpackExecutor.Rejected rejected) {
				throw new IllegalStateException("Failed to load modpack: " + rejected.detail(), rejected.cause());
			}
		}
	}

	/** Why nothing is served, or null when a pack is. */
	public PackAbsence absence() {
		if (source instanceof HostSource.None none) return none.absence();
		if (source instanceof HostSource.Generated) return executor.packAbsence();
		return null;
	}

	/** The document joining players receive. */
	public Optional<PackDocument> currentDocument() throws IOException {
		if (source instanceof HostSource.Generated) return executor.currentDocument();
		if (source instanceof HostSource.Relay relay) return Optional.of(new ClientGenerationStore(clientStorage).activeDocument(relay.modpackId()));
		return Optional.empty();
	}

	/** Why this session may not generate or rewrite a pack, or empty when it may. */
	public Optional<String> generationRefusal() {
		if (source instanceof HostSource.Relay relay)
			return Optional.of("This world shares installed modpack " + relay.modpackId()
					+ "; generating here would replace it. Turn off its LAN sharing or deactivate it first.");
		if (source instanceof HostSource.None none) {
			if (none.absence().kind() == PackAbsence.Kind.NOT_SHARED)
				return Optional.of("This instance runs an installed modpack, so a generated pack would mix its files in. Deactivate it first.");
			return Optional.of(none.absence().detail());
		}
		return Optional.empty();
	}
}
