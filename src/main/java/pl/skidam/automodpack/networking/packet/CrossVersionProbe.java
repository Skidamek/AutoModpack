package pl.skidam.automodpack.networking.packet;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import net.minecraft.SharedConstants;
import net.minecraft.client.gui.screens.DisconnectedScreen;
import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.ui.screen.CrossVersionOfferScreen;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.networking.ModPackets;
import pl.skidam.automodpack_core.Environment;
import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.client.AdvertiseFetcher;
import pl.skidam.automodpack_core.client.ModpackUpdater;
import pl.skidam.automodpack_core.client.UpdateType;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.WireJsons;
import pl.skidam.automodpack_core.launchers.LauncherVersionSwapper;
import pl.skidam.automodpack_core.launchers.PrismMeta;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.protocol.CertificatePinMismatchException;
import pl.skidam.automodpack_core.protocol.CertificateTrustCancelledException;
import pl.skidam.automodpack_core.protocol.StatusPinger;
import pl.skidam.automodpack_core.protocol.UnauthorizedException;
import pl.skidam.automodpack_core.screen.FailureCategory;
import pl.skidam.automodpack_core.screen.FailureDestination;
import pl.skidam.automodpack_core.screen.FailureRequest;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.utils.AddressHelpers;
import pl.skidam.automodpack_core.utils.ByteFormat;
import pl.skidam.automodpack_core.utils.Throwables;

public final class CrossVersionProbe {
	private static final Logger LOGGER = LogManager.getLogger(CrossVersionProbe.class);
	private static final long NEGATIVE_CACHE_TTL_NANOS = TimeUnit.SECONDS.toNanos(60);
	private static final ConcurrentHashMap<String, Long> NEGATIVE_ORIGINS = new ConcurrentHashMap<>();

	private CrossVersionProbe() {}

	public static void onDisconnectScreen(Screen disconnectScreen) {
		if (ModPackets.loginQueryArrived()) return;
		if (!(disconnectScreen instanceof DisconnectedScreen)) return;
		ModPackets.ConnectionAttempt attempt = ModPackets.getConnectionAttempt();
		if (attempt == null || attempt.origin() == null) return;

		if (AdvertiseFetcher.isEssentialRelayHost(attempt.origin())) {
			LOGGER.warn("Cannot probe or sync through Essential's closed relay ({})", attempt.origin().getHostString());
			ScreenManager.failure(FailureRequest.of(new IOException("Essential world hosting relay does not expose a reachable AutoModpack endpoint"), "automodpack.error.essentialRelay",
					FailureCategory.CONNECTION, FailureDestination.MULTIPLAYER, null));
			return;
		}

		String originKey = AddressHelpers.formatAddress(attempt.origin());
		if (isNegativelyCached(originKey)) return;

		String screenText = disconnectScreen.getNarrationMessage().getString().toLowerCase(Locale.ROOT);
		ModpackUpdater.executor().execute(() -> checkAndProbe(attempt, originKey, screenText, disconnectScreen));
	}

	private static void checkAndProbe(ModPackets.ConnectionAttempt attempt, String originKey, String screenText, Screen originalDisconnectScreen) {
		int clientProtocol = SharedConstants.getProtocolVersion();
		OptionalInt serverProtocol = StatusPinger.fetchProtocolVersion(attempt.origin(), clientProtocol);

		boolean protocolMismatch = serverProtocol.isPresent() && serverProtocol.getAsInt() != clientProtocol;
		boolean decodeFailure = serverProtocol.isEmpty() && looksLikeDecodeFailure(screenText);
		boolean swallowedAutoModpackKick = serverProtocol.isPresent() && serverProtocol.getAsInt() == clientProtocol && screenText.contains("automodpack");

		if (!protocolMismatch && !decodeFailure && !swallowedAutoModpackKick) {
			if (serverProtocol.isPresent()) cacheNegative(originKey);
			return;
		}

		LOGGER.info("Join to {} failed (protocolMismatch={}, decodeFailure={}, swallowedKick={}); probing for AutoModpack advertisement",
				originKey, protocolMismatch, decodeFailure, swallowedAutoModpackKick);

		ScreenManager.clientThread(() -> {
			if (ScreenImpl.currentScreen() != originalDisconnectScreen) return;
			ScreenManager.waiting();
			ModpackUpdater.executor().execute(() -> runProbe(attempt, originKey, originalDisconnectScreen));
		});
	}

	private static boolean looksLikeDecodeFailure(String lowerText) {
		return lowerText.contains("decoderexception")
				|| lowerText.contains("failed to decode")
				|| lowerText.contains("jsonsyntaxexception")
				|| lowerText.contains("cannot decode")
				|| lowerText.contains("malformed");
	}

	private static void runProbe(ModPackets.ConnectionAttempt attempt, String originKey, Screen originalDisconnectScreen) {
		AdvertiseFetcher.fetchAsync(attempt.origin(), attempt.expectedFingerprint()).whenComplete((ad, error) -> {
			if (error != null || ad == null) {
				Throwable cause = Throwables.unwrap(error);
				if (CertificateTrustCancelledException.is(cause)) {
					ScreenImpl.multiplayer();
					return;
				}
				if (cause instanceof CertificatePinMismatchException) {
					ScreenManager.failure(FailureRequest.of(cause, "automodpack.pin.mismatch", FailureCategory.SECURITY, FailureDestination.MULTIPLAYER, null));
					return;
				}
				LOGGER.debug("Advertisement probe for {} returned no AutoModpack host: {}", originKey, cause == null ? "null" : cause.getMessage());
				cacheNegative(originKey);
				ScreenImpl.setScreen(originalDisconnectScreen);
				return;
			}
			presentOffer(attempt, ad, originalDisconnectScreen);
		});
	}

	private static boolean isNegativelyCached(String originKey) {
		Long expiresAt = NEGATIVE_ORIGINS.get(originKey);
		if (expiresAt == null) return false;
		if (System.nanoTime() - expiresAt < 0L) return true;
		NEGATIVE_ORIGINS.remove(originKey, expiresAt);
		return false;
	}

	private static void cacheNegative(String originKey) {
		NEGATIVE_ORIGINS.put(originKey, System.nanoTime() + NEGATIVE_CACHE_TTL_NANOS);
	}

	private static void presentOffer(ModPackets.ConnectionAttempt attempt, WireJsons.AdvertiseFields ad, Screen originalDisconnectScreen) {
		if (ad.v != WireJsons.WIRE_CONTRACT_VERSION) {
			String targetVersion = ad.amVersion == null || ad.amVersion.isBlank() ? "the server's version" : ad.amVersion;
			List<String> paragraphs = List.of(VersionedText.str("automodpack.crossVersion.contractMismatch", ad.v, targetVersion));
			ScreenManager.clientThread(() -> ScreenImpl.setScreen(new CrossVersionOfferScreen(originalDisconnectScreen, "automodpack.crossVersion.contractMismatchTitle", paragraphs, null)));
			return;
		}

		String serverMc = ad.target == null ? null : ad.target.mc;
		String serverLoader = ad.target == null ? null : ad.target.loader;
		String serverLoaderVersion = ad.target == null ? null : ad.target.loaderVersion;
		String currentMc = Environment.current().mcVersion();
		String currentLoader = Environment.current().loader();

		ClientConfigJsons.ClientConfigFieldsV3 clientConfig = ClientStorage.current().clientConfig().current();
		boolean syncVersions = clientConfig == null || clientConfig.syncVersions;
		LauncherVersionSwapper.SwitchPlan plan = LauncherVersionSwapper.planSwitch(serverLoader, serverLoaderVersion, serverMc, syncVersions, currentLoader, currentMc,
				LauncherVersionSwapper.detect(GameDirectory.current()), PrismMeta::isVersionResolvable);

		// Same-version host whose login queries were swallowed by a proxy: sync directly via advertised route if reachable.
		if (!plan.required() && !plan.refused() && ad.route != null && ad.pack != null && ad.absence == null) {
			trySameVersionInstall(attempt, ad);
			return;
		}

		List<String> paragraphs = new ArrayList<>();
		if (ad.pack != null && ad.pack.name != null && !ad.pack.name.isBlank()) {
			paragraphs.add(VersionedText.str("automodpack.crossVersion.pack", ad.pack.name, ad.pack.files, ByteFormat.formatSize(ad.pack.bytes)));
		} else {
			paragraphs.add(VersionedText.str("automodpack.crossVersion.noPack"));
		}

		String targetDesc = formatTarget(serverMc, serverLoader, serverLoaderVersion);
		String currentDesc = formatTarget(currentMc, currentLoader, Environment.current().loaderVersion());
		paragraphs.add(VersionedText.str("automodpack.crossVersion.versions", targetDesc, currentDesc));

		if (ad.route == null) {
			paragraphs.add(VersionedText.str("automodpack.crossVersion.unreachableRoute"));
		}

		String selectedId = ClientStorage.current().selectedModpackId();
		if (selectedId != null && !selectedId.isBlank() && (ad.pack == null || !selectedId.equals(ad.pack.id))) {
			paragraphs.add(VersionedText.str("automodpack.crossVersion.instanceWarning"));
		}

		Runnable onSwitch = null;
		if (plan.refused()) {
			paragraphs.add(plan.refusedReason());
		} else if (plan.manual()) {
			paragraphs.add(VersionedText.str("automodpack.crossVersion.manual", targetDesc));
		} else if (plan.required()) {
			LauncherVersionSwapper.Versions versions = new LauncherVersionSwapper.Versions(serverLoader, serverLoaderVersion, serverMc);
			LauncherVersionSwapper.Switch launcherSwitch = new LauncherVersionSwapper.Switch(plan.axes(), versions);
			onSwitch = () -> applySwitch(attempt, ad, launcherSwitch);
		}

		Runnable switchAction = onSwitch;
		ScreenManager.clientThread(() -> ScreenImpl.setScreen(new CrossVersionOfferScreen(originalDisconnectScreen, paragraphs, switchAction)));
	}

	private static void trySameVersionInstall(ModPackets.ConnectionAttempt attempt, WireJsons.AdvertiseFields ad) {
		try {
			ConnectionJsons.ConnectionInfo connectionInfo = AdvertiseFetcher.resolveAdvertisedConnection(attempt.origin(), attempt.origin(), attempt.expectedFingerprint(), ad.route);
			if (connectionInfo == null || !connectionInfo.isComplete()) {
				ScreenManager.failure(FailureRequest.of(new IOException("Server did not advertise a reachable endpoint"), "automodpack.error.connection", FailureCategory.CONNECTION,
						FailureDestination.MULTIPLAYER, null));
				return;
			}
			ClientStorage storage = ClientStorage.open(GameDirectory.current());
			Secrets.Secret storedSecret = ModpackId.isValid(ad.pack.id) ? ConnectionStore.getClientSecret(storage, ad.pack.id, attempt.origin()) : null;
			ClientLoginUpdateFlow.reconcile(null, connectionInfo, storedSecret, storage, ad.require, false).whenComplete((response, error) -> {
				if (error != null) {
					Throwable cause = Throwables.unwrap(error);
					if (Throwables.findCause(cause, UnauthorizedException.class) != null) {
						ScreenManager.failure(FailureRequest.of(cause, "automodpack.error.proxySwallowedQueries", FailureCategory.CONNECTION, FailureDestination.MULTIPLAYER, null));
					} else {
						ScreenManager.failure(FailureRequest.of(cause, "automodpack.error.connection", FailureCategory.CONNECTION, FailureDestination.MULTIPLAYER, null));
					}
				}
			});
		} catch (Exception e) {
			ScreenManager.failure(FailureRequest.of(e, "automodpack.error.connection", FailureCategory.CONNECTION, FailureDestination.MULTIPLAYER, null));
		}
	}

	private static void applySwitch(ModPackets.ConnectionAttempt attempt, WireJsons.AdvertiseFields ad, LauncherVersionSwapper.Switch launcherSwitch) {
		ScreenManager.waiting();
		ModpackUpdater.executor().execute(() -> {
			try {
				LauncherVersionSwapper.apply(launcherSwitch, GameDirectory.current());
				ClientStorage storage = ClientStorage.open(GameDirectory.current());
				if (ad.pack != null && ModpackId.isValid(ad.pack.id) && ad.route != null) {
					ConnectionJsons.ConnectionInfo resolved = AdvertiseFetcher.resolveAdvertisedConnection(attempt.origin(), attempt.origin(), attempt.expectedFingerprint(), ad.route);
					if (resolved != null && resolved.isComplete()) {
						ConnectionStore.saveConnection(storage, ad.pack.id, resolved);
						storage.writeSelectedModpackId(ad.pack.id);
					}
				}
				ScreenManager.restart(UpdateType.SELECT, null);
			} catch (Exception e) {
				ScreenManager.failure(FailureRequest.of(e, "automodpack.error.storage", FailureCategory.STORAGE, FailureDestination.MULTIPLAYER, null));
			}
		});
	}

	private static String formatTarget(String mc, String loader, String loaderVersion) {
		StringBuilder sb = new StringBuilder();
		if (mc != null && !mc.isBlank()) sb.append("Minecraft ").append(mc);
		if (loader != null && !loader.isBlank()) {
			if (!sb.isEmpty()) sb.append(" / ");
			sb.append(loader);
			if (loaderVersion != null && !loaderVersion.isBlank()) sb.append(' ').append(loaderVersion);
		}
		return sb.isEmpty() ? "unknown" : sb.toString();
	}
}
