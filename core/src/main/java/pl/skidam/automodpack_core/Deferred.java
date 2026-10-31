package pl.skidam.automodpack_core;

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Work that must not run while the game is still bootstrapping. On Fabric one classloader holds the game, every mod
 * and our core, and Mixin runs its one global config selection on whichever thread loads the first transformable
 * class. A worker that Preload leaves running can be that thread, which puts every mod's mixin plugin on it. Preload
 * queues such work here; the loader drains it from its own main-thread init, after the game has loaded its first
 * classes. A task queued after the drain runs on the calling thread, which is already past that phase.
 */
public final class Deferred {
	private static final Deferred SHARED = new Deferred();

	private final Queue<Runnable> queue = new ConcurrentLinkedQueue<>();
	private final AtomicBoolean drained = new AtomicBoolean();

	public static Deferred shared() {
		return SHARED;
	}

	public void run(Runnable task) {
		if (drained.get()) {
			task.run();
			return;
		}
		queue.add(task);
		if (drained.get() && queue.remove(task)) task.run();
	}

	/** Runs every queued task on the calling thread; only the first call drains, later calls do nothing. */
	public void drain() {
		if (!drained.compareAndSet(false, true)) return;
		Runnable task;
		while ((task = queue.poll()) != null) task.run();
	}
}
