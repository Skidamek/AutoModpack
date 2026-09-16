package pl.skidam.automodpack_core.screen;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One wait/download episode. {@link #begin()} starts or continues the episode and returns a token the adapter must
 * check before showing the wait; {@link #supersede()} invalidates every in-flight wait so a stale preparing screen
 * cannot reopen after the engine has settled. {@link #commitSuccessor()} marks that a real screen (restart, welcome,
 * preview, failure) already owns the episode, so {@link ScreenService#restore()} must not return to the parent under it.
 */
public final class TransientAttention {
	private final AtomicLong generation = new AtomicLong();
	private volatile boolean successorCommitted;

	public long begin() {
		successorCommitted = false;
		return generation.incrementAndGet();
	}

	public boolean isCurrent(long token) {
		return generation.get() == token;
	}

	public void supersede() {
		generation.incrementAndGet();
	}

	/** A successor screen has claimed this wait; restore becomes a no-op until the next {@link #begin()}. */
	public void commitSuccessor() {
		supersede();
		successorCommitted = true;
	}

	public boolean successorCommitted() {
		return successorCommitted;
	}
}
