package pl.skidam.automodpack_loader_core.client;

import static pl.skidam.automodpack_core.Constants.THIS_MOD_JAR;

import java.io.IOException;
import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.OfflineRepair;
import pl.skidam.automodpack_core.update.UpdatePlanner;
import pl.skidam.automodpack_core.utils.FileInspection;
import pl.skidam.automodpack_core.utils.FileIntegrity;

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
		SelectedModpackTarget target = new ClientGenerationStore(storage).readActiveTarget(ClientPlatform.current())
				.orElseThrow(() -> new IOException("Repair is available only for the active installed modpack"));
		return repair.inspect(new OfflineRepair.Request(target, forceCopyPaths(target), protectedModPath));
	}

	public OfflineRepair.Receipt apply(OfflineRepair.Prepared prepared) throws IOException {
		return repair.apply(prepared);
	}

	public OfflineRepair.Receipt apply(OfflineRepair.Prepared prepared, Set<String> editableResetPaths, Set<String> unownedModPaths) throws IOException {
		return repair.apply(prepared, editableResetPaths, unownedModPaths);
	}

	private Set<String> forceCopyPaths(SelectedModpackTarget target) throws IOException {
		Set<String> services = loader.forceCopyServices();
		if (services.isEmpty()) return Set.of();
		TreeSet<String> paths = new TreeSet<>();
		for (var item : target.flatTarget().list.stream().filter(value -> ModpackPathPolicy.isActiveMod(UpdatePlanner.normalize(value.file), value.type)).toList()) {
			long size = parseSize(item.size);
			Path source = verifiedSource(item.file, size, item.sha1);
			if (source == null) continue;
			try (FileSystem fileSystem = FileSystems.newFileSystem(source)) {
				if (!FileInspection.getServices(fileSystem, services).isEmpty()) paths.add(UpdatePlanner.normalize(item.file));
			}
		}
		return Set.copyOf(paths);
	}

	private Path verifiedSource(String logicalPath, long size, String hash) {
		for (Path candidate : List.of(storage.activePath(logicalPath), storage.gamePath(logicalPath), storage.objectsDirectory().resolve(hash)))
			if (FileIntegrity.matches(candidate, size, hash)) return candidate;
		return null;
	}

	private static long parseSize(String value) throws IOException {
		try {
			long size = Long.parseLong(value);
			if (size < 0) throw new IllegalArgumentException("Negative size");
			return size;
		} catch (RuntimeException e) {
			throw new IOException("Installed repair target contains an invalid size: " + value, e);
		}
	}
}
