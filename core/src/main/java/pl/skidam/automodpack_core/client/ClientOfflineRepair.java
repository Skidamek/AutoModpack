package pl.skidam.automodpack_core.client;

import static pl.skidam.automodpack_core.Constants.THIS_MOD_JAR;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

import pl.skidam.automodpack_core.loader.ModFileCache;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.OfflineRepair;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.cache.FileCache;

/** Loader-aware entry point for the strictly offline active-pack repair workflow. */
public final class ClientOfflineRepair {
	private final ClientStorage storage;
	private final ModpackLoaderService loader;
	private final Path protectedModPath;
	private final OfflineRepair repair;

	public ClientOfflineRepair(ClientStorage storage, ModpackLoaderService loader) {
		this(storage, loader, THIS_MOD_JAR);
	}

	ClientOfflineRepair(ClientStorage storage, ModpackLoaderService loader, Path protectedModPath) {
		this.storage = Objects.requireNonNull(storage, "client storage");
		this.loader = Objects.requireNonNull(loader, "modpack loader");
		this.protectedModPath = Objects.requireNonNull(protectedModPath, "loaded AutoModpack path").toAbsolutePath().normalize();
		this.repair = new OfflineRepair(storage);
	}

	public OfflineRepair.Prepared inspect() throws IOException {
		OfflineRepair.Request request = request();
		repair.recover(request);
		return repair.inspect(request);
	}

	/** Resumes a player-approved repair before normal update or projection loading starts. */
	public Optional<OfflineRepair.Receipt> recover() throws IOException {
		return repair.recover(request());
	}

	public OfflineRepair.Receipt apply(OfflineRepair.Prepared prepared) throws IOException {
		return repair.apply(prepared);
	}

	public OfflineRepair.Receipt apply(OfflineRepair.Prepared prepared, Set<String> editableResetPaths, Set<String> unownedModPaths) throws IOException {
		return repair.apply(prepared, editableResetPaths, unownedModPaths);
	}

	private OfflineRepair.Request request() throws IOException {
		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget()
				.orElseThrow(() -> new IOException("Repair is available only for the active installed modpack"));
		return new OfflineRepair.Request(target, forceCopyPaths(target), protectedModPath);
	}

	private Set<String> forceCopyPaths(SelectedModpackTarget target) throws IOException {
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory()); ModFileCache mods = ModFileCache.open(storage.modCacheDirectory())) {
			return ForceCopyPaths.ofActiveMods(target.flatTarget().list, loader.forceCopyServices(), item -> verifiedSource(item.file, item.size, item.sha1, cache), mods, cache);
		}
	}

	private Path verifiedSource(String logicalPath, long size, String hash, FileCache cache) {
		Path object = storage.objectFile(hash);
		if (FileIntegrity.matchesNamed(object, size, hash, cache)) return object;
		Path active = storage.activePath(logicalPath);
		if (FileIntegrity.matchesObject(active, object, size, hash, cache)) return active;
		Path live = storage.gamePath(logicalPath);
		if (FileIntegrity.matches(live, size, hash, cache)) return live;
		return null;
	}

}
