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

import java.io.IOException;

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
            serverConfig = ConfigTools.loadConfig(serverConfigFile, Jsons.ServerConfigFields.class);
            VersionedCommandSource.sendFeedback(context, VersionedText.literal("AutoModpack server config reloaded!").formatted(Formatting.GREEN), true);
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int startModpackHost(CommandContext<ServerCommandSource> context) {
        Util.getMainWorkerExecutor().execute(() -> {
            if (!httpServer.isRunning()) {
                VersionedCommandSource.sendFeedback(context, VersionedText.literal("Starting modpack hosting...")
                                .formatted(Formatting.YELLOW),
                        true);
                try {
                    httpServer.start();
                    VersionedCommandSource.sendFeedback(context, VersionedText.literal("Modpack hosting started!")
                                    .formatted(Formatting.GREEN),
                            true);
                } catch (IOException e) {
                    LOGGER.error("Couldn't start server.", e);

                    VersionedCommandSource.sendFeedback(context, VersionedText.literal("Couldn't start server." + e.getCause())
                                    .formatted(Formatting.RED),
                            true);
                }

            } else {
                VersionedCommandSource.sendFeedback(context, VersionedText.literal("Modpack hosting is already running!")
                                .formatted(Formatting.RED),
                        false);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int stopModpackHost(CommandContext<ServerCommandSource> context) {
        Util.getMainWorkerExecutor().execute(() -> {
            if (httpServer.isRunning()) {
                VersionedCommandSource.sendFeedback(context, VersionedText.literal("Stopping modpack hosting...")
                                .formatted(Formatting.RED),
                        true);
                httpServer.stop();
                VersionedCommandSource.sendFeedback(context, VersionedText.literal("Modpack hosting stopped!")
                                .formatted(Formatting.RED),
                        true);
            } else {
                VersionedCommandSource.sendFeedback(context, VersionedText.literal("Modpack hosting is not running!")
                                .formatted(Formatting.RED),
                        false);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int restartModpackHost(CommandContext<ServerCommandSource> context) {
        Util.getMainWorkerExecutor().execute(() -> {
            VersionedCommandSource.sendFeedback(context, VersionedText.literal("Restarting modpack hosting...")
                            .formatted(Formatting.YELLOW),
                    true);
            if (httpServer.isRunning()) {
                httpServer.stop();
                try {
                    httpServer.start();

                    VersionedCommandSource.sendFeedback(context, VersionedText.literal("Modpack hosting restarted!")
                                    .formatted(Formatting.GREEN),
                            true);

                } catch (IOException e) {
                    LOGGER.error("Couldn't start server.", e);

                    VersionedCommandSource.sendFeedback(context, VersionedText.literal("Couldn't start server." + e.getCause())
                                    .formatted(Formatting.RED),
                            true);
                }

            } else if (serverConfig.modpackHost){
                try {
                    httpServer.start();

                    VersionedCommandSource.sendFeedback(context, VersionedText.literal("Modpack hosting restarted!")
                                    .formatted(Formatting.GREEN),
                            true);
                } catch (IOException e) {
                    LOGGER.error("Couldn't start server.", e);

                    VersionedCommandSource.sendFeedback(context, VersionedText.literal("Couldn't start server." + e.getCause())
                                    .formatted(Formatting.RED),
                            true);
                }
            } else {
                VersionedCommandSource.sendFeedback(context, VersionedText.literal("Modpack hosting is disabled in config!")
                                .formatted(Formatting.RED),
                        false);
            }
        });
        return Command.SINGLE_SUCCESS;
    }


    private static int modpackHostAbout(CommandContext<ServerCommandSource> context) {
        Formatting statusColor = httpServer.isRunning() ? Formatting.GREEN : Formatting.RED;
        String status = httpServer.isRunning() ? "running" : "not running";
        VersionedCommandSource.sendFeedback(context, VersionedText.literal("Modpack hosting status")
                .formatted(Formatting.GREEN)
                .append(VersionedText.literal(" - ")
                        .formatted(Formatting.WHITE)
                        .append(VersionedText.literal(status)
                                .formatted(statusColor)
                        )
                ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int about(CommandContext<ServerCommandSource> context) {
        VersionedCommandSource.sendFeedback(context, VersionedText.literal("AutoModpack")
                .formatted(Formatting.GREEN)
                .append(VersionedText.literal(" - " + AM_VERSION)
                        .formatted(Formatting.WHITE)
                ), false);
        VersionedCommandSource.sendFeedback(context, VersionedText.literal("/automodpack generate")
                .formatted(Formatting.YELLOW), false);
        VersionedCommandSource.sendFeedback(context, VersionedText.literal("/automodpack host start/stop/restart")
                .formatted(Formatting.YELLOW), false);
        VersionedCommandSource.sendFeedback(context, VersionedText.literal("/automodpack config reload")
                .formatted(Formatting.YELLOW), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateModpack(CommandContext<ServerCommandSource> context) {
        Util.getMainWorkerExecutor().execute(() -> {

            if (modpack.isGenerating()) {
                VersionedCommandSource.sendFeedback(context, VersionedText.literal("Modpack is already generating! Please wait!")
                                .formatted(Formatting.RED),
                        false);
                return;
            }

            VersionedCommandSource.sendFeedback(context, VersionedText.literal("Generating Modpack...")
                            .formatted(Formatting.YELLOW),
                    true);
            long start = System.currentTimeMillis();
            if (modpack.generateNew()) { // TODO generate old if exists
                VersionedCommandSource.sendFeedback(context, VersionedText.literal("Modpack generated! took " + (System.currentTimeMillis() - start) + "ms")
                                .formatted(Formatting.GREEN),
                        true);
            } else {
                VersionedCommandSource.sendFeedback(context, VersionedText.literal("Modpack generation failed! Check logs for more info.")
                                .formatted(Formatting.RED),
                        true);
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
