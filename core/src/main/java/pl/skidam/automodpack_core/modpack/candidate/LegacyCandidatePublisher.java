package pl.skidam.automodpack_core.modpack.candidate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

/**
 * Transitional catalogue publisher. It has no generation record yet, but every object it exposes is
 * promoted into the immutable server object store before the catalogue becomes visible.
 */
public final class LegacyCandidatePublisher {
	private final Path manifestPath;
	private final ServerObjectStore objectStore;
	private final NettyServer hostServer;

	public LegacyCandidatePublisher(Path manifestPath, Path objectsDirectory, Path stagingDirectory, NettyServer hostServer) {
		this.manifestPath = Objects.requireNonNull(manifestPath);
		objectStore = new ServerObjectStore(objectsDirectory, stagingDirectory);
		this.hostServer = hostServer;
	}

	public void publish(ModpackCandidate candidate) throws IOException {
		Map<String, Path> previous = hostServer == null ? Map.of() : hostServer.getPathsSnapshot();
		try {
			NavigableMap<String, Path> objects = objectStore.promoteAll(candidate.objects());
			Map<String, Path> bridge = new HashMap<>(previous);
			bridge.putAll(objects);
			if (hostServer != null) hostServer.replacePaths(bridge);
			ModpackContentTools.writeComplete(manifestPath, candidate.manifest());
			if (hostServer != null) hostServer.replacePaths(objects);
		} catch (IOException | RuntimeException e) {
			if (hostServer != null) hostServer.replacePaths(previous);
			try {
				candidate.close();
			} catch (IOException cleanupFailure) {
				e.addSuppressed(cleanupFailure);
			}
			throw e;
		}
	}
}
