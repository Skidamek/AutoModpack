package org.slf4j.impl;

import org.slf4j.ILoggerFactory;
import org.slf4j.Logger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

final class AutoModpackSlf4jLoggerFactory implements ILoggerFactory {
    private final ConcurrentMap<String, Logger> loggers = new ConcurrentHashMap<>();

    @Override
    public Logger getLogger(String name) {
        String actualName = name == null || name.isBlank() ? "AutoModpack" : name;
        return loggers.computeIfAbsent(actualName, AutoModpackSlf4jLoggerAdapter::new);
    }
}
