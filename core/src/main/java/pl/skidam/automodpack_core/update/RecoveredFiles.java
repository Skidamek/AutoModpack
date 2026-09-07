package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;

import pl.skidam.automodpack_core.modpack.group.LogicalPath;
import pl.skidam.automodpack_core.utils.FileTrees;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.OsPaths;

/**
 * Human-facing recovered copies. The one path is {@code automodpack/recovered/{dir}/{stem}-{claim8}{ext}}: original
 * folders stay, and the claim is part of the file name so two preserved files that shared a live path never collide.
 */
public final class RecoveredFiles {
	private static final int CLAIM_NAME = 8;

	private RecoveredFiles() {}

	public static Path directory(ClientStorage storage) {
		return Objects.requireNonNull(storage, "storage").recoveredDirectory();
	}

	/** The recovered path for this claim, without checking whether the OS can publish it. */
	public static Path path(ClientStorage storage, String originalPath, String claimId) {
		String canonical = LogicalPath.requireCanonical(originalPath);
		String id = HashUtils.normalizeSha1(claimId);
		Path root = directory(storage);
		Path original = LogicalPath.resolve(root, canonical);
		Path parent = original.getParent();
		String fileName = original.getFileName().toString();
		int dot = fileName.lastIndexOf('.');
		String stem = dot <= 0 ? fileName : fileName.substring(0, dot);
		String ext = dot <= 0 ? "" : fileName.substring(dot);
		Path destination = parent.resolve(stem + "-" + id.substring(0, CLAIM_NAME) + ext).normalize();
		if (!destination.startsWith(root) || !parent.equals(destination.getParent())) throw new IllegalArgumentException("Recovered copy escaped its original folder: " + canonical);
		return destination;
	}

	/** {@link #path} after the publishable-path tripwire. Occupied destinations are the caller's problem. */
	public static Path destination(ClientStorage storage, String originalPath, String claimId) throws IOException {
		Path destination = path(storage, originalPath, claimId);
		FileTrees.requireNoSymbolicLinkDescendants(storage.gameDirectory(), destination, "recovered copy");
		OsPaths.requirePublishableFile(destination);
		return destination;
	}
}
