package pl.skidam.automodpack.server;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.LiteralText;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.AutoModpackMain;
import pl.skidam.automodpack.AutoModpackServer;

import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.literal;
public class Commands {
    public static void register() { // TODO config server reload command
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> {
            dispatcher.register(
                literal("automodpack")
                    .executes(Commands::about)
                    .then(literal("generate-modpack")
                            .requires((source) -> source.hasPermissionLevel(2))
                            .executes(Commands::generateModpack)
                    )
                    .then(literal("modpack-host")
                            .requires((source) -> source.hasPermissionLevel(2))
                            .executes(Commands::modpackHostAbuot)
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
            );
        });
    }

    private static int startModpackHost(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            context.getSource().sendFeedback(new LiteralText("Starting modpack hosting...")
                            .formatted(Formatting.YELLOW),
                    true);
            boolean isRunning = HostModpack.server != null;
            if (!isRunning) {
                HostModpack.start();
                context.getSource().sendFeedback(new LiteralText("Modpack hosting started!")
                                .formatted(Formatting.GREEN),
                        true);
            } else {
                context.getSource().sendFeedback(new LiteralText("Modpack hosting is already running!")
                    .formatted(Formatting.RED),
            true);
            }
        });
        return 0;

    }

    private static int stopModpackHost(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            context.getSource().sendFeedback(new LiteralText("Stopping modpack hosting...")
                            .formatted(Formatting.RED),
                    true);
            boolean isRunning = HostModpack.server != null;
            if (isRunning) {
                HostModpack.stop();
                context.getSource().sendFeedback(new LiteralText("Modpack hosting stopped!")
                                .formatted(Formatting.RED),
                        true);
            } else {
                context.getSource().sendFeedback(new LiteralText("Modpack hosting is not running!")
                    .formatted(Formatting.RED),
            true);
            }
        });
        return 0;
    }

    private static int restartModpackHost(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            context.getSource().sendFeedback(new LiteralText("Restarting modpack hosting...")
                            .formatted(Formatting.YELLOW),
                    true);
            boolean isRunning = HostModpack.server != null;
            if (isRunning) {
                HostModpack.stop();
            }
            HostModpack.start();
            context.getSource().sendFeedback(new LiteralText("Modpack hosting restarted!")
                            .formatted(Formatting.GREEN),
                    true);
        });
        return 0;
    }


    private static int modpackHostAbuot(CommandContext<ServerCommandSource> context) {
        boolean isRunning = HostModpack.server != null;
        Formatting statusColor = isRunning ? Formatting.GREEN : Formatting.RED;
        context.getSource().sendFeedback(new LiteralText("Modpack hosting status")
                .formatted(Formatting.GREEN)
                .append(new LiteralText(" - ")
                        .formatted(Formatting.WHITE)
                        .append(new LiteralText( "" + isRunning)
                                .formatted(statusColor)
                        )
                ), false);
        context.getSource().sendFeedback(new LiteralText("/automodpack modpack-host start")
                .formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(new LiteralText("/automodpack modpack-host stop")
                .formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(new LiteralText("/automodpack modpack-host restart")
                .formatted(Formatting.YELLOW), false);
        return 0;
    }

    private static int about(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(new LiteralText("AutoModpack")
                .formatted(Formatting.GREEN)
                .append(new LiteralText(" - " + AutoModpackMain.VERSION)
                        .formatted(Formatting.WHITE)
                ), false);

        return 0;
    }

    public static int generateModpack(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            context.getSource().sendFeedback(new LiteralText("Generating Modpack...")
                    .formatted(Formatting.GREEN),
                    true);
            AutoModpackServer.genModpack();
            context.getSource().sendFeedback(new LiteralText("Modpack generated!")
                    .formatted(Formatting.GREEN),
                    true);
        });
        return 0;
    }
}
