package pl.skidam.automodpack;

import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.client.ScreenTools;
import pl.skidam.automodpack.ui.Windows;
import pl.skidam.automodpack.utils.JavaPath;

import java.awt.*;
import java.io.BufferedReader;
import java.io.File;
import java.io.InputStreamReader;
import java.lang.management.ManagementFactory;
import java.lang.management.RuntimeMXBean;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

import static pl.skidam.automodpack.StaticVariables.*;

/**
 * Credits to jonafanho for the original code (https://github.com/jonafanho/Minecraft-Mod-Updater/blob/master/common/src/main/java/updater/Launcher.java)
 */

public class ReLauncher {
    private static final List<Runnable> CALLBACKS = new ArrayList<>();
    private static String command;
    private static String javaPath;

    public static class Restart {

        public Restart(File gameDir) {
            new Restart(gameDir, "Successfully applied the modpack!");
        }
        
        public Restart(File gameDir, String guiMessage) {
            String environment = Platform.getEnvironmentType();
            boolean isClient = environment.equals("CLIENT");
            boolean isHeadless = GraphicsEnvironment.isHeadless();
            boolean autoRelaunch = clientConfig.autoRelauncher;

            if (isClient) {
                if (!preload && !ScreenTools.getScreenString().contains("restartscreen")) {
                    ScreenTools.setTo.restart(ScreenTools.getScreen(), gameDir);
                    return;
                }

                if (autoRelaunch) {
                    if (!Platform.Forge) {
                        ReLauncher.run();
                        return;
                    }
                }

                if (!isHeadless) {
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
        if (Platform.getEnvironmentType().equals("SERVER")) return;

        String className = null;
        for (final StackTraceElement stackTraceElement : Thread.currentThread().getStackTrace()) {
            className = stackTraceElement.getClassName();
            if (stackTraceElement.getMethodName().equals("main")) {
                if (className.contains("fabric") || className.contains("quilt")) {
                    break;
                }
            }
        }

        if (className != null) { // TODO make it work on linux
            final RuntimeMXBean runtimeMXBean = ManagementFactory.getRuntimeMXBean();
            final Path oldLibraryPath = Paths.get(runtimeMXBean.getLibraryPath());

            final Path newLibraryPath = oldLibraryPath.getParent().resolve("new-natives");

            try {
                FileUtils.copyDirectory(oldLibraryPath.toFile(), newLibraryPath.toFile());
            } catch (Exception ignored) {
            }

            javaPath = JavaPath.getJavaPath();

            if (clientConfig.autoRelauncher) {
                LOGGER.warn("Using this java executable path (if wrong/doesn't work change that in config) " + javaPath);
            }

            command = formatPath(String.format(
                    "%s -cp %s %s %s",
                    runtimeMXBean.getInputArguments().stream().map(ReLauncher::checkForSpaceAfterEquals).collect(Collectors.joining(" ")),
                    classPath.stream().map(path -> addQuotes(path.toString())).collect(Collectors.joining(";")),
                    className,
                    Arrays.stream(launchArguments).map(ReLauncher::addQuotes).collect(Collectors.joining(" "))
            )).replace(formatPath(oldLibraryPath.toString()), formatPath(newLibraryPath.toString()));


            // Fix for Fabric/Fabric/Fabric/... in title screen (by just removing --versionType property)
            command = command.replaceAll("--versionType [^ ]+", "");
        }
    }

    public static void run() {
        if (command == null) {
            LOGGER.error("Can't relaunch, relauncher not initialized!");
            return;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");

        // remove old -Dfabric.addMods and -Dloader.addMods properties
        if (command.contains("-Dfabric.addMods") || command.contains("-Dloader.addMods")) {
            command = removeAddModsProperties(command);
        }

        command = formatPath(String.format(
                "%s %s",
                addQuotes(javaPath),
                command
        ));

        if (!isWindows) {
            LOGGER.warn("AutoModpack relauncher may not work on non-windows systems!");
            LOGGER.warn("Check this issue: https://github.com/Skidamek/AutoModpack/issues/87");
        }

        LOGGER.info("Restarting Minecraft with command:\n" + censorPrivateInfo(command));
        try {
            Process process = Runtime.getRuntime().exec(command);
            CompletableFuture.runAsync(() -> {
                try {
                    process.waitFor();

                    BufferedReader input = new BufferedReader(new InputStreamReader(process.getInputStream()));
                    BufferedReader error = new BufferedReader(new InputStreamReader(process.getErrorStream()));
                    String line;
                    while ((line = input.readLine()) != null) {
                        LOGGER.info(line);
                    }
                    while ((line = error.readLine()) != null) {
                        LOGGER.warn(line);
                    }
                    input.close();
                    error.close();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            });
        } catch (Exception e) {
            e.printStackTrace();
        }

        CALLBACKS.forEach(Runnable::run);

        if (!isWindows) {
            LOGGER.warn("AutoModpack relauncher may not work on non-windows systems!");
            LOGGER.warn("Check this issue: https://github.com/Skidamek/AutoModpack/issues/87");
        }

        System.exit(0);
    }

    public static void addCallback(Runnable callback) {
        CALLBACKS.add(callback);
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

    private static String getOptionalModsProperty(File additionalModsDir) {
        if (additionalModsDir == null) {
            return "";
        }

        Path path = additionalModsDir.toPath().normalize().toAbsolutePath();
        String strPath = formatPath(path.toString());

        if (Platform.Fabric) {
            return "-Dfabric.addMods=\"" + strPath + "\"";
        } else if (Platform.Quilt) { // quilt changed how it handles optional mods
            String loaderVersion = Platform.getModVersion("quilt_loader");
            if (loaderVersion.contains("-beta")) {
                loaderVersion = loaderVersion.replaceFirst("-beta\\..*", "");
            }
            String loaderVersionWithChanges = "0.18.1";
            int result = loaderVersion.compareTo(loaderVersionWithChanges);
            if (result < 0) {
                return "-Dloader.addMods=\"" + strPath + "\"";
            } else {
                return "-Dloader.addMods=\"" + strPath + "/*\"";
            }
        } else {
            LOGGER.error("Can't get optional mods property, unknown platform!");
            return "";
        }
    }

    private static String removeProperty(String jvmArgs, String property) {
        int propertyIndex = jvmArgs.indexOf(property);
        if (propertyIndex == -1) {
            return jvmArgs;
        }

        int startIndex = propertyIndex + property.length();
        int endIndex = jvmArgs.indexOf(" ", startIndex);
        if (endIndex == -1) {
            endIndex = jvmArgs.length();
        }

        return jvmArgs.substring(0, propertyIndex) + jvmArgs.substring(endIndex);
    }

    private static String removeAddModsProperties(String jvmArgs) {
        jvmArgs = removeProperty(jvmArgs, "-Dfabric.addMods=");
        jvmArgs = removeProperty(jvmArgs, "-Dloader.addMods=");
        return jvmArgs;
    }

    private static String censorPrivateInfo(String command) {
        return command.replaceAll("--username [^ ]+", "--username <censored>")
                .replaceAll("--accessToken [^ ]+", "--accessToken <censored>")
                .replaceAll("--uuid [^ ]+", "--uuid <censored>")
                .replaceAll("--xuid [^ ]+", "--xuid <censored>")
                .replaceAll("--clientId [^ ]+", "--clientId <censored>");
    }
}
