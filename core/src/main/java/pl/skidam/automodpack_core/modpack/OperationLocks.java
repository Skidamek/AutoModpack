package pl.skidam.automodpack_core.modpack;

import java.util.concurrent.atomic.AtomicReference;

/**
 * The modpack operations' mutual exclusion: one operation at a time, and the exporter shares these slots, so an export
 * can never copy a journal a publish is appending to. One state, not two - a publication slot can never exist without
 * the operation slot, so there is no ordering to get wrong and no branch that cannot happen.
 */
final class OperationLocks {
	/** What the holder of the slot is doing; a publication also refuses every other operation. */
	enum Operation {
		SCAN, PUBLICATION
	}

	/** The one refusal every caller reports when the slot is taken. */
	static final String BUSY = "Another modpack operation is already in progress";

	/** The slot was taken: the operation produces nothing rather than queueing behind the holder. */
	static final class BusyException extends RuntimeException {
		private static final long serialVersionUID = 1L;

		private BusyException() {
			super(BUSY);
		}
	}

	private final AtomicReference<Operation> held = new AtomicReference<>();

	boolean isPublishing() {
		return held.get() == Operation.PUBLICATION;
	}

	/** The slot for one operation, released when the lease closes; throws {@link BusyException} instead of queueing. */
	Lease acquire(Operation operation) {
		if (!held.compareAndSet(null, operation)) throw new BusyException();
		return new Lease(operation);
	}

	final class Lease implements AutoCloseable {
		private final Operation operation;
		private boolean closed;

		private Lease(Operation operation) {
			this.operation = operation;
		}

		@Override
		public void close() {
			if (closed) return;
			closed = true;
			held.compareAndSet(operation, null);
		}
	}
}
