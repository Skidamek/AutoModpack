package pl.skidam.automodpack.modpack;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import pl.skidam.automodpack.client.ui.versioned.VersionedCommandSource;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

import static net.minecraft.server.command.CommandManager.literal;
import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Commands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        dispatcher.register(
                literal("automodpack")
                        .executes(Commands::about)
                        .then(literal("generate")
                                .requires((source) -> source.hasPermissionLevel(3))
                                .executes(Commands::generateModpack)
                        )
                        .then(literal("host")
                                .requires((source) -> source.hasPermissionLevel(3))
                                .executes(Commands::modpackHostAbout)
                                .then(literal("start")
                                        .requires((source) -> source.hasPermissionLevel(3))
                                        .executes(Commands::startModpackHost)
                                )
                                .then(literal("stop")
                                        .requires((source) -> source.hasPermissionLevel(3))
                                        .executes(Commands::stopModpackHost)
                                )
                                .then(literal("restart")
                                        .requires((source) -> source.hasPermissionLevel(3))
                                        .executes(Commands::restartModpackHost)
                                )
                        )
                        .then(literal("config")
                                .requires((source) -> source.hasPermissionLevel(3))
                                .then(literal("reload")
                                        .requires((source) -> source.hasPermissionLevel(3))
                                        .executes(Commands::reload)
                                )
                        )
        );
    }

    private static int reload(CommandContext<ServerCommandSource> context) {
        Util.getMainWorkerExecutor().execute(() -> {
            var tempServerConfig = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFields.class);
            if (tempServerConfig != null) {
                serverConfig = tempServerConfig;
                send(context, "AutoModpack server config reloaded!", Formatting.GREEN, true);
            } else {
                send(context, "Error while reloading config file!", Formatting.RED, true);
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static int startModpackHost(CommandContext<ServerCommandSource> context) {
        Util.getMainWorkerExecutor().execute(() -> {
            if (!hostServer.shouldRunInternally()) {
                send(context, "Starting modpack hosting...", Formatting.YELLOW, true);
                hostServer.start();
                if (hostServer.shouldRunInternally()) {
                    send(context, "Modpack hosting started!", Formatting.GREEN, true);
                } else {
                    send(context, "Couldn't start server!", Formatting.RED, true);
                }
            } else {
                send(context, "Modpack hosting is already running!", Formatting.RED, false);
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static int stopModpackHost(CommandContext<ServerCommandSource> context) {
        Util.getMainWorkerExecutor().execute(() -> {
            if (hostServer.shouldRunInternally()) {
                send(context, "Stopping modpack hosting...", Formatting.RED, true);
                if (hostServer.stop()) {
                    send(context, "Modpack hosting stopped!", Formatting.RED, true);
                } else {
                    send(context, "Couldn't stop server!", Formatting.RED, true);
                }
            } else {
                send(context, "Modpack hosting is not running!", Formatting.RED, false);
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static int restartModpackHost(CommandContext<ServerCommandSource> context) {
        Util.getMainWorkerExecutor().execute(() -> {
            send(context, "Restarting modpack hosting...", Formatting.YELLOW, true);
            boolean needStop = hostServer.shouldRunInternally();
            boolean stopped = false;
            if (needStop) {
                stopped = hostServer.stop();
            }

            if (needStop && !stopped) {
                send(context, "Couldn't restart server!", Formatting.RED, true);
            } else {
                hostServer.start();
                if (hostServer.shouldRunInternally()) {
                    send(context, "Modpack hosting restarted!", Formatting.GREEN, true);
                } else {
                    send(context, "Couldn't restart server!", Formatting.RED, true);
                }
            }
        });

        return Command.SINGLE_SUCCESS;
    }


    private static int modpackHostAbout(CommandContext<ServerCommandSource> context) {
        Formatting statusColor = hostServer.shouldRunInternally() ? Formatting.GREEN : Formatting.RED;
        String status = hostServer.shouldRunInternally() ? "running" : "not running";
        send(context, "Modpack hosting status", Formatting.GREEN, status, statusColor, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int about(CommandContext<ServerCommandSource> context) {
        send(context, "AutoModpack", Formatting.GREEN, AM_VERSION, Formatting.WHITE, false);
        send(context, "/automodpack generate", Formatting.YELLOW, false);
        send(context, "/automodpack host start/stop/restart", Formatting.YELLOW, false);
        send(context, "/automodpack config reload", Formatting.YELLOW, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateModpack(CommandContext<ServerCommandSource> context) {
        Util.getMainWorkerExecutor().execute(() -> {
            if (modpack.isGenerating()) {
                send(context, "Modpack is already generating! Please wait!", Formatting.RED, false);
                return;
            }
            send(context, "Generating Modpack...", Formatting.YELLOW, true);
            long start = System.currentTimeMillis();
            if (modpack.generateNew()) {
                send(context, "Modpack generated! took " + (System.currentTimeMillis() - start) + "ms", Formatting.GREEN, true);
            } else {
                send(context, "Modpack generation failed! Check logs for more info.", Formatting.RED, true);
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static void send(CommandContext<ServerCommandSource> context, String msg, Formatting msgColor, boolean broadcast) {
        VersionedCommandSource.sendFeedback(context,
                VersionedText.literal(msg)
                        .formatted(msgColor),
                broadcast);
    }

    private static void send(CommandContext<ServerCommandSource> context, String msg, Formatting msgColor, String appendMsg, Formatting appendMsgColor, boolean broadcast) {
        VersionedCommandSource.sendFeedback(context,
                VersionedText.literal(msg)
                    .formatted(msgColor)
                    .append(VersionedText.literal(" - ")
                            .formatted(Formatting.WHITE))
                    .append(VersionedText.literal(appendMsg)
                            .formatted(appendMsgColor)),
                broadcast);
    }
}
