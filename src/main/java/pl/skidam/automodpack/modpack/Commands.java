package pl.skidam.automodpack.modpack;

import com.mojang.authlib.GameProfile;
import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.text.ClickEvent;
import net.minecraft.text.HoverEvent;
import net.minecraft.text.MutableText;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import pl.skidam.automodpack.client.ui.versioned.VersionedCommandSource;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

import java.util.Set;

import static net.minecraft.server.command.CommandManager.literal;
import static pl.skidam.automodpack_core.GlobalVariables.*;

public class Commands {

    public static void register(CommandDispatcher<ServerCommandSource> dispatcher) {
        var automodpackNode = dispatcher.register(
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
                                .then(literal("connections")
                                        .requires((source) -> source.hasPermissionLevel(3))
                                        .executes(Commands::connections)
                                )
                                .then(literal("fingerprint"))
                                .requires((source) -> source.hasPermissionLevel(3))
                                .executes(Commands::fingerprint)
                        )
                        .then(literal("config")
                                .requires((source) -> source.hasPermissionLevel(3))
                                .then(literal("reload")
                                        .requires((source) -> source.hasPermissionLevel(3))
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

    private static int fingerprint(CommandContext<ServerCommandSource> context) {
        String fingerprint = hostServer.getCertificateFingerprint();
        if (fingerprint != null) {
            MutableText fingerprintText = VersionedText.literal(fingerprint).styled(style -> style
                    /*? if >1.21.4 {*/
                    /*.withHoverEvent(new HoverEvent.ShowText, VersionedText.translatable("chat.copy.click"))
                    /*.withClickEvent(new ClickEvent.CopyToClipboard(fingerprint)));
                     *//*?} else {*/
                    .withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, VersionedText.translatable("chat.copy.click")))
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, fingerprint)));
            /*?}*/
            send(context, "Certificate fingerprint", Formatting.WHITE, fingerprintText, Formatting.YELLOW, false);
        } else {
            send(context, "Certificate fingerprint is not available. Make sure the server is running with TLS enabled.", Formatting.RED, false);
        }

        return Command.SINGLE_SUCCESS;
    }

    private static int connections(CommandContext<ServerCommandSource> context) {
        Util.getMainWorkerExecutor().execute(() -> {
            var connections = hostServer.getConnections();
            var uniqueSecrets = Set.copyOf(connections.values());

            send(context, String.format("Active connections: %d Unique connections: %d ", connections.size(), uniqueSecrets.size()), Formatting.YELLOW, false);

            for (String secret : uniqueSecrets) {
                var playerSecretPair = SecretsStore.getHostSecret(secret);
                if (playerSecretPair == null) continue;

                String playerId = playerSecretPair.getKey();
                GameProfile profile = GameHelpers.getPlayerProfile(playerId);

                long connNum = connections.values().stream().filter(secret::equals).count();

                send(context, String.format("Player: %s (%s) is downloading modpack using %d connections", profile.getName(), playerId, connNum), Formatting.GREEN, false);
            }
        });

        return Command.SINGLE_SUCCESS;
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
            if (!hostServer.isRunning()) {
                send(context, "Starting modpack hosting...", Formatting.YELLOW, true);
                hostServer.start();
                if (hostServer.isRunning()) {
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
            if (hostServer.isRunning()) {
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
            boolean needStop = hostServer.isRunning();
            boolean stopped = false;
            if (needStop) {
                stopped = hostServer.stop();
            }

            if (needStop && !stopped) {
                send(context, "Couldn't restart server!", Formatting.RED, true);
            } else {
                hostServer.start();
                if (hostServer.isRunning()) {
                    send(context, "Modpack hosting restarted!", Formatting.GREEN, true);
                } else {
                    send(context, "Couldn't restart server!", Formatting.RED, true);
                }
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static int modpackHostAbout(CommandContext<ServerCommandSource> context) {
        Formatting statusColor = hostServer.isRunning() ? Formatting.GREEN : Formatting.RED;
        String status = hostServer.isRunning() ? "running" : "not running";
        send(context, "Modpack hosting status", Formatting.GREEN, status, statusColor, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int about(CommandContext<ServerCommandSource> context) {
        send(context, "AutoModpack", Formatting.GREEN, AM_VERSION, Formatting.WHITE, false);
        send(context, "/automodpack generate", Formatting.YELLOW, false);
        send(context, "/automodpack host start/stop/restart/connections/fingerprint", Formatting.YELLOW, false);
        send(context, "/automodpack config reload", Formatting.YELLOW, false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateModpack(CommandContext<ServerCommandSource> context) {
        Util.getMainWorkerExecutor().execute(() -> {
            if (modpackExecutor.isGenerating()) {
                send(context, "Modpack is already generating! Please wait!", Formatting.RED, false);
                return;
            }
            send(context, "Generating Modpack...", Formatting.YELLOW, true);
            long start = System.currentTimeMillis();
            if (modpackExecutor.generateNew()) {
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

    private static void send(CommandContext<ServerCommandSource> context, String msg, Formatting msgColor, MutableText appendMsg, Formatting appendMsgColor, boolean broadcast) {
        VersionedCommandSource.sendFeedback(context,
                VersionedText.literal(msg)
                        .formatted(msgColor)
                        .append(VersionedText.literal(" - ")
                                .formatted(Formatting.WHITE))
                        .append(appendMsg
                                .formatted(appendMsgColor)),
                broadcast);
    }
}
