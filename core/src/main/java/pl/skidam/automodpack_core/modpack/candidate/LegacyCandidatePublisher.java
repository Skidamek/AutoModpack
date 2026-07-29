package pl.skidam.automodpack_core.modpack.candidate;

import java.io.IOException;
import java.nio.file.Path;
import java.util.*;

import pl.skidam.automodpack_core.protocol.netty.NettyServer;
import pl.skidam.automodpack_core.utils.ModpackContentTools;

public final class LegacyCandidatePublisher {
	private final Path manifestPath;
	private final NettyServer hostServer;

	public LegacyCandidatePublisher(Path manifestPath, NettyServer hostServer) {
		this.manifestPath = Objects.requireNonNull(manifestPath);
		this.hostServer = hostServer;
	}

	public void publish(ModpackCandidate candidate) throws IOException {
		Map<String, Path> previous = hostServer == null ? Map.of() : hostServer.getPathsSnapshot();
		Map<String, Path> bridge = new HashMap<>(previous);
		bridge.putAll(candidate.hostedPaths());
		if (hostServer != null) hostServer.replacePaths(bridge);
		try {
			ModpackContentTools.writeComplete(manifestPath, candidate.manifest());
			if (hostServer != null) hostServer.replacePaths(candidate.hostedPaths());
		} catch (IOException | RuntimeException e) {
			if (hostServer != null) hostServer.replacePaths(previous);
			throw e;
		}
	}
}
