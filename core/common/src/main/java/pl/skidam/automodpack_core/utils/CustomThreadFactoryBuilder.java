/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack_core.utils;

import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

public class CustomThreadFactoryBuilder {
    private String nameFormat;
    private boolean daemon;
    private int priority;
    private Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
    private ThreadFactory backingThreadFactory;

    public CustomThreadFactoryBuilder setNameFormat(String nameFormat) {
        this.nameFormat = nameFormat;
        return this;
    }

    public CustomThreadFactoryBuilder setDaemon(boolean daemon) {
        this.daemon = daemon;
        return this;
    }

    public CustomThreadFactoryBuilder setPriority(int priority) {
        this.priority = priority;
        return this;
    }

    public CustomThreadFactoryBuilder setUncaughtExceptionHandler(Thread.UncaughtExceptionHandler uncaughtExceptionHandler) {
        this.uncaughtExceptionHandler = uncaughtExceptionHandler;
        return this;
    }

    public CustomThreadFactoryBuilder setThreadFactory(ThreadFactory backingThreadFactory) {
        this.backingThreadFactory = backingThreadFactory;
        return this;
    }

    public ThreadFactory build() {
        return new CustomThreadFactory(nameFormat, daemon, priority, uncaughtExceptionHandler, backingThreadFactory);
    }

    private static class CustomThreadFactory implements ThreadFactory {
        private final AtomicInteger threadNumber = new AtomicInteger(1);
        private final String nameFormat;
        private final boolean daemon;
        private final int priority;
        private final Thread.UncaughtExceptionHandler uncaughtExceptionHandler;
        private final ThreadFactory backingThreadFactory;

        private CustomThreadFactory(String nameFormat, boolean daemon, int priority, Thread.UncaughtExceptionHandler uncaughtExceptionHandler, ThreadFactory backingThreadFactory) {
            this.nameFormat = nameFormat;
            this.daemon = daemon;
            this.priority = priority;
            this.uncaughtExceptionHandler = uncaughtExceptionHandler;
            this.backingThreadFactory = backingThreadFactory;
        }

        @Override
        public Thread newThread(Runnable runnable) {
            Thread thread;
            if (backingThreadFactory != null) {
                thread = backingThreadFactory.newThread(runnable);
            } else {
                thread = new Thread(runnable);
            }
            if (nameFormat != null) {
                thread.setName(String.format(nameFormat, threadNumber.getAndIncrement()));
            }
            thread.setDaemon(daemon);

            if (priority != 0) {
                thread.setPriority(priority);
            } else {
                thread.setPriority(Thread.NORM_PRIORITY);
            }
            thread.setUncaughtExceptionHandler(uncaughtExceptionHandler);
            return thread;
        }
    }
}
