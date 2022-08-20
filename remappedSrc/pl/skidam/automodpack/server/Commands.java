package pl.skidam.automodpack.server;

import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.AutoModpackServer;

import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.literal;

public class Commands {
    public static void register() { // TODO config server reload command
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
                literal("automodpack")
                        .executes(Commands::about)
                        .then(literal("generate-modpack")
                                .requires((source) -> source.hasPermissionLevel(2))
                                .executes(Commands::generateModpack)
                        )
                        .then(literal("modpack-host")
                                .requires((source) -> source.hasPermissionLevel(2))
                                .executes(Commands::modpackHostAbout)
                                .then(literal("start")
                                        .requires((source) -> source.hasPermissionLevel(2))
                                        .executes(Commands::startModpackHost)
                                )
                                .then(literal("stop")
                                        .requires((source) -> source.hasPermissionLevel(2))
                                        .executes(Commands::stopModpackHost)
                                )
                                .then(literal("restart")
                                        .requires((source) -> source.hasPermissionLevel(2))
                                        .executes(Commands::restartModpackHost)
                                )
                        )
        ));
    }

    private static int startModpackHost(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            if (!HostModpack.isRunning) {
                context.getSource().sendFeedback(Text.literal("Starting modpack hosting...")
                                .formatted(Formatting.YELLOW),
                        true);
                HostModpack.start();
                context.getSource().sendFeedback(Text.literal("Modpack hosting started!")
                                .formatted(Formatting.GREEN),
                        true);
            } else {
                context.getSource().sendFeedback(Text.literal("Modpack hosting is already running!")
                                .formatted(Formatting.RED),
                        true);
            }
        });
        return 0;

    }

    private static int stopModpackHost(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            if (HostModpack.isRunning) {
                context.getSource().sendFeedback(Text.literal("Stopping modpack hosting...")
                                .formatted(Formatting.RED),
                        true);
                HostModpack.stop();
                context.getSource().sendFeedback(Text.literal("Modpack hosting stopped!")
                                .formatted(Formatting.RED),
                        true);
            } else {
                context.getSource().sendFeedback(Text.literal("Modpack hosting is not running!")
                                .formatted(Formatting.RED),
                        true);
            }
        });
        return 0;
    }

    private static int restartModpackHost(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            context.getSource().sendFeedback(Text.literal("Restarting modpack hosting...")
                            .formatted(Formatting.YELLOW),
                    true);
            if (HostModpack.isRunning) {
                HostModpack.stop();
            }
            if (!HostModpack.isRunning) {
                HostModpack.start();
            }
            context.getSource().sendFeedback(Text.literal("Modpack hosting restarted!")
                            .formatted(Formatting.GREEN),
                    true);
        });
        return 0;
    }


    private static int modpackHostAbout(CommandContext<ServerCommandSource> context) {
        Formatting statusColor = HostModpack.isRunning ? Formatting.GREEN : Formatting.RED;
        context.getSource().sendFeedback(Text.literal("Modpack hosting status")
                .formatted(Formatting.GREEN)
                .append(Text.literal(" - ")
                        .formatted(Formatting.WHITE)
                        .append(Text.literal( "" + HostModpack.isRunning)
                                .formatted(statusColor)
                        )
                ), false);
        context.getSource().sendFeedback(Text.literal("/automodpack modpack-host start")
                .formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(Text.literal("/automodpack modpack-host stop")
                .formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(Text.literal("/automodpack modpack-host restart")
                .formatted(Formatting.YELLOW), false);
        return 0;
    }

    private static int about(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(Text.literal("AutoModpack")
                .formatted(Formatting.GREEN)
                .append(Text.literal(" - " + AutoModpackMain.VERSION)
                        .formatted(Formatting.WHITE)
                ), false);

        return 0;
    }

    public static int generateModpack(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            context.getSource().sendFeedback(Text.literal("Generating Modpack...")
                            .formatted(Formatting.GREEN),
                    true);
            AutoModpackServer.genModpack();
            context.getSource().sendFeedback(Text.literal("Modpack generated!")
                            .formatted(Formatting.GREEN),
                    true);
        });
        return 0;
    }
}
