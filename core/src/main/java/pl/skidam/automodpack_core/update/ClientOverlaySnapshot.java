package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Stream;

import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.cache.FileMetadataCache;

/** A validated, per-operation view of one editable overlay and its tombstones. */
public record ClientOverlaySnapshot(Map<String, UpdatePlan.FileState> files, String digest) {

	public ClientOverlaySnapshot {
		files = Map.copyOf(files);
		if (!HashUtils.isCanonicalSha1(digest)) throw new IllegalArgumentException("Overlay digest is invalid");
	}

	public static ClientOverlaySnapshot capture(ClientStorage storage, String modpackId, FileMetadataCache cache) throws IOException {
		Path root = storage.overlayDirectory(modpackId);
		var overlayState = storage.readOverlayState(modpackId);
		Map<String, UpdatePlan.FileState> files = new HashMap<>();
		List<OverlayFile> physicalFiles = new ArrayList<>();
		if (Files.exists(root, LinkOption.NOFOLLOW_LINKS)) {
			if (Files.isSymbolicLink(root) || !Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Client overlay root is not a directory: " + root);
			try (Stream<Path> paths = Files.walk(root)) {
				for (Path path : paths.filter(candidate -> !candidate.equals(root)).sorted().toList()) {
					if (Files.isSymbolicLink(path)) throw new IOException("Client overlay contains a symbolic link: " + path);
					if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) continue;
					String relative = LogicalPath.normalize(root.relativize(path).toString());
					String hash = cache == null ? HashUtils.getHash(path) : cache.getTrustedHash(path);
					if (hash == null) throw new IOException("Cannot hash client overlay file: " + path);
					long size = Files.size(path);
					files.put(relative, new UpdatePlan.FileState(hash, size, true));
					physicalFiles.add(new OverlayFile(relative, hash, size));
				}
			}
		}
		MessageDigest digest = HashUtils.newSha1Digest();
		for (String deletedPath : overlayState.deletedPaths) {
			files.put(deletedPath, new UpdatePlan.FileState(null, -1, false));
			digest.update(("D\0" + deletedPath + "\n").getBytes(StandardCharsets.UTF_8));
		}
		for (OverlayFile file : physicalFiles) digest.update((file.relativePath + "\0" + file.size + "\0" + file.sha1.toLowerCase(Locale.ROOT) + "\n").getBytes(StandardCharsets.UTF_8));
		return new ClientOverlaySnapshot(files, HexFormat.of().formatHex(digest.digest()));
	}

	private record OverlayFile(String relativePath, String sha1, long size) {}
}
