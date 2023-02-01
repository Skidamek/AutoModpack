package pl.skidam.automodpack;

import org.apache.commons.io.FileUtils;
import pl.skidam.automodpack.client.ScreenTools;
import pl.skidam.automodpack.utils.JavaPath;

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

import static pl.skidam.automodpack.AutoModpack.LOGGER;

/**
 * Credits to jonafanho for the original code (https://github.com/jonafanho/Minecraft-Mod-Updater/blob/master/common/src/main/java/updater/Launcher.java)
 */

public class ReLauncher {
    private static final List<Runnable> CALLBACKS = new ArrayList<>();
    private static String command;
    private static String javaPath;

    public static class Restart {
        public Restart(File gameDir) {
            if (AutoModpack.preload) {
                ReLauncher.run(gameDir);
            } else {
                ScreenTools.setTo.Restart(ScreenTools.getScreen(), gameDir);
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

//            LOGGER.error("oldLibraryPath: " + oldLibraryPath);

            final Path newLibraryPath = oldLibraryPath.getParent().resolve("new-natives");

//            LOGGER.error("newLibraryPath: " + newLibraryPath);

            try {
                FileUtils.copyDirectory(oldLibraryPath.toFile(), newLibraryPath.toFile());
            } catch (Exception e) {
//                e.printStackTrace();
            }

            javaPath = JavaPath.getJavaPath();

            if (!JavaPath.checkJavaPath(new File(javaPath))) return;

            AutoModpack.LOGGER.warn("Using this java executable path (if wrong/doesn't work change that in config) " + javaPath);

            command = formatPath(String.format(
                    "%s -cp %s %s %s",
                    runtimeMXBean.getInputArguments().stream().map(ReLauncher::checkForSpaceAfterEquals).collect(Collectors.joining(" ")),
                    classPath.stream().map(path -> addQuotes(path.toString())).collect(Collectors.joining(";")),
                    className,
                    Arrays.stream(launchArguments).map(ReLauncher::addQuotes).collect(Collectors.joining(" "))
            )).replace(formatPath(oldLibraryPath.toString()), formatPath(newLibraryPath.toString()));


            // Fix for Fabric/Fabric/Fabric/... in title screen (just removing --versionType property)
            if (command.contains(" --versionType ")) {
                String[] parts = command.split(" --versionType ");
                String launchArgsBeforeVersionType = parts[0];
                String launchArgsAfterVersionType = parts[1].replaceFirst("\\S+\\s*", "");
                command = launchArgsBeforeVersionType + launchArgsAfterVersionType;
            }
        }
    }

    public static void run(File gameDir) {
        if (Platform.getEnvironmentType().equals("SERVER")) {
            LOGGER.info("Please restart the server to apply updates!");
            System.exit(0);
        }

        if (command == null) {
            LOGGER.error("Can't relaunch, relauncher not initialized!");
            return;
        }

        boolean isWindows = System.getProperty("os.name").toLowerCase().contains("windows");

        // remove old -Dfabric.addMods and -Dloader.addMods properties
        if (command.contains("-Dfabric.addMods") || command.contains("-Dloader.addMods")) {
            command = removeAddModsProperties(command);
        }

        String optionalModsProperty = "";

        if (gameDir != null) { // -Dfabric.addMods || -Dloader.modsDir
            File modsDir = new File(gameDir + "/mods/");
            if (modsDir.exists()) {
                if (modsDir.listFiles() != null && modsDir.listFiles().length > 0) {
                    optionalModsProperty = getOptionalModsProperty(modsDir);
                }
            }
        }

        command = formatPath(String.format(
                "%s %s %s",
                addQuotes(javaPath),
                optionalModsProperty,
                command
        ));


        if (gameDir != null && (!command.contains(optionalModsProperty) && !command.contains(formatPath(optionalModsProperty)))) {
            LOGGER.error("AutoModpack relauncher failed to add {} property to command!\nCommand: {}", optionalModsProperty, command);
            System.exit(1);
        }

        if (!isWindows) {
            LOGGER.warn("AutoModpack relauncher may not work on non-windows systems!");
            LOGGER.warn("Check this issue: https://github.com/Skidamek/AutoModpack/issues/87");
        }

        LOGGER.info("Restarting Minecraft with command:\n" + command);
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

//        if (!AutoModpack.preload) {
//            new Window().restartingWindow();
//        }

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

    private static String removeAddModsProperties(String jvmArgs) {
        StringBuilder sb = new StringBuilder();

        if (jvmArgs.contains("-Dfabric.addMods=")) {
            String[] args = jvmArgs.split("-Dfabric.addMods=");
            for (int i = 0; i < args.length; i++) {
                if (i == 0) {
                    sb.append(args[i]);
                    if (i < args.length - 1) {
                        sb.append(" ");
                    }
                } else {
                    String[] subArgs = args[i].split(" ");
                    for (int j = 1; j < subArgs.length; j++) {
                        sb.append(subArgs[j]);
                        if (j < subArgs.length - 1) {
                            sb.append(" ");
                        }
                    }
                }
            }
        }

        if (jvmArgs.contains("-Dloader.addMods=")) {
            String[] args = jvmArgs.split("-Dloader.addMods=");
            for (int i = 0; i < args.length; i++) {
                if (i == 0) {
                    sb.append(args[i]);
                    if (i < args.length - 1) {
                        sb.append(" ");
                    }
                } else {
                    String[] subArgs = args[i].split(" ");
                    for (int j = 1; j < subArgs.length; j++) {
                        sb.append(subArgs[j]);
                        if (j < subArgs.length - 1) {
                            sb.append(" ");
                        }
                    }
                }
            }
        }

        if (sb.toString().equals("") || sb.toString().equals(" ")) {
            return jvmArgs;
        }

        return sb.toString();
    }
}
