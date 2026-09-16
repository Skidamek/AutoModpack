package pl.skidam.automodpack_core.modpack.generation;

import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;
import java.util.concurrent.locks.ReentrantLock;

final class PublicationLockRegistry {
	private final Map<Path, Entry> entries = new WeakHashMap<>();

	LockLease acquire(Path root) {
		Objects.requireNonNull(root, "root");
		Entry entry;
		synchronized (this) {
			entry = entries.get(root);
			if (entry == null) {
				entry = new Entry(root);
				entries.put(root, entry);
			} else if (entry.activeLeases == 0) {
				entries.remove(root);
				entry.activeKey = root;
				entries.put(root, entry);
			}
			entry.activeLeases++;
		}
		entry.lock.lock();
		return new LockLease(this, entry);
	}

	private synchronized void release(Entry entry) {
		if (entry.activeLeases <= 0) throw new IllegalStateException("Publication lock lease was already released");
		if (--entry.activeLeases == 0) entry.activeKey = null;
	}

	static final class LockLease implements AutoCloseable {
		private final PublicationLockRegistry registry;
		private final Entry entry;

		private LockLease(PublicationLockRegistry registry, Entry entry) {
			this.registry = registry;
			this.entry = entry;
		}

		@Override
		public void close() {
			entry.lock.unlock();
			registry.release(entry);
		}
	}

	private static final class Entry {
		private final ReentrantLock lock = new ReentrantLock();
		// Keep the exact weak-map key alive while a lease waits or holds the lock; an equal Path must not create a second lock.
		private Path activeKey;
		private int activeLeases;

		private Entry(Path activeKey) {
			this.activeKey = activeKey;
		}
	}
}
