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
    public static void register() {
        CommandRegistrationCallback.EVENT.register((dispatcher, registryAccess, environment) -> {
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
        context.getSource().sendFeedback(Text.literal("AutoModpack")
                .formatted(Formatting.GREEN)
                .append(Text.literal(" - " + AutoModpackMain.VERSION)
                        .formatted(Formatting.WHITE)
                ), false);

        return 1;
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
