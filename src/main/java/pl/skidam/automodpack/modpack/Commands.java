package pl.skidam.automodpack.modpack;

import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ServerConfigJsons;
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
import pl.skidam.automodpack_core.auth.ProvisioningSecretStore;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.auth.ServerAddressPin;
import pl.skidam.automodpack_core.config.BootstrapConfig;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackExecutor;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.GenerationDiff;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryEntry;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationStore;
import pl.skidam.automodpack_core.protocol.ModpackConnectionMode;
import pl.skidam.automodpack_core.storage.DataRootResolver;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.cache.ClientObjectStore;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.storage.StoragePaths;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
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
		var generateIfStateNode = literal("if-state")
				.then(argument("state-digest", StringArgumentType.word()).executes(Commands::guardedGenerateModpack)
						.then(literal("notes")
								.then(argument("notes", StringArgumentType.greedyString()).executes(Commands::guardedGenerateModpack))));
		var generateRevertNode = literal("revert")
				.then(argument("generation-id", StringArgumentType.word()).executes(Commands::previewRevertGeneration)
						.then(literal("confirm")
								.executes(Commands::revertGeneration)
								.then(literal("notes")
										.then(argument("notes", StringArgumentType.greedyString()).executes(Commands::revertGeneration)))));
		var generateStorageNode = literal("storage")
				.executes(Commands::generationStorage)
				.then(literal("compact")
						.then(literal("before")
								.then(argument("generation-id", StringArgumentType.word())
										.executes(Commands::generationStorageCompactPreview)
										.then(literal("confirm").executes(Commands::generationStorageCompact)))))
				.then(literal("collect")
						.then(literal("confirm").executes(Commands::generationStorageCollect)));
		var generateNode = literal("generate")
				.requires((source) -> source.permissions().hasPermission(new Permission.HasCommandLevel(PermissionLevel.byId(3))))
				.executes(Commands::generateModpack)
				.then(literal("notes")
						.then(argument("notes", StringArgumentType.greedyString()).executes(Commands::generateModpack)))
				.then(literal("preview")
						.executes(Commands::previewModpack)
						.then(literal("notes")
								.then(argument("notes", StringArgumentType.greedyString()).executes(Commands::previewModpack))))
				.then(generateIfStateNode)
				.then(generateRevertNode)
				.then(literal("history").executes(Commands::generationHistory))
				.then(generateStorageNode);
		var automodpackNode = dispatcher.register(
				literal("automodpack")
						.executes(Commands::about)
						.then(generateNode)
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
					BootstrapConfig.install(origin, requireBootstrapFingerprint(), requirePublishedModpackId(), endpoint, serverConfig.connectionMode, requireProvisioningSecret()), true);
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
					BootstrapConfig.install(origin, requireBootstrapFingerprint(), requirePublishedModpackId(), endpoint, connectionMode, requireProvisioningSecret()), true);
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

	private static String requireProvisioningSecret() {
		return ProvisioningSecretStore.ensure();
	}

	private static String requirePublishedModpackId() {
		try {
			var current = modpackExecutor.currentRecord().orElseThrow(() -> new IllegalArgumentException("No current generation is available; generate the modpack first"));
			return ModpackId.requireValid(current.manifest().modpackId());
		} catch (IOException e) {
			throw new IllegalArgumentException("Current generation is invalid; check the server logs", e);
		}
	}

	private static int writeBootstrap(CommandContext<CommandSourceStack> context, ConnectionJsons.KnownHostsBootstrapFields fields, boolean install) {
		Path bootstrapPath = GameDirectory.current().resolve(StoragePaths.BOOTSTRAP_EXPORT_FILE).normalize();
		try {
			ConfigTools.writeAtomic(bootstrapPath, fields);
		} catch (IOException e) {
			LOGGER.error("Failed to export bootstrap file", e);
			send(context, "Failed to write bootstrap file: " + e.getMessage(), ChatFormatting.RED, false);
			return 0;
		}

		String absolutePath = bootstrapPath.toAbsolutePath().normalize().toString();
		send(context, "Bootstrap file exported", ChatFormatting.GREEN, copyable(absolutePath), ChatFormatting.YELLOW, false);
		send(context, "Copy it to clients as", ChatFormatting.WHITE, copyable("automodpack/automodpack-bootstrap.json"), ChatFormatting.YELLOW, false);
		send(context, "The exported file is not imported on this instance. Clients must already have AutoModpack installed.", ChatFormatting.GRAY, false);
		if (install) send(context, "The file includes a provisioning secret. Treat it as a credential.", ChatFormatting.YELLOW, false);
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

			send(context, String.format(Locale.ROOT, "Active connections: %d Unique connections: %d ", connections.size(), uniqueSecrets.size()), ChatFormatting.YELLOW, false);

			for (String secret : uniqueSecrets) {
				var playerSecretPair = SecretsStore.getHostSecret(secret);
				if (playerSecretPair == null) continue;

				String playerId = playerSecretPair.getKey();
				var profile = GameHelpers.getPlayerProfile(playerId);

				long connNum = connections.values().stream().filter(secret::equals).count();

				send(context, String.format(Locale.ROOT, "Player: %s (%s) is downloading modpack using %d connections", GameHelpers.getPlayerName(profile), playerId, connNum), ChatFormatting.GREEN, false);
			}
		});

		return Command.SINGLE_SUCCESS;
	}

	private static int reload(CommandContext<CommandSourceStack> context) {
		Util.backgroundExecutor().execute(() -> {
			Path serverConfigPath = GameDirectory.current().resolve(StoragePaths.SERVER_CONFIG_FILE).normalize();
			var tempServerConfig = ConfigTools.read(serverConfigPath, ServerConfigJsons.ServerConfigFieldsV3.class).orElse(null);
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

	private static boolean connectionRuntimeChanged(ServerConfigJsons.ServerConfigFieldsV3 previous, ServerConfigJsons.ServerConfigFieldsV3 current) {
		return previous.connectionMode != current.connectionMode || previous.bindPort != current.bindPort || previous.modpackHost != current.modpackHost
				|| previous.disableInternalTLS != current.disableInternalTLS || previous.bandwidthLimit != current.bandwidthLimit
				|| !Objects.equals(previous.bindAddress, current.bindAddress);
	}

	private static int modpackHostAbout(CommandContext<CommandSourceStack> context) {
		ChatFormatting statusColor = hostServer.isRunning() ? ChatFormatting.GREEN : ChatFormatting.RED;
		String status = hostServer.isRunning() ? "running" : "not running";
		send(context, "Modpack hosting status", ChatFormatting.GREEN, status, statusColor, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int about(CommandContext<CommandSourceStack> context) {
		send(context, "AutoModpack", ChatFormatting.GREEN, AM_VERSION, ChatFormatting.WHITE, false);
		send(context, "/automodpack generate [notes <text...>]", ChatFormatting.YELLOW, false);
		send(context, "/automodpack generate preview [notes <text...>]", ChatFormatting.YELLOW, false);
		send(context, "/automodpack generate if-state <digest> [notes <text...>]", ChatFormatting.YELLOW, false);
			send(context, "/automodpack generate revert <generation-id> confirm [notes <text...>]", ChatFormatting.YELLOW, false);
			send(context, "/automodpack generate history/storage [compact before <generation-id> [confirm]|collect confirm]", ChatFormatting.YELLOW, false);
		send(context, "/automodpack host start/stop/restart/connections/fingerprint/bootstrap", ChatFormatting.YELLOW, false);
		send(context, "/automodpack config reload", ChatFormatting.YELLOW, false);
		return Command.SINGLE_SUCCESS;
	}

	private static int generateModpack(CommandContext<CommandSourceStack> context) {
		return runGeneration(context, false, false);
	}

	private static int previewModpack(CommandContext<CommandSourceStack> context) {
		return runGeneration(context, true, false);
	}

	private static int guardedGenerateModpack(CommandContext<CommandSourceStack> context) {
		return runGeneration(context, false, true);
	}

	private static int runGeneration(CommandContext<CommandSourceStack> context, boolean preview, boolean guarded) {
		String notes = optionalArgument(context, "notes");
		String stateDigest = guarded ? StringArgumentType.getString(context, "state-digest") : null;
		Util.backgroundExecutor().execute(() -> {
			send(context, preview ? "Preparing modpack preview..." : "Generating modpack...", ChatFormatting.YELLOW, !preview);
			long start = System.currentTimeMillis();
			if (preview) {
				ModpackExecutor.PreviewResult result = modpackExecutor.preview(notes);
				if (result instanceof ModpackExecutor.PreviewReady ready) {
					send(context, "PREVIEW READY" + elapsed(start), ChatFormatting.GREEN, false);
					reportGenerationDetails(context, ready.state(), null, false);
					if (ready.state().parent().isEmpty())
						send(context, "Guarded publication is unavailable until an unguarded root generation exists", ChatFormatting.YELLOW, false);
				} else if (result instanceof ModpackExecutor.PreviewBusy busy) {
					send(context, "PREVIEW FAILED: " + busy.detail(), ChatFormatting.RED, false);
				} else if (result instanceof ModpackExecutor.PreviewFailed failed) {
					send(context, "PREVIEW FAILED: " + failed.failure().getClass().getSimpleName(), ChatFormatting.RED, false);
				}
				return;
			}

			ModpackExecutor.PublishResult result = guarded ? modpackExecutor.publishIfState(stateDigest, notes) : modpackExecutor.publish(notes);
			if (result instanceof ModpackExecutor.Published published) {
				send(context, "PUBLISHED" + elapsed(start), ChatFormatting.GREEN, true);
				reportGenerationDetails(context, published.state(), published.current(), false);
				published.warnings().forEach(warning -> send(context, "WARNING: " + warning, ChatFormatting.YELLOW, false));
			} else if (result instanceof ModpackExecutor.NoChanges noChanges) {
				send(context, "NO_CHANGES" + elapsed(start), ChatFormatting.YELLOW, true);
				reportGenerationDetails(context, noChanges.state(), noChanges.current(), false);
				noChanges.warnings().forEach(warning -> send(context, "WARNING: " + warning, ChatFormatting.YELLOW, false));
			} else if (result instanceof ModpackExecutor.PublishGuardMismatch mismatch) {
				send(context, "FAILED: " + mismatch.detail(), ChatFormatting.RED, true);
				reportGenerationDetails(context, mismatch.state(), null, false);
			} else if (result instanceof ModpackExecutor.PublishInvalidGuard invalid) {
				send(context, "FAILED: " + invalid.detail(), ChatFormatting.RED, true);
			} else if (result instanceof ModpackExecutor.PublishGuardUnsupported unsupported) {
				send(context, "FAILED: " + unsupported.detail(), ChatFormatting.RED, true);
			} else if (result instanceof ModpackExecutor.PublishBusy busy) {
				send(context, "FAILED: " + busy.detail(), ChatFormatting.RED, true);
			} else if (result instanceof ModpackExecutor.PublishFailed failed) {
				send(context, "FAILED: " + failed.failure().getClass().getSimpleName(), ChatFormatting.RED, true);
			}
		});
		return Command.SINGLE_SUCCESS;
	}

	private static int previewRevertGeneration(CommandContext<CommandSourceStack> context) {
		String targetGenerationId = StringArgumentType.getString(context, "generation-id");
		try {
			List<GenerationHistoryEntry> history = modpackExecutor.technicalHistory();
			GenerationHistoryEntry target = findRevertTarget(history, targetGenerationId);
			if (target == null) {
				send(context, "FAILED: generation target was not found", ChatFormatting.RED, true);
				return 0;
			}
			reportRevertTarget(context, target, history);
			send(context, "Confirmation required: /automodpack generate revert " + targetGenerationId + " confirm", ChatFormatting.YELLOW, false);
			return Command.SINGLE_SUCCESS;
		} catch (IOException e) {
			send(context, "FAILED: could not read generation history: " + e.getMessage(), ChatFormatting.RED, true);
			return 0;
		}
	}

	private static int revertGeneration(CommandContext<CommandSourceStack> context) {
		String targetGenerationId = StringArgumentType.getString(context, "generation-id");
		String notes = optionalArgument(context, "notes");
		List<GenerationHistoryEntry> history;
		GenerationHistoryEntry target;
		try {
			history = modpackExecutor.technicalHistory();
			target = findRevertTarget(history, targetGenerationId);
			if (target == null) {
				send(context, "FAILED: generation target was not found", ChatFormatting.RED, true);
				return 0;
			}
			reportRevertTarget(context, target, history);
		} catch (IOException e) {
			send(context, "FAILED: could not read generation history: " + e.getMessage(), ChatFormatting.RED, true);
			return 0;
		}
		Util.backgroundExecutor().execute(() -> {
			send(context, "Reverting modpack to generation " + targetGenerationId + "...", ChatFormatting.YELLOW, true);
			ModpackExecutor.RevertResult result = modpackExecutor.revert(targetGenerationId, notes);
			if (result instanceof ModpackExecutor.Reverted reverted) {
				send(context, "REVERTED", ChatFormatting.GREEN, true);
				send(context, "New generation", ChatFormatting.WHITE, copyable(reverted.current().metadata().generationId()), ChatFormatting.YELLOW, true);
				send(context, "Rollback target", ChatFormatting.WHITE, copyable(reverted.targetGenerationId()), ChatFormatting.YELLOW, true);
				reverted.warnings().forEach(warning -> send(context, "WARNING: " + warning, ChatFormatting.YELLOW, false));
			} else if (result instanceof ModpackExecutor.RevertBusy busy) {
				send(context, "FAILED: " + busy.detail(), ChatFormatting.RED, true);
			} else if (result instanceof ModpackExecutor.RevertInvalidTarget invalid) {
				send(context, "FAILED: " + invalid.detail(), ChatFormatting.RED, true);
			} else if (result instanceof ModpackExecutor.RevertFailed failed) {
				send(context, "FAILED: " + failed.failure().getClass().getSimpleName(), ChatFormatting.RED, true);
			}
		});
		return Command.SINGLE_SUCCESS;
	}

	private static GenerationHistoryEntry findRevertTarget(List<GenerationHistoryEntry> history, String targetGenerationId) {
		return history.stream().filter(entry -> entry.metadata().generationId().equals(targetGenerationId)).findFirst().orElse(null);
	}

	private static void reportRevertTarget(CommandContext<CommandSourceStack> context, GenerationHistoryEntry target, List<GenerationHistoryEntry> history) {
		send(context, "Revert target", ChatFormatting.YELLOW, target.metadata().createdAt().toString(), ChatFormatting.WHITE, true);
		send(context, "Target generation", ChatFormatting.WHITE, copyable(target.metadata().generationId()), ChatFormatting.YELLOW, false);
		send(context, "Target content", ChatFormatting.WHITE, copyable(target.metadata().stateDigest()), ChatFormatting.YELLOW, false);
		send(context, "Target ledger", ChatFormatting.WHITE, copyable(target.metadata().ledgerDigest()), ChatFormatting.YELLOW, false);
		int targetFiles = target.manifest().groups().values().stream().mapToInt(group -> group.files().size()).sum();
		send(context, "Target catalogue", ChatFormatting.WHITE, target.manifest().groups().size() + " groups, " + targetFiles + " files", ChatFormatting.YELLOW, false);
		if (!target.metadata().patchNotes().isBlank()) send(context, "Target patch notes: " + target.metadata().patchNotes(), ChatFormatting.GRAY, false);
		if (!history.isEmpty() && !history.get(history.size() - 1).metadata().generationId().equals(target.metadata().generationId())) {
			GenerationDiff diff = GenerationDiff.between(history.get(history.size() - 1).manifest(), target.manifest());
			GenerationDiff.Summary summary = diff.summary();
			send(context, "Changes from current: +" + summary.addedFiles() + " added, " + summary.modifiedFiles() + " changed, " + summary.removedFiles()
					+ " removed, " + summary.metadataOnlyFiles() + " metadata-only, " + summary.metadataChanges() + " metadata changes", ChatFormatting.YELLOW, false);
		}
	}

	private static int generationHistory(CommandContext<CommandSourceStack> context) {
		try {
			var history = modpackExecutor.historyIndex().orElse(null);
			if (history == null || history.entries().isEmpty()) {
				send(context, "No published generations.", ChatFormatting.YELLOW, false);
				return Command.SINGLE_SUCCESS;
			}
			for (GenerationHistoryIndex.Entry entry : history.entries()) {
				send(context, "GENERATION " + entry.createdAt(), ChatFormatting.WHITE, false);
				send(context, "Generation", ChatFormatting.WHITE, copyable(entry.generationId()), ChatFormatting.YELLOW, false);
				send(context, "Parent", ChatFormatting.WHITE, copyable(entry.parentGenerationId()), ChatFormatting.YELLOW, false);
				send(context, "Content state", ChatFormatting.WHITE, copyable(entry.stateDigest()), ChatFormatting.YELLOW, false);
				send(context, "Changes", ChatFormatting.WHITE,
						"+" + entry.diffSummary().addedFiles() + " ~" + entry.diffSummary().modifiedFiles() + " -" + entry.diffSummary().removedFiles()
								+ " metadata " + entry.diffSummary().metadataChanges(), ChatFormatting.YELLOW, false);
				if (!entry.detailsAvailable()) send(context, "Detailed catalogue", ChatFormatting.WHITE, "compacted", ChatFormatting.GRAY, false);
				if (!entry.rollbackAvailable()) send(context, "Rollback", ChatFormatting.WHITE, "unavailable after compaction", ChatFormatting.GRAY, false);
				if (!entry.patchNotes().isBlank()) send(context, "Patch notes: " + entry.patchNotes(), ChatFormatting.GRAY, false);
			}
			return Command.SINGLE_SUCCESS;
		} catch (IOException e) {
			send(context, "Failed to read generation history: " + e.getMessage(), ChatFormatting.RED, false);
			return 0;
		}
	}

	private static int generationStorage(CommandContext<CommandSourceStack> context) {
			try {
				GenerationStore.StorageReport report = modpackExecutor.storageReport();
				send(context, "Generation storage", ChatFormatting.GREEN, false);
				send(context, "Catalogues", ChatFormatting.WHITE, report.catalogueCount() + " files, " + report.catalogueBytes() + " bytes", ChatFormatting.YELLOW, false);
				send(context, "Commits", ChatFormatting.WHITE, report.commitCount() + " files, " + report.commitBytes() + " bytes", ChatFormatting.YELLOW, false);
				send(context, "Deltas", ChatFormatting.WHITE, report.deltaCount() + " files, " + report.deltaBytes() + " bytes", ChatFormatting.YELLOW, false);
				send(context, "Immutable objects", ChatFormatting.WHITE, report.immutableObjectCount() + " files, " + report.immutableObjectBytes() + " bytes", ChatFormatting.YELLOW, false);
				send(context, "Staging", ChatFormatting.WHITE, report.stagingFileCount() + " files, " + report.stagingBytes() + " bytes", ChatFormatting.YELLOW, false);
				send(context, "Referenced objects", ChatFormatting.WHITE, report.referencedObjectCount() + " unique, " + report.referencedObjectBytes() + " bytes", ChatFormatting.YELLOW, false);
				report.uniqueObjectReferenceRatio().ifPresent(ratio -> send(context, String.format(Locale.ROOT, "Unique/reference ratio: %.4f", ratio), ChatFormatting.WHITE, false));
				return Command.SINGLE_SUCCESS;
			} catch (IOException e) {
				send(context, "Failed to measure generation storage: " + e.getMessage(), ChatFormatting.RED, false);
				return 0;
			}
		}

		private static int generationStorageCollect(CommandContext<CommandSourceStack> context) {
			try {
				Set<String> retainedGenerationIds = new TreeSet<>();
				modpackExecutor.historyIndex().ifPresent(index -> index.entries().stream().filter(GenerationHistoryIndex.Entry::rollbackAvailable)
						.forEach(entry -> retainedGenerationIds.add(entry.generationId())));
				Path gameDirectory = GameDirectory.current();
				Path serverRoot = gameDirectory.resolve(StoragePaths.SERVER_DIR).normalize();
				DataRootResolver.Location dataLocation = DataRootResolver.resolve(gameDirectory);
				Set<String> clientObjectPins = ClientObjectStore.existingReferencedHashes(ClientStorage.open(gameDirectory));
				GenerationStore.CollectionResult result = new GenerationStore(serverRoot, dataLocation).collectUnreachableObjects(retainedGenerationIds, clientObjectPins);
				send(context, "Generation objects collected", ChatFormatting.GREEN, false);
				send(context, "Retained generations", ChatFormatting.WHITE, String.valueOf(retainedGenerationIds.size()), ChatFormatting.YELLOW, false);
				send(context, "Objects", ChatFormatting.WHITE, result.beforeObjectCount() + " -> " + result.afterObjectCount(), ChatFormatting.YELLOW, false);
				send(context, "Bytes", ChatFormatting.WHITE, result.beforeObjectBytes() + " -> " + result.afterObjectBytes(), ChatFormatting.YELLOW, false);
				send(context, "Deleted", ChatFormatting.WHITE, result.deletedObjectCount() + " objects, " + result.deletedObjectBytes() + " bytes", ChatFormatting.YELLOW, false);
				send(context, "Client-pinned objects", ChatFormatting.WHITE, String.valueOf(clientObjectPins.size()), ChatFormatting.YELLOW, false);
				return Command.SINGLE_SUCCESS;
			} catch (IOException e) {
				send(context, "Failed to collect generation objects: " + e.getMessage(), ChatFormatting.RED, false);
				return 0;
			}
		}

		private static int generationStorageCompactPreview(CommandContext<CommandSourceStack> context) {
			String boundary = StringArgumentType.getString(context, "generation-id");
			try {
				GenerationStore.CompactionPreview preview = modpackExecutor.previewCompactHistory(boundary);
				sendCompactionPreview(context, preview);
				send(context, "Run /automodpack generate storage compact before " + boundary + " confirm to apply this exact compaction", ChatFormatting.YELLOW, false);
				return Command.SINGLE_SUCCESS;
			} catch (IOException e) {
				send(context, "Failed to preview generation-history compaction: " + e.getMessage(), ChatFormatting.RED, false);
				return 0;
			}
		}

		private static int generationStorageCompact(CommandContext<CommandSourceStack> context) {
			String boundary = StringArgumentType.getString(context, "generation-id");
			Util.backgroundExecutor().execute(() -> {
				try {
					GenerationStore.CompactionResult result = modpackExecutor.compactHistoryBefore(boundary);
					send(context, "Generation details compacted before the retained boundary", ChatFormatting.GREEN, false);
					send(context, "Boundary generation", ChatFormatting.WHITE, copyable(result.boundaryGenerationId()), ChatFormatting.YELLOW, false);
					send(context, "Deleted history", ChatFormatting.WHITE,
						result.deletedCommitCount() + " commits, " + result.deletedDeltaCount() + " deltas, " + result.deletedCatalogueCount() + " catalogues, "
								+ result.deletedBytes() + " bytes", ChatFormatting.YELLOW, false);
					send(context, "Superseded generations", ChatFormatting.WHITE, String.valueOf(result.supersededGenerationIds().size()), ChatFormatting.YELLOW, false);
				} catch (IOException e) {
					send(context, "Failed to compact generation history: " + e.getMessage(), ChatFormatting.RED, false);
				}
			});
		return Command.SINGLE_SUCCESS;
		}

		private static void sendCompactionPreview(CommandContext<CommandSourceStack> context, GenerationStore.CompactionPreview preview) {
			send(context, "Generation-history compaction preview", ChatFormatting.YELLOW, false);
			send(context, "Retained boundary", ChatFormatting.WHITE, copyable(preview.boundaryGenerationId()), ChatFormatting.YELLOW, false);
			send(context, "Rollback targets lost", ChatFormatting.WHITE, String.valueOf(preview.rollbackUnavailableGenerationIds().size()), ChatFormatting.YELLOW, false);
			for (String generationId : preview.rollbackUnavailableGenerationIds())
				send(context, "Loses rollback", ChatFormatting.WHITE, copyable(generationId), ChatFormatting.RED, false);
			send(context, "Detailed files reclaimable", ChatFormatting.WHITE,
					preview.supersededCatalogueStateDigests().size() + " catalogues, " + preview.reclaimableCatalogueBytes() + " bytes", ChatFormatting.YELLOW, false);
			send(context, "Commit/delta files reclaimable", ChatFormatting.WHITE,
					preview.reclaimableCommitBytes() + preview.reclaimableDeltaBytes() + " bytes", ChatFormatting.YELLOW, false);
			send(context, "Total reclaimable", ChatFormatting.WHITE, preview.reclaimableBytes() + " bytes", ChatFormatting.YELLOW, false);
		}

		private static String optionalArgument(CommandContext<CommandSourceStack> context, String name) {
		try {
			return StringArgumentType.getString(context, name);
		} catch (IllegalArgumentException e) {
			return null;
		}
	}

	private static String elapsed(long start) {
		return " took " + (System.currentTimeMillis() - start) + "ms";
	}

	private static void reportGenerationDetails(CommandContext<CommandSourceStack> context, ModpackExecutor.CandidateState state,
			GenerationRecord current, boolean broadcast) {
		state.parent().ifPresent(parent -> send(context, "Parent generation", ChatFormatting.WHITE, copyable(parent.metadata().generationId()), ChatFormatting.YELLOW, broadcast));
		send(context, "Candidate state", ChatFormatting.WHITE, copyable(state.candidateStateDigest()), ChatFormatting.YELLOW, broadcast);
		var diff = state.diff().summary();
		send(context, String.format(Locale.ROOT, "Diff: +%d ~%d -%d metadata-only %d metadata changes %d", diff.addedFiles(), diff.modifiedFiles(), diff.removedFiles(),
				diff.metadataOnlyFiles(), diff.metadataChanges()), ChatFormatting.WHITE, broadcast);
		for (String change : state.diff().humanReadableChanges()) send(context, "Change: " + change, ChatFormatting.GRAY, broadcast);
		var summary = state.summary();
		send(context, String.format(Locale.ROOT, "Candidate: %d groups, %d files, %d objects, %d exclusions, %d shadows", summary.groups(), summary.files(), summary.objects(),
				summary.exclusions(), summary.shadows()), ChatFormatting.WHITE, broadcast);
		for (var exclusion : summary.excluded())
			send(context, String.format(Locale.ROOT, "Excluded: %s/%s - %s (%s)", exclusion.source().groupId(), exclusion.source().logicalPath(),
					exclusion.reason().name().toLowerCase(Locale.ROOT), exclusion.message()), ChatFormatting.GRAY, broadcast);
		state.patchNotesSource().ifPresent(source -> send(context, "Patch notes: " + source.name().toLowerCase(Locale.ROOT), ChatFormatting.WHITE, broadcast));
		if (current != null)
			send(context, "Current generation", ChatFormatting.WHITE, copyable(current.metadata().generationId()), ChatFormatting.YELLOW, broadcast);
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
