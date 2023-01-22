package pl.skidam.automodpack.modpack;

import com.mojang.brigadier.context.CommandContext;
import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.AutoModpack;
import pl.skidam.automodpack.config.Config;
import pl.skidam.automodpack.config.ConfigTools;

import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.literal;

public class Commands {
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> dispatcher.register(
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
        ));
    }

    private static int reload(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            AutoModpack.serverConfig = ConfigTools.loadConfig(AutoModpack.serverConfigFile, Config.ServerConfigFields.class);
            context.getSource().sendFeedback(Text.of("AutoModpack server config reloaded!"), true);
        });
        return 0;
    }

    private static int startModpackHost(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            if (!Modpack.Host.isRunning) {
                context.getSource().sendFeedback(Text.literal("Starting modpack hosting...")
                                .formatted(Formatting.YELLOW),
                        true);
                Modpack.Host.start();
                context.getSource().sendFeedback(Text.literal("Modpack hosting started!")
                                .formatted(Formatting.GREEN),
                        true);
            } else {
                context.getSource().sendFeedback(Text.literal("Modpack hosting is already running!")
                                .formatted(Formatting.RED),
                        false);
            }
        });
        return 0;

    }

    private static int stopModpackHost(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            if (Modpack.Host.isRunning) {
                context.getSource().sendFeedback(Text.literal("Stopping modpack hosting...")
                                .formatted(Formatting.RED),
                        true);
                Modpack.Host.stop();
                context.getSource().sendFeedback(Text.literal("Modpack hosting stopped!")
                                .formatted(Formatting.RED),
                        true);
            } else {
                context.getSource().sendFeedback(Text.literal("Modpack hosting is not running!")
                                .formatted(Formatting.RED),
                        false);
            }
        });
        return 0;
    }

    private static int restartModpackHost(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            context.getSource().sendFeedback(Text.literal("Restarting modpack hosting...")
                            .formatted(Formatting.YELLOW),
                    true);
            if (Modpack.Host.isRunning) {
                Modpack.Host.stop();
                Modpack.Host.start();
            } else if (AutoModpack.serverConfig.modpackHost){
                Modpack.Host.start();
                context.getSource().sendFeedback(Text.literal("Modpack hosting restarted!")
                                .formatted(Formatting.GREEN),
                        true);
            } else {
                context.getSource().sendFeedback(Text.literal("Modpack hosting is disabled in config!")
                                .formatted(Formatting.RED),
                        false);
            }
        });
        return 0;
    }


    private static int modpackHostAbout(CommandContext<ServerCommandSource> context) {
        Formatting statusColor = Modpack.Host.isRunning ? Formatting.GREEN : Formatting.RED;
        String status = Modpack.Host.isRunning ? "running" : "not running";
        context.getSource().sendFeedback(Text.literal("Modpack hosting status")
                .formatted(Formatting.GREEN)
                .append(Text.literal(" - ")
                        .formatted(Formatting.WHITE)
                        .append(Text.literal(status)
                                .formatted(statusColor)
                        )
                ), false);
        context.getSource().sendFeedback(Text.literal("/automodpack generate")
                .formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(Text.literal("/automodpack host start/stop/restart")
                .formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(Text.literal("/automodpack config reload")
                .formatted(Formatting.YELLOW), false);
        return 0;
    }

    private static int about(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(Text.literal("AutoModpack")
                .formatted(Formatting.GREEN)
                .append(Text.literal(" - " + AutoModpack.VERSION)
                        .formatted(Formatting.WHITE)
                ), false);
        context.getSource().sendFeedback(Text.literal("/automodpack host")
                .formatted(Formatting.YELLOW), false);
        context.getSource().sendFeedback(Text.literal("/automodpack config reload")
                .formatted(Formatting.YELLOW), false);
        return 0;
    }

    private static int generateModpack(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            context.getSource().sendFeedback(Text.literal("Generating Modpack...")
                            .formatted(Formatting.GREEN),
                    true);
            Modpack.generate();
            context.getSource().sendFeedback(Text.literal("Modpack generated!")
                            .formatted(Formatting.GREEN),
                    true);
        });
        return 0;
    }
}
