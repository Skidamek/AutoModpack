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

package pl.skidam.automodpack;

import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.client.ScreenTools;
import pl.skidam.automodpack.loaders.Loader;
import pl.skidam.automodpack.ui.Windows;

import java.awt.*;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import static pl.skidam.automodpack.GlobalVariables.*;

public class ReLauncher {

    public static class Restart {

        public Restart(Path modpackDir, boolean fullDownload) {
            new Restart(modpackDir, "Successfully applied the modpack!", fullDownload);
        }

        public Restart(Path modpackDir, String guiMessage, boolean fullDownload) {
            String environment = Loader.getEnvironmentType();
            boolean isClient = environment.equals("CLIENT");
            boolean isHeadless = GraphicsEnvironment.isHeadless();

            if (isClient) {
                if (!preload && !ScreenTools.getScreenString().contains("restartscreen")) {
                    ScreenTools.setTo.restart(modpackDir, fullDownload);
                    return;
                }

                if (preload && !isHeadless) {
                    new Windows().restartWindow(guiMessage);
                    return;
                }

                LOGGER.info("Restart your client!");
                System.exit(0);
            } else {
                LOGGER.info("Please restart the server to apply updates!");
                System.exit(0);
            }
        }
    }
    public static void init(List<Path> classPath, String[] launchArguments) {
        if (Loader.getEnvironmentType().equals("SERVER")) return;

        final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
        final Path oldLibraryPath = Paths.get(runtimeMXBean.getLibraryPath());

        final Path newLibraryPath = oldLibraryPath.getParent().resolve("new-natives");

        try {
            FileUtils.copyDirectory(oldLibraryPath.toFile(), newLibraryPath.toFile());
        } catch (Exception ignored) {
        }

        // TODO use it to get minecraft username
        String command = formatPath(String.format(
                "%s %s %s",
                runtimeMXBean.getInputArguments().stream().map(ReLauncher::checkForSpaceAfterEquals).collect(Collectors.joining(" ")),
                classPath.stream().map(path -> addQuotes(path.toString())).collect(Collectors.joining(";")),
                Arrays.stream(launchArguments).map(ReLauncher::addQuotes).collect(Collectors.joining(" "))
        )).replace(formatPath(oldLibraryPath.toString()), formatPath(newLibraryPath.toString()));


        // Fix for Fabric/Fabric/Fabric/... in title screen (by just removing --versionType property)
        command = command.replaceAll("--versionType [^ ]+", "");

    }

    private static String checkForSpaceAfterEquals(String argument) {
        final int index = argument.indexOf("=");
        return index < 0 ? argument : argument.substring(0, index + 1) + addQuotes(argument.substring(index + 1));
    }

    private static String addQuotes(String argument) {
        if (!argument.contains(" ") || (argument.startsWith("\"") && argument.endsWith("\""))) {
            return argument;
        }
        return "\"" + argument + "\"";
    }

    private static String formatPath(String text) {
        return text.replace("\\", "/");
    }
}
