package pl.skidam.automodpack_core.screen;

import java.util.concurrent.atomic.AtomicLong;

/**
 * One wait/download episode. {@link #begin()} starts or continues the episode and returns a token the adapter must
 * check before showing the wait; {@link #supersede()} invalidates every in-flight wait so a stale preparing screen
 * cannot reopen after the engine has settled.
 */
public final class TransientAttention {
	private final AtomicLong generation = new AtomicLong();

	public long begin() {
		return generation.incrementAndGet();
	}

	public boolean isCurrent(long token) {
		return generation.get() == token;
	}

	public void supersede() {
		generation.incrementAndGet();
	}
}
