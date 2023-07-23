/*
 * This file is part of the AutoModpack project, licensed under the
 * GNU Lesser General Public License v3.0
 *
 * Copyright (C) 2023 Skidam and contributors
 *
 * AutoModpack is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Lesser General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * AutoModpack is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with AutoModpack.  If not, see <https://www.gnu.org/licenses/>.
 */

package pl.skidam.automodpack.modpack;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.context.CommandContext;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.Formatting;
import pl.skidam.automodpack.client.ui.versioned.VersionedCommandSource;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.config.Jsons;
import pl.skidam.automodpack.config.ConfigTools;

import java.util.concurrent.CompletableFuture;

import static net.minecraft.server.command.CommandManager.literal;
import static pl.skidam.automodpack.GlobalVariables.*;

//#if MC < 11902
import net.fabricmc.fabric.api.command.v1.CommandRegistrationCallback;
//#else
//$$ import net.fabricmc.fabric.api.command.v2.CommandRegistrationCallback;
//#endif

public class Commands {

    public static void register() {

//#if MC < 11902
        CommandRegistrationCallback.EVENT.register((dispatcher, dedicated) -> dispatcher.register(
//#else
//$$    CommandRegistrationCallback.EVENT.register((dispatcher, dedicated, environment) -> dispatcher.register(
//#endif
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
            serverConfig = ConfigTools.loadConfig(serverConfigFile, Jsons.ServerConfigFields.class);
            VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("AutoModpack server config reloaded!").formatted(Formatting.GREEN), true);
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int startModpackHost(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            if (!HttpServer.isRunning()) {
                VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Starting modpack hosting...")
                                .formatted(Formatting.YELLOW),
                        true);
                HttpServer.start();
                VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Modpack hosting started!")
                                .formatted(Formatting.GREEN),
                        true);
            } else {
                VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Modpack hosting is already running!")
                                .formatted(Formatting.RED),
                        false);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int stopModpackHost(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            if (HttpServer.isRunning()) {
                VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Stopping modpack hosting...")
                                .formatted(Formatting.RED),
                        true);
                HttpServer.stop();
                VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Modpack hosting stopped!")
                                .formatted(Formatting.RED),
                        true);
            } else {
                VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Modpack hosting is not running!")
                                .formatted(Formatting.RED),
                        false);
            }
        });
        return Command.SINGLE_SUCCESS;
    }

    private static int restartModpackHost(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {
            VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Restarting modpack hosting...")
                            .formatted(Formatting.YELLOW),
                    true);
            if (HttpServer.isRunning()) {
                HttpServer.stop();
                HttpServer.start();
                VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Modpack hosting restarted!")
                                .formatted(Formatting.GREEN),
                        true);
            } else if (serverConfig.modpackHost){
                HttpServer.start();
                VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Modpack hosting restarted!")
                                .formatted(Formatting.GREEN),
                        true);
            } else {
                VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Modpack hosting is disabled in config!")
                                .formatted(Formatting.RED),
                        false);
            }
        });
        return Command.SINGLE_SUCCESS;
    }


    private static int modpackHostAbout(CommandContext<ServerCommandSource> context) {
        Formatting statusColor = HttpServer.isRunning() ? Formatting.GREEN : Formatting.RED;
        String status = HttpServer.isRunning() ? "running" : "not running";
        VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Modpack hosting status")
                .formatted(Formatting.GREEN)
                .append(VersionedText.common.literal(" - ")
                        .formatted(Formatting.WHITE)
                        .append(VersionedText.common.literal(status)
                                .formatted(statusColor)
                        )
                ), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int about(CommandContext<ServerCommandSource> context) {
        VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("AutoModpack")
                .formatted(Formatting.GREEN)
                .append(VersionedText.common.literal(" - " + AM_VERSION)
                        .formatted(Formatting.WHITE)
                ), false);
        VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("/automodpack generate")
                .formatted(Formatting.YELLOW), false);
        VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("/automodpack host start/stop/restart")
                .formatted(Formatting.YELLOW), false);
        VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("/automodpack config reload")
                .formatted(Formatting.YELLOW), false);
        return Command.SINGLE_SUCCESS;
    }

    private static int generateModpack(CommandContext<ServerCommandSource> context) {
        CompletableFuture.runAsync(() -> {

            if (Modpack.isGenerating()) {
                VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Modpack is already generating! Please wait!")
                                .formatted(Formatting.RED),
                        false);
                return;
            }

            VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Generating Modpack...")
                            .formatted(Formatting.YELLOW),
                    true);
            long start = System.currentTimeMillis();
            if (Modpack.generate()) {
                VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Modpack generated! took " + (System.currentTimeMillis() - start) + "ms")
                                .formatted(Formatting.GREEN),
                        true);
            } else {
                VersionedCommandSource.sendFeedback(context, VersionedText.common.literal("Modpack generation failed! Check logs for more info.")
                                .formatted(Formatting.RED),
                        true);
            }
        });
        return Command.SINGLE_SUCCESS;
    }
}
