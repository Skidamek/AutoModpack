package pl.skidam.automodpack_core.utils;

import java.util.Locale;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class CustomThreadFactoryBuilder {
	private String nameFormat;
	private boolean daemon;

	public CustomThreadFactoryBuilder setNameFormat(String nameFormat) {
		this.nameFormat = nameFormat;
		return this;
	}

	public CustomThreadFactoryBuilder setDaemon(boolean daemon) {
		this.daemon = daemon;
		return this;
	}

	public ThreadFactory build() {
		return new CustomThreadFactory(nameFormat, daemon);
	}

	private static class CustomThreadFactory implements ThreadFactory {
		private final AtomicInteger threadNumber = new AtomicInteger(1);
		private final String nameFormat;
		private final boolean daemon;

		private CustomThreadFactory(String nameFormat, boolean daemon) {
			this.nameFormat = nameFormat;
			this.daemon = daemon;
		}

		@Override
		public Thread newThread(Runnable runnable) {
			Thread thread = new Thread(runnable);
			if (nameFormat != null) thread.setName(String.format(Locale.ROOT, nameFormat, threadNumber.getAndIncrement()));
			thread.setDaemon(daemon);
			// A fresh thread inherits the creating thread's priority; pin every worker back to the default.
			thread.setPriority(Thread.NORM_PRIORITY);
			return thread;
		}
	}
}
