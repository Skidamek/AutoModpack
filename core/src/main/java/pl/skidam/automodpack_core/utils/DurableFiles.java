package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

/** Filesystem publication primitives whose failure modes preserve the power-loss contract. */
public final class DurableFiles {
	private DurableFiles() {}

	/**
	 * Replaces one file with a same-filesystem temporary using an atomic directory-entry update.
	 * AutoModpack deliberately refuses filesystems without this primitive: Java has no portable
	 * fallback that guarantees the old or new complete file after power loss.
	 */
	public static void replace(Path temporary, Path target) throws IOException {
		Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
	}
}
