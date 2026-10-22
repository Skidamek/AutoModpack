package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.function.Predicate;

/** The commit engine of a game running on Windows, for tests outside this package that drive the real planner. */
public final class HeldFiles {
	private HeldFiles() {}

	/** A process holds every path {@code held} accepts, reported the way the JDK reports a sharing violation: a bare FileSystemException. */
	public static UpdateCommit inGame(ClientStorage storage, Predicate<Path> held) {
		return new UpdateCommit(storage, new UpdateCommit.Probe() {
			@Override
			public void beforeWrite(Path target) throws IOException {
				if (held.test(target)) throw new FileSystemException(target.toString(), null, "The process cannot access the file because it is being used by another process");
			}
		});
	}
}
