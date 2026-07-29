package pl.skidam.automodpack.modpack;

import com.mojang.brigadier.Command;
import com.mojang.brigadier.CommandDispatcher;
import com.mojang.brigadier.arguments.StringArgumentType;
import com.mojang.brigadier.context.CommandContext;
/*? if >= 1.21.11 {*/
import net.minecraft.server.permissions.Permission;
import net.minecraft.server.permissions.PermissionLevel;
/*?}*/
import pl.skidam.automodpack.client.ui.versioned.VersionedCommandSource;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.auth.DnsPinResolver;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.auth.ServerAddressPin;
import pl.skidam.automodpack_core.config.BootstrapConfig;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.utils.AddressHelpers;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import net.minecraft.ChatFormatting;
import net.minecraft.util.Util;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.network.chat.ClickEvent;
import net.minecraft.network.chat.HoverEvent;
import net.minecraft.network.chat.MutableComponent;
import pl.skidam.automodpack_core.config.ConfigUtils;

import static net.minecraft.commands.Commands.argument;
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
								.then(literal("fingerprint")
										.requires((source) -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))
										.executes(Commands::fingerprint)
										.then(literal("dns")
												.executes(Commands::fingerprintDnsUsage)
												.then(argument("minecraft-hostname", StringArgumentType.word())
														.executes(Commands::fingerprintDnsRecord)
												)
										)
										.then(literal("share")
												.executes(Commands::fingerprintShareUsage)
												.then(argument("minecraft-address", StringArgumentType.greedyString())
														.executes(Commands::fingerprintShareAddress)
												)
										)
								)
								.then(literal("bootstrap")
										.then(literal("pin")
												.then(argument("origin", StringArgumentType.word())
														.executes(Commands::bootstrapPin)
												)
										)
										.then(literal("install")
												.then(argument("origin", StringArgumentType.word())
														.executes(Commands::bootstrapInstallConfiguredEndpoint)
														.then(argument("endpoint", StringArgumentType.word())
																.then(argument("connection-mode", StringArgumentType.word())
																		.executes(Commands::bootstrapInstallExplicitEndpoint)
																)
														)
												)
										)
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

	private static int fingerprint(CommandContext<CommandSourceStack> context) {
		String fingerprint = hostServer.getCertificateFingerprint();
		if (fingerprint != null) {
			send(context, "Certificate fingerprint", ChatFormatting.WHITE, copyable(fingerprint), ChatFormatting.YELLOW, false);
		} else {
			send(context, "Certificate fingerprint is not available. Make sure the server is running with TLS enabled.", ChatFormatting.RED, false);
		}

		return Command.SINGLE_SUCCESS;
	}

	private static int fingerprintDnsUsage(CommandContext<CommandSourceStack> context) {
		send(context, "Usage: /automodpack host fingerprint dns <minecraft-hostname>", ChatFormatting.RED, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int fingerprintDnsRecord(CommandContext<CommandSourceStack> context) {
		String fingerprint = hostServer.getCertificateFingerprint();
		if (fingerprint == null) {
			send(context, "Certificate fingerprint is not available. Make sure the server is running with TLS enabled.", ChatFormatting.RED, false);
			return Command.SINGLE_SUCCESS;
		}

		final String record;
		try {
			record = DnsPinResolver.formatRecord(
					StringArgumentType.getString(context, "minecraft-hostname"), fingerprint);
		} catch (IllegalArgumentException e) {
			send(context, e.getMessage(), ChatFormatting.RED, false);
			return Command.SINGLE_SUCCESS;
		}

		send(context, "Publish this record in the DNSSEC-signed zone for the Minecraft hostname players use.", ChatFormatting.WHITE, copyable(record),
				ChatFormatting.YELLOW, false);

		return Command.SINGLE_SUCCESS;
	}

	private static int fingerprintShareUsage(CommandContext<CommandSourceStack> context) {
		send(context, "Usage: /automodpack host fingerprint share <minecraft-address>", ChatFormatting.RED, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int fingerprintShareAddress(CommandContext<CommandSourceStack> context) {
		String fingerprint = hostServer.getCertificateFingerprint();
		if (fingerprint == null) {
			send(context, "Certificate fingerprint is not available. Make sure the server is running with TLS enabled.", ChatFormatting.RED, false);
			return Command.SINGLE_SUCCESS;
		}

		String origin = StringArgumentType.getString(context, "minecraft-address");
		final String pinnedOrigin;
		try {
			pinnedOrigin = ServerAddressPin.format(origin, fingerprint);
		} catch (IllegalArgumentException e) {
			send(context, e.getMessage(), ChatFormatting.RED, false);
			return Command.SINGLE_SUCCESS;
		}

		send(context, "Plain Minecraft origin (vanilla and older clients):", ChatFormatting.WHITE, copyable(origin), ChatFormatting.YELLOW, false);
		send(context, "Pinned AutoModpack origin:", ChatFormatting.WHITE, copyable(pinnedOrigin), ChatFormatting.GREEN, false);
		send(context, "Compatible AutoModpack clients import the public fingerprint and save a clean Minecraft origin.", ChatFormatting.GRAY, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int bootstrapPin(CommandContext<CommandSourceStack> context) {
		try {
			InetSocketAddress origin = AddressHelpers.parseOrigin(StringArgumentType.getString(context, "origin"));
			return writeBootstrap(context, BootstrapConfig.pin(origin, requireBootstrapFingerprint()), false);
		} catch (IllegalArgumentException e) {
			send(context, e.getMessage(), ChatFormatting.RED, false);
			return 0;
		}
	}

	private static int bootstrapInstallConfiguredEndpoint(CommandContext<CommandSourceStack> context) {
		try {
			if (serverConfig.advertisedEndpointHost == null || serverConfig.advertisedEndpointHost.isBlank()
					|| serverConfig.advertisedEndpointPort == -1)
				throw new IllegalArgumentException("Configured bootstrap install requires explicit advertisedEndpointHost and advertisedEndpointPort values");
			InetSocketAddress origin = AddressHelpers.parseOrigin(StringArgumentType.getString(context, "origin"));
			InetSocketAddress endpoint = AddressHelpers.parseEndpoint(
					AddressHelpers.formatAddress(AddressHelpers.format(serverConfig.advertisedEndpointHost, serverConfig.advertisedEndpointPort)));
			return writeBootstrap(context,
					BootstrapConfig.install(origin, requireBootstrapFingerprint(), requirePublishedModpackId(), endpoint, serverConfig.connectionMode), true);
		} catch (IllegalArgumentException e) {
			send(context, e.getMessage(), ChatFormatting.RED, false);
			return 0;
		}
	}

	private static int bootstrapInstallExplicitEndpoint(CommandContext<CommandSourceStack> context) {
		try {
			InetSocketAddress origin = AddressHelpers.parseOrigin(StringArgumentType.getString(context, "origin"));
			InetSocketAddress endpoint = AddressHelpers.parseEndpoint(StringArgumentType.getString(context, "endpoint"));
			ModpackConnectionMode connectionMode = ModpackConnectionMode.valueOf(
					StringArgumentType.getString(context, "connection-mode").toUpperCase(Locale.ROOT));
			return writeBootstrap(context,
					BootstrapConfig.install(origin, requireBootstrapFingerprint(), requirePublishedModpackId(), endpoint, connectionMode), true);
		} catch (IllegalArgumentException e) {
			send(context, e.getMessage(), ChatFormatting.RED, false);
			return 0;
		}
	}

	private static String requireBootstrapFingerprint() {
		if (serverConfig.disableInternalTLS) throw new IllegalArgumentException("Bootstrap export requires AutoModpack TLS to be enabled");
		String fingerprint = hostServer.getCertificateFingerprint();
		if (fingerprint == null) throw new IllegalArgumentException("Certificate fingerprint is unavailable; start the AutoModpack host with TLS enabled first");
		return fingerprint;
	}

	private static String requirePublishedModpackId() {
		try {
			var current = modpackExecutor.currentRecord().orElseThrow(() -> new IllegalArgumentException("No current generation is available; generate the modpack first"));
			return ModpackId.requireValid(current.manifest().modpackId());
		} catch (IOException e) {
			throw new IllegalArgumentException("Current generation is invalid; check the server logs", e);
		}
	}

	private static int writeBootstrap(CommandContext<CommandSourceStack> context, Jsons.KnownHostsBootstrapFields fields, boolean install) {
		try {
			ConfigTools.writeAtomic(knownHostsBootstrapFile, fields);
		} catch (IOException e) {
			LOGGER.error("Failed to export bootstrap file", e);
			send(context, "Failed to write bootstrap file: " + e.getMessage(), ChatFormatting.RED, false);
			return 0;
		}

		String absolutePath = knownHostsBootstrapFile.toAbsolutePath().normalize().toString();
		send(context, "Bootstrap file exported", ChatFormatting.GREEN, copyable(absolutePath), ChatFormatting.YELLOW, false);
		send(context, "Package it on clients at", ChatFormatting.WHITE, copyable("automodpack/automodpack-bootstrap.json"), ChatFormatting.YELLOW, false);
		if (install && serverConfig.validateSecrets) {
			send(context,
					"WARNING: validateSecrets=true. Fresh clients without an existing secret for this origin will fail preload download; disable validation or provision a normal login first.",
					ChatFormatting.RED, false);
		}
		return Command.SINGLE_SUCCESS;
	}

	private static MutableComponent copyable(String value) {
		return VersionedText.literal(value).withStyle(style -> style
				/*? if >=1.21.5 {*/
				.withHoverEvent(new HoverEvent.ShowText(VersionedText.translatable("chat.copy.click")))
				.withClickEvent(new ClickEvent.CopyToClipboard(value)));
				/*?} else {*/
				/*.withHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, VersionedText.translatable("chat.copy.click")))
				.withClickEvent(new ClickEvent(ClickEvent.Action.COPY_TO_CLIPBOARD, value)));
		*//*?}*/
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
				var profile = GameHelpers.getPlayerProfile(playerId);

				long connNum = connections.values().stream().filter(secret::equals).count();

				send(context, String.format("Player: %s (%s) is downloading modpack using %d connections", GameHelpers.getPlayerName(profile), playerId, connNum), ChatFormatting.GREEN, false);
			}
		});

		return Command.SINGLE_SUCCESS;
	}

	private static int reload(CommandContext<CommandSourceStack> context) {
		Util.backgroundExecutor().execute(() -> {
			var tempServerConfig = ConfigTools.read(serverConfigFile, Jsons.ServerConfigFieldsV3.class).orElse(null);
			if (tempServerConfig != null) {
				ConfigUtils.normalizeServerConfig(tempServerConfig, true);
				boolean restartRequired = connectionRuntimeChanged(serverConfig, tempServerConfig);
				serverConfig = tempServerConfig;
				send(context, "AutoModpack server config reloaded!", ChatFormatting.GREEN, true);
				if (restartRequired) send(context, "Connection settings changed. Run /automodpack host restart to apply them.", ChatFormatting.YELLOW, false);
			} else {
				send(context, "Error while reloading config file!", ChatFormatting.RED, true);
			}
		});

		return Command.SINGLE_SUCCESS;
	}

	private static int startModpackHost(CommandContext<CommandSourceStack> context) {
		Util.backgroundExecutor().execute(() -> {
			if (hostServer.isRunning()) {
				send(context, "Modpack hosting is already running!", ChatFormatting.RED, false);
				return;
			}

			send(context, "Starting modpack hosting...", ChatFormatting.YELLOW, true);
			hostServer.start();
			reportHostStart(context, "started");
		});

		return Command.SINGLE_SUCCESS;
	}

	private static int stopModpackHost(CommandContext<CommandSourceStack> context) {
		Util.backgroundExecutor().execute(() -> {
			boolean wasRunning = hostServer.isRunning();
			if (wasRunning) send(context, "Stopping modpack hosting...", ChatFormatting.RED, true);
			if (!hostServer.stop()) {
				send(context, "Couldn't stop server!", ChatFormatting.RED, true);
			} else if (wasRunning) {
				send(context, "Modpack hosting stopped!", ChatFormatting.RED, true);
			} else {
				send(context, "Modpack hosting is not running!", ChatFormatting.RED, false);
			}
		});

		return Command.SINGLE_SUCCESS;
	}

	private static int restartModpackHost(CommandContext<CommandSourceStack> context) {
		Util.backgroundExecutor().execute(() -> {
			send(context, "Restarting modpack hosting...", ChatFormatting.YELLOW, true);
			if (!hostServer.stop()) {
				send(context, "Couldn't restart server!", ChatFormatting.RED, true);
				return;
			}

			hostServer.start();
			reportHostStart(context, "restarted");
		});

		return Command.SINGLE_SUCCESS;
	}

	private static void reportHostStart(CommandContext<CommandSourceStack> context, String action) {
		if (hostServer.isRunning()) {
			send(context, "Modpack hosting " + action + "!", ChatFormatting.GREEN, true);
		} else if (!serverConfig.modpackHost) {
			send(context, "Built-in modpack hosting is disabled by modpackHost.", ChatFormatting.YELLOW, false);
		} else if (serverConfig.connectionMode == ModpackConnectionMode.DIRECT && serverConfig.bindPort == -1) {
			send(context, "DIRECT with bindPort -1 uses only the advertised external endpoint; no built-in listener was started.", ChatFormatting.YELLOW, false);
		} else {
			send(context, "Couldn't start server!", ChatFormatting.RED, true);
		}
	}

	private static boolean connectionRuntimeChanged(Jsons.ServerConfigFieldsV3 previous, Jsons.ServerConfigFieldsV3 current) {
		return previous.connectionMode != current.connectionMode || previous.bindPort != current.bindPort || previous.modpackHost != current.modpackHost
				|| previous.disableInternalTLS != current.disableInternalTLS || previous.bandwidthLimit != current.bandwidthLimit
				|| previous.updateIpsOnEveryStart != current.updateIpsOnEveryStart || !Objects.equals(previous.bindAddress, current.bindAddress);
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
		send(context, "/automodpack host start/stop/restart/connections/fingerprint/bootstrap", ChatFormatting.YELLOW, false);
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
			var generation = modpackExecutor.generateNew();
			if (generation.succeeded()) {
				send(context, "Modpack generation " + generation.status() + "! took " + (System.currentTimeMillis() - start) + "ms", ChatFormatting.GREEN, true);
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
