package pl.skidam.automodpack;

// The MIT License (MIT)
//
// Copyright (c) 2021 sschr15
//
// Permission is hereby granted, free of charge, to any person obtaining a copy
// of this software and associated documentation files (the "Software"), to deal
// in the Software without restriction, including without limitation the rights
// to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
// copies of the Software, and to permit persons to whom the Software is
// furnished to do so, subject to the following conditions:
//
// The above copyright notice and this permission notice shall be included in
// all copies or substantial portions of the Software.
//
// THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
// IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
// FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
// AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
// LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
// OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
// THE SOFTWARE.


import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.ModContainer;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.file.FileSystem;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import static org.apache.commons.lang3.function.Failable.rethrow;
import static pl.skidam.automodpack.AutoModpackMain.LOGGER;
import static pl.skidam.automodpack.AutoModpackMain.correctName;

// Copied & modified from Version-Mod-Loader by sschr15 under the MIT License
// https://github.com/sschr15/Version-Mod-Loader/blob/master/src/main/java/sschr15/fabricmods/tools/versionmodloader/VersionModLoader.java
// Thanks! :)

public class Relaunch {
    public Relaunch() throws Throwable {
        LOGGER.info("Relaunching...");

        String vmArgs = ManagementFactory.getRuntimeMXBean().getInputArguments()
                .stream().filter(s -> !s.contains("-agentlib") && !s.contains("-javaagent"))
                .collect(Collectors.joining(" "));
        String command = System.getProperty("sun.java.command").split(" ")[0];
        String java = System.getProperty("java.home") + "/bin/java";

        if (System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")) {
            java = java.replace('/', '\\') + ".exe";
        }

        String cp = System.getProperty("java.class.path");
        if (!FabricLoader.getInstance().isDevelopmentEnvironment()) {
            // we need to add our jarfile to the classpath
            ModContainer container = FabricLoader.getInstance().getModContainer("automodpack")
                    .orElseThrow(() -> new RuntimeException("Could not find jar file for automodpack"));
            Path jar;
            try {
                jar = container.getRootPaths().stream().filter(p -> p.getFileName().toString().equals(correctName)).findFirst().get();
            } catch (RuntimeException e) {
                // very old fabric crashes when we try to access root path too early
                Class<?> modContainer = Class.forName("net.fabricmc.loader.ModContainer");
                Method setupRootPath = modContainer.getDeclaredMethod("setupRootPath");
                Field root = modContainer.getDeclaredField("root");
                root.setAccessible(true);
                setupRootPath.setAccessible(true);

                setupRootPath.invoke(container);
                jar = container.getRootPaths().stream().filter(p -> p.getFileName().toString().equals(correctName)).findFirst().get();
                root.set(container, null); // avoid floader crashing trying to set up root twice
            }

            FileSystem fs = jar.getFileSystem();
            cp += File.pathSeparator + fs.toString().replace('\\', '/');
        }

        if (command.equals("org.multimc.EntryPoint")) {
            // replace mmc's entrypoint with fabric's
            try {
                Class.forName("net.fabricmc.loader.launch.knot.KnotClient");
                command = "net.fabricmc.loader.launch.knot.KnotClient";
            } catch (ClassNotFoundException e) {
                command = "net.fabricmc.loader.impl.launch.knot.KnotClient";
            }
        }
        String[] fabricReportedArgs = FabricLoader.getInstance().getLaunchArguments(false);

        // fix for funky mclauncher stuff
        if (vmArgs.contains("-Dos.name=")) {
            String beforeOsName = vmArgs.substring(0, vmArgs.indexOf("-Dos.name="));
            String afterXss = vmArgs.substring(vmArgs.indexOf("-Xss"));
            afterXss = afterXss.substring(afterXss.indexOf(' ') + 1); // skip the value and the space
            vmArgs = beforeOsName + afterXss;

            // fix weird space thing which convinces java it's done parsing vm args
            if (vmArgs.contains("-DFabricMcEmu= ")) {
                vmArgs = vmArgs.replace("-DFabricMcEmu= ", "-DFabricMcEmu=");
            }
        }

        // fix for extra spaces
        vmArgs = vmArgs.replaceAll("\\s+", " ");

        List<String> entireCommand = new ArrayList<>();
        entireCommand.add(java);
        entireCommand.addAll(Arrays.asList(vmArgs.split(" ")));
        entireCommand.add("-cp");
        entireCommand.add(cp);
        entireCommand.add(command); // then pass the original command on
        entireCommand.addAll(Arrays.asList(fabricReportedArgs)); // along with the original args

        if (System.getProperty("debug") != null) {
            LOGGER.info("Launching with command: " + String.join(" ", entireCommand));
        }

        Process process = new ProcessBuilder(entireCommand)
                .inheritIO()
                .start();

        try {
            while (process.isAlive()) {
                //noinspection BusyWait
                Thread.sleep(100);
            }
            System.exit(process.exitValue());
        } catch (InterruptedException e) {
            process.destroy();
            System.exit(1);
        }
    }

    static {
        try {
            new Relaunch();
        } catch (Throwable t) {
            throw rethrow(t);
        }
    }
}