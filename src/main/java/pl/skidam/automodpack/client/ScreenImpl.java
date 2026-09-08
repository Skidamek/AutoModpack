package pl.skidam.automodpack.client;

import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack.client.ui.*;
import pl.skidam.automodpack.client.ui.screen.*;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack.client.ui.versioned.VersionedToasts;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.protocol.CertificatePinMismatchException;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.client.SessionUpdateState;
import pl.skidam.automodpack_loader_core.screen.ScreenService;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.HistoryViewRequest;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;
import pl.skidam.automodpack_core.utils.Throwables;

import java.util.Optional;
import java.util.Locale;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.toasts.SystemToast;
import net.minecraft.client.gui.components.toasts.Toast;
import net.minecraft.client.gui.screens.ConnectScreen;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

public class ScreenImpl implements ScreenService {

	private static void executeOnClient(Runnable task) {
		Minecraft.getInstance().execute(task);
	}

	/** Quiet reminder that this session installed content the running game has not loaded; never replaces a screen. */
	public static void updatePendingRestartToast() {
		if (!SessionUpdateState.hasAppliedContentNotLoaded()) return;
		executeOnClient(() -> {
			Toast toast = new SystemToast(SystemToast.SystemToastId.PACK_LOAD_FAILURE, VersionedText.translatable("automodpack.restart.toast.title"),
					VersionedText.translatable("automodpack.restart.toast.description"));
			VersionedToasts.add(toast);
		});
	}

	@Override
	public void download(DownloadManager downloadManager, String modpackName) {
		executeOnClient(() -> Screens.download(downloadManager, modpackName));
	}

	@Override
	public void changelog(Object parent, Changelogs changelogs) {
		executeOnClient(() -> Screens.changelog((Screen) parent, changelogs));
	}

	@Override
	public void restart(UpdateType updateType, Changelogs changelogs) {
		executeOnClient(() -> Screens.restart(updateType, changelogs));
	}

	@Override
	public void completeWithoutRestart() {
		executeOnClient(Screens::multiplayer);
	}

	@Override
	public void welcome(ModpackUpdater modpackUpdater) {
		executeOnClient(() -> Screens.welcome(modpackUpdater));
	}

	@Override
	public boolean preview(UpdatePreview preview, String modpackName, ModpackUpdater updater, Runnable continueAction, Runnable cancelAction) {
		executeOnClient(() -> Screens.preview(preview, modpackName, updater, continueAction, cancelAction));
		return true;
	}

	@Override
	public void history(HistoryViewRequest request) {
		executeOnClient(() -> Screens.history(request));
	}

	@Override
	public void failure(FailureRequest request) {
		executeOnClient(() -> Screens.failure(request));
	}

	@Override
	public void validation(Object parent, String fingerprint, String origin, Runnable validated, Runnable canceled) {
		executeOnClient(() -> Screens.validation((Screen) parent, fingerprint, origin, validated, canceled));
	}

	@Override
	public void originChange(String modpackName, String approvedOrigins, String newOrigin, Runnable allowed, Runnable refused) {
		executeOnClient(() -> Screens.originChange(modpackName, approvedOrigins, newOrigin, allowed, refused));
	}

	@Override
	public void detachedJoin(String modpackName, boolean headMatchesActive, Runnable continueJoin, Runnable syncNow) {
		executeOnClient(() -> Screens.detachedJoin(modpackName, headMatchesActive, continueJoin, syncNow));
	}

	@Override
	public void waiting() {
		executeOnClient(() -> Screens.waiting(null));
	}

	@Override
	public void waiting(Runnable onCancel) {
		executeOnClient(() -> Screens.waiting(onCancel));
	}

	@Override
	public void discardReturnTarget() {
		executeOnClient(() -> Screens.interactiveParent = null);
	}

	@Override
	public Optional<String> getScreenString() {
		Screen screen = Screens.getScreen();
		return Optional.ofNullable(screen).map(current -> current.getTitle().getString().toLowerCase(Locale.ROOT));
	}

	@Override
	public Optional<Object> getScreen() {
		return Optional.ofNullable(Screens.getScreen());
	}

	public static void setScreen(Screen screen) {
		Screens.setScreen(screen);
	}

	public static void multiplayer() {
		Screens.multiplayer();
	}

	public static void repairSelection(GenerationJsons.HeadDocumentFields fields, SelectionIntent savedSelection, Consumer<SelectionIntent> selectionAction, Runnable cancelAction) {
		executeOnClient(() -> Screens.repairSelection(fields, savedSelection, selectionAction, cancelAction));
	}

	private static class Screens {
		private static Screen interactiveParent;
		// A Preparing/Download screen owns the player's attention from the moment one is asked for until a real screen shows again; the loading transition delays that swap, so this flag and not the live screen says when a flow is transient.
		private static boolean transientAttention;
		private static final LoadingTransition LOADING_TRANSITION = new LoadingTransition(ScreenImpl::executeOnClient);

		private static Screen getScreen() {
			/*? if >=26.2 {*/
			return Minecraft.getInstance().gui.screen();
			/*?} else {*/
			/*return Minecraft.getInstance().screen;
			*//*?}*/
		}

		public static void setScreen(Screen screen) {
			// A live login screen must be replaced instantly, without the transition: the vanilla join proceeds under our screens.
			if (getScreen() instanceof ConnectScreen) {
				LOADING_TRANSITION.cancel();
				setScreenNow(screen);
				return;
			}
			if (isTransient(screen)) {
				beginTransient(screen);
				return;
			}
			LOADING_TRANSITION.complete(() -> setScreenNow(screen));
		}

		private static void beginTransient(Screen screen) {
			beginTransientAttention();
			LOADING_TRANSITION.begin(() -> setScreenNow(screen));
		}

		private static void setScreenNow(Screen screen) {
			if (isTransient(screen)) {
				beginTransientAttention();
			} else {
				interactiveParent = null;
				transientAttention = false;
			}
			/*? if >=26.2 {*/
			Minecraft.getInstance().gui.setScreen(screen);
			/*?} else {*/
			/*Minecraft.getInstance().setScreen(screen);
			*//*?}*/
		}

		/** Starts a transient episode: the busy screen owns the display and the screen under it is remembered as the place to return to. */
		private static void beginTransientAttention() {
			if (!transientAttention) interactiveParent = getScreen();
			transientAttention = true;
		}

		/** Where a flow that borrowed the display returns to: the remembered screen during a transient episode, else the screen that is up. */
		private static Screen flowParent() {
			return transientAttention ? interactiveParent : getScreen();
		}

		/** Screens with nowhere honest to return to land on the multiplayer hub. */
		private static Screen returnTarget(Screen parent) {
			return parent == null ? multiplayerScreen() : parent;
		}

		public static void download(DownloadManager downloadManager, String modpackName) {
			Screens.setScreen(new DownloadScreen(downloadManager, modpackName));
		}

		public static void changelog(Screen parent, Changelogs changelogs) {
			Screens.setScreen(new ChangelogScreen(parent, changelogs));
		}

		public static void restart(UpdateType updateType, Changelogs changelogs) {
			Screens.setScreen(new RestartScreen(updateType, changelogs));
		}

		public static void welcome(ModpackUpdater modpackUpdater) {
			Screens.setScreen(new PackConfirmScreen(modpackUpdater));
		}

		public static void preview(UpdatePreview preview, String modpackName, ModpackUpdater updater, Runnable continueAction, Runnable cancelAction) {
			Screen parent = returnTarget(flowParent());
			if (updater != null && (preview.mode() == UpdatePreview.Mode.UPDATE || preview.mode() == UpdatePreview.Mode.ROLLBACK) && updater.planWritesUnverifiedJar(preview.plan())) {
				Screens.setScreen(new PackConfirmScreen(parent, updater, preview, continueAction, cancelAction));
				return;
			}
			Screens.setScreen(new UpdatePreviewScreen(parent, preview, modpackName, updater, continueAction, cancelAction));
		}

		private static boolean isTransient(Screen screen) {
			return screen instanceof PreparingScreen || screen instanceof DownloadScreen;
		}

		public static void history(HistoryViewRequest request) {
			Screens.setScreen(new ContentHistoryScreen(flowParent(), request));
		}

		public static void failure(FailureRequest request) {
			Screen parent = switch (request.returnDestination()) {
				case CURRENT_SCREEN -> returnTarget(flowParent());
				case MULTIPLAYER -> multiplayerScreen();
			};
			CertificatePinMismatchException mismatch = Throwables.findCause(request.cause(), CertificatePinMismatchException.class);
			if (mismatch != null) {
				Screens.setScreen(new PinMismatchScreen(parent, mismatch.getOrigin(), mismatch.getExpectedFingerprint(), mismatch.getPresentedFingerprint()));
				return;
			}
			Screens.setScreen(new ErrorScreen(parent, request));
		}

		public static void multiplayer() {
			Screens.setScreen(multiplayerScreen());
		}

		private static Screen multiplayerScreen() {
			return new JoinMultiplayerScreen(new TitleScreen());
		}

		public static void repairSelection(GenerationJsons.HeadDocumentFields fields, SelectionIntent savedSelection, Consumer<SelectionIntent> selectionAction, Runnable cancelAction) {
			GroupManifest manifest = PackDocument.fromFields(fields).manifest();
			Screens.setScreen(ModpackSelectionScreen.repair(multiplayerScreen(), manifest, savedSelection, selectionAction, cancelAction));
		}

		public static void validation(Screen parent, String fingerprint, String origin, Runnable validated, Runnable canceled) {
			Screens.setScreen(new FingerprintVerificationScreen(parent, fingerprint, origin, validated, canceled));
		}

		public static void originChange(String modpackName, String approvedOrigins, String newOrigin, Runnable allowed, Runnable refused) {
			Screens.setScreen(new OriginChangeConfirmScreen(modpackName, approvedOrigins, newOrigin, allowed, refused));
		}

		public static void detachedJoin(String modpackName, boolean headMatchesActive, Runnable continueJoin, Runnable syncNow) {
			Screens.setScreen(new DetachedJoinPromptScreen(Screens.getScreen(), modpackName, headMatchesActive, continueJoin, syncNow));
		}

		public static void waiting(Runnable onCancel) {
			Screens.setScreen(new PreparingScreen(() -> {
				if (onCancel != null) onCancel.run();
				Screens.setScreen(returnTarget(interactiveParent));
			}));
		}
	}
}
