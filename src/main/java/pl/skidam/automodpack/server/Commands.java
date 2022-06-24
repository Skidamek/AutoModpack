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
            );
        });
    }

    private static int about(CommandContext<ServerCommandSource> context) {
        context.getSource().sendFeedback(new LiteralText("AutoModpack")
                .formatted(Formatting.GREEN)
                .append(new LiteralText(" - " + AutoModpackMain.VERSION)
                        .formatted(Formatting.WHITE)
                ), false);

        return 1;
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
