package org.slf4j.impl;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.slf4j.helpers.FormattingTuple;
import org.slf4j.helpers.MarkerIgnoringBase;
import org.slf4j.helpers.MessageFormatter;

final class AutoModpackSlf4jLoggerAdapter extends MarkerIgnoringBase {
    private static final long serialVersionUID = 1L;

    private final transient org.apache.logging.log4j.Logger delegate;

    AutoModpackSlf4jLoggerAdapter(String name) {
        this.name = name;
        this.delegate = LogManager.getLogger(name);
    }

    @Override
    public boolean isTraceEnabled() {
        return delegate.isTraceEnabled();
    }

    @Override
    public void trace(String msg) {
        log(Level.TRACE, msg, null);
    }

    @Override
    public void trace(String format, Object arg) {
        log(Level.TRACE, MessageFormatter.format(format, arg));
    }

    @Override
    public void trace(String format, Object arg1, Object arg2) {
        log(Level.TRACE, MessageFormatter.format(format, arg1, arg2));
    }

    @Override
    public void trace(String format, Object... arguments) {
        log(Level.TRACE, MessageFormatter.arrayFormat(format, arguments));
    }

    @Override
    public void trace(String msg, Throwable t) {
        log(Level.TRACE, msg, t);
    }

    @Override
    public boolean isDebugEnabled() {
        return delegate.isDebugEnabled();
    }

    @Override
    public void debug(String msg) {
        log(Level.DEBUG, msg, null);
    }

    @Override
    public void debug(String format, Object arg) {
        log(Level.DEBUG, MessageFormatter.format(format, arg));
    }

    @Override
    public void debug(String format, Object arg1, Object arg2) {
        log(Level.DEBUG, MessageFormatter.format(format, arg1, arg2));
    }

    @Override
    public void debug(String format, Object... arguments) {
        log(Level.DEBUG, MessageFormatter.arrayFormat(format, arguments));
    }

    @Override
    public void debug(String msg, Throwable t) {
        log(Level.DEBUG, msg, t);
    }

    @Override
    public boolean isInfoEnabled() {
        return delegate.isInfoEnabled();
    }

    @Override
    public void info(String msg) {
        log(Level.INFO, msg, null);
    }

    @Override
    public void info(String format, Object arg) {
        log(Level.INFO, MessageFormatter.format(format, arg));
    }

    @Override
    public void info(String format, Object arg1, Object arg2) {
        log(Level.INFO, MessageFormatter.format(format, arg1, arg2));
    }

    @Override
    public void info(String format, Object... arguments) {
        log(Level.INFO, MessageFormatter.arrayFormat(format, arguments));
    }

    @Override
    public void info(String msg, Throwable t) {
        log(Level.INFO, msg, t);
    }

    @Override
    public boolean isWarnEnabled() {
        return delegate.isWarnEnabled();
    }

    @Override
    public void warn(String msg) {
        log(Level.WARN, msg, null);
    }

    @Override
    public void warn(String format, Object arg) {
        log(Level.WARN, MessageFormatter.format(format, arg));
    }

    @Override
    public void warn(String format, Object arg1, Object arg2) {
        log(Level.WARN, MessageFormatter.format(format, arg1, arg2));
    }

    @Override
    public void warn(String format, Object... arguments) {
        log(Level.WARN, MessageFormatter.arrayFormat(format, arguments));
    }

    @Override
    public void warn(String msg, Throwable t) {
        log(Level.WARN, msg, t);
    }

    @Override
    public boolean isErrorEnabled() {
        return delegate.isErrorEnabled();
    }

    @Override
    public void error(String msg) {
        log(Level.ERROR, msg, null);
    }

    @Override
    public void error(String format, Object arg) {
        log(Level.ERROR, MessageFormatter.format(format, arg));
    }

    @Override
    public void error(String format, Object arg1, Object arg2) {
        log(Level.ERROR, MessageFormatter.format(format, arg1, arg2));
    }

    @Override
    public void error(String format, Object... arguments) {
        log(Level.ERROR, MessageFormatter.arrayFormat(format, arguments));
    }

    @Override
    public void error(String msg, Throwable t) {
        log(Level.ERROR, msg, t);
    }

    private void log(Level level, FormattingTuple tuple) {
        if (!delegate.isEnabled(level)) {
            return;
        }
        log(level, tuple.getMessage(), tuple.getThrowable());
    }

    private void log(Level level, String message, Throwable throwable) {
        if (!delegate.isEnabled(level)) {
            return;
        }
        if (throwable == null) {
            delegate.log(level, message);
            return;
        }
        delegate.log(level, message, throwable);
    }
}
