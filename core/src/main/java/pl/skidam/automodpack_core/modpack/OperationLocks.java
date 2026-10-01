package pl.skidam.automodpack_core.modpack;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * The modpack operations' mutual exclusion: one scan-shaped operation at a time, and a publication never runs beside
 * another publication. The exporter shares the same locks, so an export can never copy a journal a publish is
 * appending to.
 */
final class OperationLocks {
	private final AtomicBoolean scanActive = new AtomicBoolean();
	private final AtomicBoolean publicationActive = new AtomicBoolean();

	boolean isPublishing() {
		return publicationActive.get();
	}

	/** Null when another operation already holds the scan slot; the publication slot is taken only for publication attempts. */
	Lease acquire(boolean publication) {
		if (!scanActive.compareAndSet(false, true)) return null;
		if (publication && !publicationActive.compareAndSet(false, true)) {
			scanActive.set(false);
			return null;
		}
		return new Lease(publication);
	}

	final class Lease implements AutoCloseable {
		private final boolean publication;
		private boolean closed;

		private Lease(boolean publication) {
			this.publication = publication;
		}

		@Override
		public void close() {
			if (closed) return;
			closed = true;
			if (publication) publicationActive.set(false);
			scanActive.set(false);
		}
	}
}
