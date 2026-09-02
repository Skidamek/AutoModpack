package pl.skidam.automodpack.client;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack.client.ui.*;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.GroupManifest;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.ScreenService;
import pl.skidam.automodpack_loader_core.screen.HistoricalCatalogueLoader;
import pl.skidam.automodpack_loader_core.screen.FailureDestination;
import pl.skidam.automodpack_loader_core.screen.FailureRequest;
import pl.skidam.automodpack_loader_core.screen.HistoryViewRequest;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.util.Optional;
import java.util.Locale;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

public class ScreenImpl implements ScreenService {

	private static void executeOnClient(Runnable task) {
		Minecraft.getInstance().execute(task);
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
	public boolean preview(UpdatePreview preview, String modpackName, Runnable continueAction, Runnable cancelAction, boolean returnToSelection,
			Map<UpdatePlan.FileKey, List<String>> mainPageUrls) {
		executeOnClient(() -> Screens.preview(preview, modpackName, continueAction, cancelAction, returnToSelection, mainPageUrls));
		return true;
	}

	@Override
	public void recovery(ModpackUpdater modpackUpdater, ModpackUpdater.RecoverySnapshot recoverySnapshot, String modpackName, Runnable closed) {
		executeOnClient(() -> Screens.recovery(modpackUpdater, recoverySnapshot, modpackName, closed));
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
	public void title() {
		executeOnClient(Screens::title);
	}

	@Override
	public void validation(Object parent, String fingerprint, Runnable validated, Runnable canceled) {
		executeOnClient(() -> Screens.validation((Screen) parent, fingerprint, validated, canceled));
	}

	@Override
	public void waiting() {
		executeOnClient(Screens::waiting);
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

	public static void repairSelection(ModpackJsons.CompleteModpackContentFields fields, SelectionIntent savedSelection, Consumer<SelectionIntent> selectionAction, Runnable cancelAction) {
		executeOnClient(() -> Screens.repairSelection(fields, savedSelection, selectionAction, cancelAction));
	}

	private static class Screens {
		private static Screen interactiveParent;
		private static final LoadingTransition LOADING_TRANSITION = new LoadingTransition(ScreenImpl::executeOnClient);

		private static Screen getScreen() {
			/*? if >=26.2 {*/
			return Minecraft.getInstance().gui.screen();
			/*?} else {*/
			/*return Minecraft.getInstance().screen;
			*//*?}*/
		}

		public static void setScreen(Screen screen) {
			if (isTransient(screen)) {
				beginTransient(screen);
				return;
			}
			LOADING_TRANSITION.complete(() -> setScreenNow(screen));
		}

		private static void beginTransient(Screen screen) {
			Screen current = Screens.getScreen();
			if (!isTransient(current)) interactiveParent = current;
			LOADING_TRANSITION.begin(() -> setScreenNow(screen));
		}

		private static void setScreenNow(Screen screen) {
			Screen current = Screens.getScreen();
			if (isTransient(screen)) {
				if (!isTransient(current)) interactiveParent = current;
			} else {
				interactiveParent = null;
			}
			/*? if >=26.2 {*/
			Minecraft.getInstance().gui.setScreen(screen);
			/*?} else {*/
			/*Minecraft.getInstance().setScreen(screen);
			*//*?}*/
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
			Screens.setScreen(new FirstConnectScreen(modpackUpdater));
		}

		public static void preview(UpdatePreview preview, String modpackName, Runnable continueAction, Runnable cancelAction, boolean returnToSelection,
				Map<UpdatePlan.FileKey, List<String>> mainPageUrls) {
			Screen parent = Screens.getScreen();
			if (isTransient(parent)) parent = interactiveParent;
			parent = previewParent(parent);
			interactiveParent = null;
			Screens.setScreen(new UpdatePreviewScreen(parent, preview, modpackName, returnToSelection, continueAction, cancelAction, mainPageUrls));
		}

		private static Screen previewParent(Screen parent) {
			if (parent instanceof FirstConnectScreen) return parent;
			if (parent instanceof ModpackSelectionScreen selection && (!selection.isUpdateFlow() || selection.isConfirmationFlow())) return parent;
			return multiplayerScreen();
		}

		private static boolean isTransient(Screen screen) {
			return screen instanceof PreparingScreen || screen instanceof DownloadScreen;
		}

		public static void recovery(ModpackUpdater modpackUpdater, ModpackUpdater.RecoverySnapshot recoverySnapshot, String modpackName, Runnable closed) {
			Screen parent = Screens.getScreen();
			Screens.setScreen(new RecoveryArchiveScreen(parent, modpackUpdater, recoverySnapshot, modpackName, closed));
		}

		public static void history(HistoryViewRequest request) {
			Screen parent = Screens.getScreen();
			Screens.setScreen(new ContentHistoryScreen(parent, request.historyIndex(), request.availableHistory(), request.modpackName(), request.catalogueLoader(), request.closed()));
		}

		public static void failure(FailureRequest request) {
			Screen parent = Screens.getScreen();
			if (isTransient(parent)) parent = interactiveParent;
			parent = switch (request.returnDestination()) {
				case CURRENT_SCREEN -> parent;
				case MULTIPLAYER -> multiplayerScreen();
				case TITLE -> new TitleScreen();
			};
			Screens.setScreen(new ErrorScreen(parent, request));
		}

		public static void title() {
			Screens.setScreen(new TitleScreen());
		}

		public static void multiplayer() {
			Screens.setScreen(multiplayerScreen());
		}

		private static Screen multiplayerScreen() {
			return new JoinMultiplayerScreen(new TitleScreen());
		}

		public static void repairSelection(ModpackJsons.CompleteModpackContentFields fields, SelectionIntent savedSelection, Consumer<SelectionIntent> selectionAction, Runnable cancelAction) {
			GroupManifest manifest = GenerationRecord.fromFields(fields).manifest();
			Screens.setScreen(ModpackSelectionScreen.repair(multiplayerScreen(), manifest, savedSelection, selectionAction, cancelAction));
		}

		public static void validation(Screen parent, String fingerprint, Runnable validated, Runnable canceled) {
			Screens.setScreen(new FingerprintVerificationScreen(parent, fingerprint, validated, canceled));
		}

		public static void waiting() {
			Screens.setScreen(new PreparingScreen());
		}
	}
}
