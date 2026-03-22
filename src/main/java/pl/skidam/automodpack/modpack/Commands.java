package pl.skidam.automodpack.modpack;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.context.CommandContext;
/*? if >= 1.21.11 {*/
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
/*?}*/
import pl.skidam.automodpack.client.ui.versioned.VersionedCommandSource;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.auth.PlayerEndpointsStore;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import pl.skidam.automodpack_core.config.ConfigUtils;

import static net.minecraft.commands.Commands.literal;
import static pl.skidam.automodpack_core.Constants.*;

public class Commands {

    public static void register(CommandDispatcher<CommandSourceStack> dispatcher) {
        var automodpackNode = dispatcher.register(
                literal("automodpack")
                        .executes(Commands::about)
                        .then(literal("generate")
                                .requires((source) -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))
                                .executes(Commands::generateModpack)
                        )
                        .then(literal("host")
                                .requires((source) -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))
                                .executes(Commands::modpackHostAbout)
                                .then(literal("start")
                                        .requires((source) -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))
                                        .executes(Commands::startModpackHost)
                                )
                                .then(literal("stop")
                                        .requires((source) -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))
                                        .executes(Commands::stopModpackHost)
                                )
                                .then(literal("restart")
                                        .requires((source) -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))
                                        .executes(Commands::restartModpackHost)
                                )
                                .then(literal("connections")
                                        .requires((source) -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))
                                        .executes(Commands::connections)
                                )
                                .then(literal("endpoint")
                                        .requires((source) -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))
                                        .executes(Commands::endpoint)
                                )
                        )
                        .then(literal("config")
                                .requires((source) -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))
                                .then(literal("reload")
                                        .requires((source) -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))
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

    private static int endpoint(CommandContext<CommandSourceStack> context) {
        String endpointId = hostServer.getIrohEndpointId();
        if (endpointId != null && !endpointId.isBlank()) {
            MutableComponent endpointText = VersionedText.literal(endpointId).withStyle(style -> style
                    /*? if >=1.21.5 {*/
                    .withHoverEvent(new HoverEvent.ShowText(VersionedText.translatable("chat.copy.click")))
                    .withClickEvent(new ClickEvent.CopyToClipboard(endpointId)));
                    /*?} else {*/
                    /*.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, VersionedText.translatable("chat.copy.click")))
                    .withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, endpointId)));
            *//*?}*/
            send(context, "Iroh endpoint ID", ChatFormatting.WHITE, endpointText, ChatFormatting.YELLOW, false);
        } else {
            send(context, "Iroh endpoint ID is not available. Make sure iroh hosting started correctly.", ChatFormatting.RED, false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int connections(CommandContext<CommandSourceStack> context) {
        Util.backgroundExecutor().execute(() -> {
            var connectionCounts = hostServer.getConnectionCountsByEndpoint();
            var uniqueEndpoints = Set.copyOf(connectionCounts.keySet());
            int totalConnections = connectionCounts.values().stream().mapToInt(Integer::intValue).sum();

            send(context, String.format("Active connections: %d Unique endpoints: %d ", totalConnections, uniqueEndpoints.size()), ChatFormatting.YELLOW, false);

            for (String endpointId : uniqueEndpoints) {
                String playerId = PlayerEndpointsStore.getPlayerUuidForEndpoint(endpointId);
                int connNum = connectionCounts.getOrDefault(endpointId, 0);
                if (playerId == null) {
                    send(context, String.format("Endpoint %s is using %d connections", endpointId, connNum), ChatFormatting.GREEN, false);
                    continue;
                }

                var profile = GameHelpers.getPlayerProfile(playerId);
                send(context, String.format("Player: %s (%s) endpoint=%s is using %d connections", GameHelpers.getPlayerName(profile), playerId, endpointId, connNum), ChatFormatting.GREEN, false);
            }
        });

        return Command.SINGLE_SUCCESS;
    }

    private static int reload(CommandContext<CommandSourceStack> context) {
        Util.backgroundExecutor().execute(() -> {
            var tempServerConfig = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFieldsV2.class);
            if (tempServerConfig != null) {
                ConfigUtils.normalizeServerConfig(tempServerConfig, true);
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
        send(context, "Iroh transport", ChatFormatting.WHITE, hostServer.isIrohEnabled() ? "enabled" : "disabled", hostServer.isIrohEnabled() ? ChatFormatting.GREEN : ChatFormatting.RED, false);
        if (hostServer.getIrohEndpointId() != null) {
            send(context, "Iroh endpoint ID", ChatFormatting.WHITE, hostServer.getIrohEndpointId(), ChatFormatting.YELLOW, false);
        }
        return Command.SINGLE_SUCCESS;
    }

    private static int about(CommandContext<CommandSourceStack> context) {
        send(context, "AutoModpack", ChatFormatting.GREEN, AM_VERSION, ChatFormatting.WHITE, false);
        send(context, "/automodpack generate", ChatFormatting.YELLOW, false);
        send(context, "/automodpack host start/stop/restart/connections/endpoint", ChatFormatting.YELLOW, false);
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
