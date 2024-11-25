package pl.skidam.automodpack_loader_velocity;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.tree.LiteralCommandNode;
import com.velocitypowered.api.command.BrigadierCommand;
import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;

public class Commands {

    public static BrigadierCommand register(final ProxyServer proxy) {
        LiteralCommandNode<CommandSource> commandNode = BrigadierCommand.literalArgumentBuilder("automodpack")
                .executes(context -> {
                    CommandSource source = context.getSource();
                    Component message = Component.text("AutoModpack", NamedTextColor.GREEN);
                    source.sendMessage(message);
                    return Command.SINGLE_SUCCESS;
                })
                .then(BrigadierCommand.literalArgumentBuilder("generate")
                        .requires(source -> source.hasPermission("automodpack.generate"))
                        .executes(context -> {
                            CommandSource source = context.getSource();
                            // Add your generateModpack logic here
                            source.sendMessage(Component.text("Generating Modpack...", NamedTextColor.YELLOW));
                            return Command.SINGLE_SUCCESS;
                        })
                )
                .then(BrigadierCommand.literalArgumentBuilder("host")
                        .requires(source -> source.hasPermission("automodpack.host"))
                        .executes(context -> {
                            CommandSource source = context.getSource();
                            // Add your modpackHostAbout logic here
                            source.sendMessage(Component.text("Modpack hosting status", NamedTextColor.GREEN));
                            return Command.SINGLE_SUCCESS;
                        })
                        .then(BrigadierCommand.literalArgumentBuilder("start")
                                .requires(source -> source.hasPermission("automodpack.host.start"))
                                .executes(context -> {
                                    CommandSource source = context.getSource();
                                    // Add your startModpackHost logic here
                                    source.sendMessage(Component.text("Starting modpack hosting...", NamedTextColor.YELLOW));
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(BrigadierCommand.literalArgumentBuilder("stop")
                                .requires(source -> source.hasPermission("automodpack.host.stop"))
                                .executes(context -> {
                                    CommandSource source = context.getSource();
                                    // Add your stopModpackHost logic here
                                    source.sendMessage(Component.text("Stopping modpack hosting...", NamedTextColor.RED));
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                        .then(BrigadierCommand.literalArgumentBuilder("restart")
                                .requires(source -> source.hasPermission("automodpack.host.restart"))
                                .executes(context -> {
                                    CommandSource source = context.getSource();
                                    // Add your restartModpackHost logic here
                                    source.sendMessage(Component.text("Restarting modpack hosting...", NamedTextColor.YELLOW));
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .then(BrigadierCommand.literalArgumentBuilder("config")
                        .requires(source -> source.hasPermission("automodpack.config"))
                        .then(BrigadierCommand.literalArgumentBuilder("reload")
                                .requires(source -> source.hasPermission("automodpack.config.reload"))
                                .executes(context -> {
                                    CommandSource source = context.getSource();
                                    // Add your reload logic here
                                    source.sendMessage(Component.text("AutoModpack server config reloaded!", NamedTextColor.GREEN));
                                    return Command.SINGLE_SUCCESS;
                                })
                        )
                )
                .build();

        return new BrigadierCommand(commandNode);
    }


// TODO: implement the following methods

//    private static int reload(CommandSource context) {
//        Util.getMainWorkerExecutor().execute(() -> {
//            serverConfig = ConfigTools.load(serverConfigFile, Jsons.ServerConfigFields.class);
//            send(context, "AutoModpack server config reloaded!", Formatting.GREEN, true);
//        });
//
//        return Command.SINGLE_SUCCESS;
//    }
//
//    private static int startModpackHost(CommandContext<ServerCommandSource> context) {
//        Util.getMainWorkerExecutor().execute(() -> {
//            if (!httpServer.shouldRunInternally()) {
//                send(context, "Starting modpack hosting...", Formatting.YELLOW, true);
//                httpServer.start();
//                if (httpServer.shouldRunInternally()) {
//                    send(context, "Modpack hosting started!", Formatting.GREEN, true);
//                } else {
//                    send(context, "Couldn't start server!", Formatting.RED, true);
//                }
//            } else {
//                send(context, "Modpack hosting is already running!", Formatting.RED, false);
//            }
//        });
//
//        return Command.SINGLE_SUCCESS;
//    }
//
//    private static int stopModpackHost(CommandContext<ServerCommandSource> context) {
//        Util.getMainWorkerExecutor().execute(() -> {
//            if (httpServer.shouldRunInternally()) {
//                send(context, "Stopping modpack hosting...", Formatting.RED, true);
//                if (httpServer.stop()) {
//                    send(context, "Modpack hosting stopped!", Formatting.RED, true);
//                } else {
//                    send(context, "Couldn't stop server!", Formatting.RED, true);
//                }
//            } else {
//                send(context, "Modpack hosting is not running!", Formatting.RED, false);
//            }
//        });
//
//        return Command.SINGLE_SUCCESS;
//    }
//
//    private static int restartModpackHost(CommandContext<ServerCommandSource> context) {
//        Util.getMainWorkerExecutor().execute(() -> {
//            send(context, "Restarting modpack hosting...", Formatting.YELLOW, true);
//            boolean needStop = httpServer.shouldRunInternally();
//            boolean stopped = false;
//            if (needStop) {
//                stopped = httpServer.stop();
//            }
//
//            if (needStop && !stopped) {
//                send(context, "Couldn't restart server!", Formatting.RED, true);
//            } else {
//                httpServer.start();
//                if (httpServer.shouldRunInternally()) {
//                    send(context, "Modpack hosting restarted!", Formatting.GREEN, true);
//                } else {
//                    send(context, "Couldn't restart server!", Formatting.RED, true);
//                }
//            }
//        });
//
//        return Command.SINGLE_SUCCESS;
//    }
//
//
//    private static int modpackHostAbout(CommandContext<ServerCommandSource> context) {
//        Formatting statusColor = httpServer.shouldRunInternally() ? Formatting.GREEN : Formatting.RED;
//        String status = httpServer.shouldRunInternally() ? "running" : "not running";
//        send(context, "Modpack hosting status", Formatting.GREEN, status, statusColor, false);
//        return Command.SINGLE_SUCCESS;
//    }
//
//    private static int about(CommandContext<ServerCommandSource> context) {
//        send(context, "AutoModpack", Formatting.GREEN, AM_VERSION, Formatting.WHITE, false);
//        send(context, "/automodpack generate", Formatting.YELLOW, false);
//        send(context, "/automodpack host start/stop/restart", Formatting.YELLOW, false);
//        send(context, "/automodpack config reload", Formatting.YELLOW, false);
//        return Command.SINGLE_SUCCESS;
//    }
//
//    private static int generateModpack(CommandContext<ServerCommandSource> context) {
//        Util.getMainWorkerExecutor().execute(() -> {
//            if (modpack.isGenerating()) {
//                send(context, "Modpack is already generating! Please wait!", Formatting.RED, false);
//                return;
//            }
//            send(context, "Generating Modpack...", Formatting.YELLOW, true);
//            long start = System.currentTimeMillis();
//            if (modpack.generateNew()) {
//                send(context, "Modpack generated! took " + (System.currentTimeMillis() - start) + "ms", Formatting.GREEN, true);
//            } else {
//                send(context, "Modpack generation failed! Check logs for more info.", Formatting.RED, true);
//            }
//        });
//
//        return Command.SINGLE_SUCCESS;
//    }
//
//    private static void send(CommandContext<ServerCommandSource> context, String msg, Formatting msgColor, boolean broadcast) {
//        VersionedCommandSource.sendFeedback(context,
//                VersionedText.literal(msg)
//                        .formatted(msgColor),
//                broadcast);
//    }
//
//    private static void send(CommandContext<ServerCommandSource> context, String msg, Formatting msgColor, String appendMsg, Formatting appendMsgColor, boolean broadcast) {
//        VersionedCommandSource.sendFeedback(context,
//                VersionedText.literal(msg)
//                    .formatted(msgColor)
//                    .append(VersionedText.literal(" - ")
//                            .formatted(Formatting.WHITE))
//                    .append(VersionedText.literal(appendMsg)
//                            .formatted(appendMsgColor)),
//                broadcast);
//    }
}
