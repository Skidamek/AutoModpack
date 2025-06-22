package pl.skidam.automodpack.modpack;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import com.mojang.brigadier.tree.LiteralCommandNode;
import pl.skidam.automodpack.client.ui.versioned.VersionedCommandSource;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.config.Jsons.ServerConfigFieldsV2;
import java.util.Map;
import java.util.Map.Entry;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;

import static net.minecraft.commands.Commands.literal;
import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Commands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var automodpackNode = dispatcher.register(
                literal("automodpack")
                        .executes(Commands::about)
                        .then(literal("generate")
                                .requires((source) -> source.hasPermission(3))
                                .executes(Commands::generateModpack)
                        )
                        .then(literal("host")
                                .requires((source) -> source.hasPermission(3))
                                .executes(Commands::modpackHostAbout)
                                .then(literal("start")
                                        .requires((source) -> source.hasPermission(3))
                                        .executes(Commands::startModpackHost)
                                )
                                .then(literal("stop")
                                        .requires((source) -> source.hasPermission(3))
                                        .executes(Commands::stopModpackHost)
                                )
                                .then(literal("restart")
                                        .requires((source) -> source.hasPermission(3))
                                        .executes(Commands::restartModpackHost)
                                )
                                .then(literal("connections")
                                        .requires((source) -> source.hasPermission(3))
                                        .executes(Commands::connections)
                                )
                                .then(literal("fingerprint")
                                        .requires((source) -> source.hasPermission(3))
                                        .executes(Commands::fingerprint)
                                )
                        )
                        .then(literal("config")
                                .requires((source) -> source.hasPermission(3))
                                .then(literal("reload")
                                        .requires((source) -> source.hasPermission(3))
                                        .executes(Commands::reload)
                                )
                        )
        );

        dispatcher.register(
                literal("amp")
                        .executes(Commands::about)
                        .redirect(automodpackNode)
        );
    }

    private static int fingerprint(CommandContext<CommandSourceStack> context) {
        String fingerprint = hostServer.getCertificateFingerprint();
        if (fingerprint != null) {
            MutableComponent fingerprintText = VersionedText.literal(fingerprint).withStyle(style -> style
                    /*? if >1.21.5 {*/
                    .withHoverEvent(new HoverEvent.ShowText(VersionedText.translatable("chat.copy.click")))
                    .withClickEvent(new ClickEvent.CopyToClipboard(fingerprint)));
                     /*?} else {*/
                    /*.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, VersionedText.translatable("chat.copy.click")))
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, fingerprint)));
            *//*?}*/
            send(context, "Certificate fingerprint", ChatFormatting.WHITE, fingerprintText, ChatFormatting.YELLOW, false);
        } else {
            send(context, "Certificate fingerprint is not available. Make sure the server is running with TLS enabled.", ChatFormatting.RED, false);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int connections(CommandContext<CommandSourceStack> context) {
        Util.backgroundExecutor().execute(() -> {
            var connections = hostServer.getConnections();
            var uniqueSecrets = Set.copyOf(connections.values());

            send(context, String.format("Active connections: %d Unique connections: %d ", connections.size(), uniqueSecrets.size()), ChatFormatting.YELLOW, false);

            for (String secret : uniqueSecrets) {
                var playerSecretPair = SecretsStore.getHostSecret(secret);
                if (playerSecretPair == null) continue;

                String playerId = playerSecretPair.getKey();
                GameProfile profile = GameHelpers.getPlayerProfile(playerId);

                long connNum = connections.values().stream().filter(secret::equals).count();

                send(context, String.format("Player: %s (%s) is downloading modpack using %d connections", profile.getName(), playerId, connNum), ChatFormatting.GREEN, false);
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        Util.backgroundExecutor().execute(() -> {
            var tempServerConfig = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFieldsV2.class);
            if (tempServerConfig != null) {
                serverConfig = tempServerConfig;
                send(context, "AutoModpack server config reloaded!", ChatFormatting.GREEN, true);
            } else {
                send(context, "Error while reloading config file!", ChatFormatting.RED, true);
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static int startModpackHost(CommandContext<CommandSourceStack> context) {
        Util.backgroundExecutor().execute(() -> {
            if (!hostServer.isRunning()) {
                send(context, "Starting modpack hosting...", ChatFormatting.YELLOW, true);
                hostServer.start();
                if (hostServer.isRunning()) {
                    send(context, "Modpack hosting started!", ChatFormatting.GREEN, true);
                } else {
                    send(context, "Couldn't start server!", ChatFormatting.RED, true);
                }
            } else {
                send(context, "Modpack hosting is already running!", ChatFormatting.RED, false);
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static int stopModpackHost(CommandContext<CommandSourceStack> context) {
        Util.backgroundExecutor().execute(() -> {
            if (hostServer.isRunning()) {
                send(context, "Stopping modpack hosting...", ChatFormatting.RED, true);
                if (hostServer.stop()) {
                    send(context, "Modpack hosting stopped!", ChatFormatting.RED, true);
                } else {
                    send(context, "Couldn't stop server!", ChatFormatting.RED, true);
                }
            } else {
                send(context, "Modpack hosting is not running!", ChatFormatting.RED, false);
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static int restartModpackHost(CommandContext<CommandSourceStack> context) {
        Util.backgroundExecutor().execute(() -> {
            send(context, "Restarting modpack hosting...", ChatFormatting.YELLOW, true);
            boolean needStop = hostServer.isRunning();
            boolean stopped = false;
            if (needStop) {
                stopped = hostServer.stop();
            }

            if (needStop && !stopped) {
                send(context, "Couldn't restart server!", ChatFormatting.RED, true);
            } else {
                hostServer.start();
                if (hostServer.isRunning()) {
                    send(context, "Modpack hosting restarted!", ChatFormatting.GREEN, true);
                } else {
                    send(context, "Couldn't restart server!", ChatFormatting.RED, true);
                }
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static int modpackHostAbout(CommandContext<CommandSourceStack> context) {
        ChatFormatting statusColor = hostServer.isRunning() ? ChatFormatting.GREEN : ChatFormatting.RED;
        String status = hostServer.isRunning() ? "running" : "not running";
        send(context, "Modpack hosting status", ChatFormatting.GREEN, status, statusColor, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int about(CommandContext<CommandSourceStack> context) {
        send(context, "AutoModpack", ChatFormatting.GREEN, AM_VERSION, ChatFormatting.WHITE, false);
        send(context, "/automodpack generate", ChatFormatting.YELLOW, false);
        send(context, "/automodpack host start/stop/restart/connections/fingerprint", ChatFormatting.YELLOW, false);
        send(context, "/automodpack config reload", ChatFormatting.YELLOW, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateModpack(CommandContext<CommandSourceStack> context) {
        Util.backgroundExecutor().execute(() -> {
            if (modpackExecutor.isGenerating()) {
                send(context, "Modpack is already generating! Please wait!", ChatFormatting.RED, false);
                return;
            }
            send(context, "Generating Modpack...", ChatFormatting.YELLOW, true);
            long start = System.currentTimeMillis();
            if (modpackExecutor.generateNew()) {
                send(context, "Modpack generated! took " + (System.currentTimeMillis() - start) + "ms", ChatFormatting.GREEN, true);
            } else {
                send(context, "Modpack generation failed! Check logs for more info.", ChatFormatting.RED, true);
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static void send(CommandContext<CommandSourceStack> context, String msg, ChatFormatting msgColor, boolean broadcast) {
        VersionedCommandSource.sendFeedback(context,
                VersionedText.literal(msg)
                        .withStyle(msgColor),
                broadcast);
    }

    private static void send(CommandContext<CommandSourceStack> context, String msg, ChatFormatting msgColor, String appendMsg, ChatFormatting appendMsgColor, boolean broadcast) {
        VersionedCommandSource.sendFeedback(context,
                VersionedText.literal(msg)
                        .withStyle(msgColor)
                        .append(VersionedText.literal(" - ")
                                .withStyle(ChatFormatting.WHITE))
                        .append(VersionedText.literal(appendMsg)
                                .withStyle(appendMsgColor)),
                broadcast);
    }

    private static void send(CommandContext<CommandSourceStack> context, String msg, ChatFormatting msgColor, MutableComponent appendMsg, ChatFormatting appendMsgColor, boolean broadcast) {
        VersionedCommandSource.sendFeedback(context,
                VersionedText.literal(msg)
                        .withStyle(msgColor)
                        .append(VersionedText.literal(" - ")
                                .withStyle(ChatFormatting.WHITE))
                        .append(appendMsg
                                .withStyle(appendMsgColor)),
                broadcast);
    }
}
