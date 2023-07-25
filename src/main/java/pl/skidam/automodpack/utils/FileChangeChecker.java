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

package pl.skidam.automodpack.utils;

import com.google.common.util.concurrent.ThreadFactoryBuilder;
import net.minecraft.util.Util;
import pl.skidam.automodpack.GlobalVariables;
import pl.skidam.automodpack.modpack.HttpServer;
import pl.skidam.automodpack.modpack.Modpack;

import java.awt.image.Kernel;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.concurrent.*;

import static pl.skidam.automodpack.GlobalVariables.LOGGER;

public class FileChangeChecker {
    private final ThreadFactory threadFactoryFileChecker = new ThreadFactoryBuilder()
            .setNameFormat("AutoModpackFileChecker")
            .build();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(1, threadFactoryFileChecker);
    private final Map<Path, FileTime> fileTimes = new HashMap<>();
    private final List<Path> paths;
    private boolean changed = false;

    public FileChangeChecker(List<Path> paths) {
        this.paths = paths;
    }

    public void startChecking() {
        Util.getMainWorkerExecutor().execute(() -> {
            try {
                if (!scheduler.isShutdown()) {
                    // Schedule a task to run every few seconds
                    scheduler.scheduleAtFixedRate(() -> {
                        changed = false;
                        try {
                            checkFiles(paths);
                        } catch (Exception e) {
                            e.printStackTrace();
                        }

                        if (changed) {
                            Modpack.Content.saveModpackContent();
                        }

                    }, 0, 1, TimeUnit.SECONDS);
                } else {
                    HttpServer.fileChangeChecker = new FileChangeChecker(paths);
                    HttpServer.fileChangeChecker.startChecking();
                }
            } catch (Exception e) {
                e.printStackTrace();
            }
        });
    }

    public void stopChecking() {
        scheduler.shutdownNow();
        try {
            if (!scheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                scheduler.shutdownNow();
                if (!scheduler.awaitTermination(3, TimeUnit.SECONDS)) {
                    LOGGER.error("SCHEDULER did not terminate");
                }
            }
        } catch (InterruptedException e) {
            scheduler.shutdownNow();
        }
    }

    public boolean isRunning() {
        return !scheduler.isShutdown() && !scheduler.isTerminated();
    }


    private void checkFiles(List<Path> paths) throws Exception {
        List<Path> pathsCopy = new ArrayList<>(paths);
        for (Path path : pathsCopy) {

            // check if file exists, if not, remove it from the list
            if (Files.exists(path)) {
                if (!this.fileTimes.containsKey(path)) {
                    this.fileTimes.put(path, Files.getLastModifiedTime(path));
                }
            } else {
                LOGGER.info("File removed: {}", path.getFileName());
                this.fileTimes.remove(path);
                this.paths.remove(path);
                Modpack.Content.removeOneItem(path, Modpack.Content.list);
                LOGGER.info("Removed modpack content values for: {}", path.getFileName());
                continue;
            }


            FileTime newTime = Files.getLastModifiedTime(path);
            FileTime oldTime = this.fileTimes.get(path);

            if (!newTime.equals(oldTime)) {
                this.changed = true;
                this.fileTimes.put(path, newTime);

                if (GlobalVariables.serverFullyStarted) {
                    LOGGER.info("File changed: {} ", path.getFileName());
                }

                Modpack.Content.replaceOneItem(path.getParent(), path, Modpack.Content.list);

                if (GlobalVariables.serverFullyStarted) {
                    LOGGER.info("Re-generated modpack content values for: {}", path.getFileName());
                }
            }
        }
    }
}
