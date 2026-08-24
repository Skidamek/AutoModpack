package pl.skidam.automodpack.client.ui;

import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/** Delays short loading states and prevents a visible loading state from flashing away. */
public final class LoadingTransition {
	static final long SHOW_DELAY_MILLIS = 300;
	static final long MINIMUM_VISIBLE_MILLIS = 400;

	private static final ScheduledExecutorService TIMER = Executors.newSingleThreadScheduledExecutor(new LoadingThreadFactory());

	private final Consumer<Runnable> clientExecutor;
	private ScheduledFuture<?> scheduled;
	private long generation;
	private long visibleAtNanos;
	private boolean active;

	public LoadingTransition(Consumer<Runnable> clientExecutor) {
		this.clientExecutor = Objects.requireNonNull(clientExecutor, "client executor");
	}

	public void begin(Runnable displayLoading) {
		Objects.requireNonNull(displayLoading, "loading display");
		synchronized (this) {
			cancelScheduled();
			long token = ++generation;
			active = true;
			visibleAtNanos = 0;
			scheduled = TIMER.schedule(() -> clientExecutor.accept(() -> show(token, displayLoading)), SHOW_DELAY_MILLIS, TimeUnit.MILLISECONDS);
		}
	}

	public void complete(Runnable displayNext) {
		Objects.requireNonNull(displayNext, "next display");
		long token;
		long delayNanos;
		boolean displayImmediately;
		synchronized (this) {
			token = ++generation;
			cancelScheduled();
			if (!active || visibleAtNanos == 0) {
				active = false;
				visibleAtNanos = 0;
				displayImmediately = true;
				delayNanos = 0;
			} else {
				active = false;
				long elapsedNanos = Math.max(0, System.nanoTime() - visibleAtNanos);
				delayNanos = Math.max(0, TimeUnit.MILLISECONDS.toNanos(MINIMUM_VISIBLE_MILLIS) - elapsedNanos);
				visibleAtNanos = 0;
				displayImmediately = delayNanos == 0;
				if (!displayImmediately) scheduled = TIMER.schedule(() -> clientExecutor.accept(() -> finish(token, displayNext)), delayNanos, TimeUnit.NANOSECONDS);
			}
		}
		if (displayImmediately) clientExecutor.accept(displayNext);
	}

	public void cancel() {
		synchronized (this) {
			++generation;
			cancelScheduled();
			active = false;
			visibleAtNanos = 0;
		}
	}

	private void show(long token, Runnable displayLoading) {
		synchronized (this) {
			if (!active || generation != token) return;
			visibleAtNanos = System.nanoTime();
			scheduled = null;
		}
		displayLoading.run();
	}

	private void finish(long token, Runnable displayNext) {
		synchronized (this) {
			if (generation != token) return;
			scheduled = null;
		}
		displayNext.run();
	}

	private synchronized void cancelScheduled() {
		if (scheduled != null) {
			scheduled.cancel(false);
			scheduled = null;
		}
	}

	private static final class LoadingThreadFactory implements ThreadFactory {
		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable, "automodpack-loading-timer");
			thread.setDaemon(true);
			return thread;
		}
	}
}
