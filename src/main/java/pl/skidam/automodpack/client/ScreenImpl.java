package pl.skidam.automodpack.client;

import pl.skidam.automodpack.client.ui.*;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_core.utils.FetchManager;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.screen.ScreenService;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

import java.util.Optional;
import java.util.Locale;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.gui.screens.TitleScreen;
import net.minecraft.client.gui.screens.multiplayer.JoinMultiplayerScreen;

public class ScreenImpl implements ScreenService {

	private static void executeOnClient(Runnable task) {
		Minecraft.getInstance().execute(task);
	}

	@Override
	public void download(Object... args) {
		executeOnClient(() -> Screens.download(args[0], args[1]));
	}

	@Override
	public void changelog(Object... args) {
		executeOnClient(() -> Screens.changelog(args[0], args[1]));
	}

	@Override
	public void restart(Object... args) {
		executeOnClient(() -> Screens.restart(args[1], args[2]));
	}

	@Override
	public void danger(Object... args) {
		executeOnClient(() -> Screens.danger(args[0]));
	}

	@Override
	public void welcome(Object... args) {
		executeOnClient(() -> Screens.welcome(args[0]));
	}

	@Override
	public boolean preview(Object... args) {
		executeOnClient(() -> Screens.preview(args));
		return true;
	}

	@Override
	public void recovery(Object... args) {
		executeOnClient(() -> Screens.recovery(args));
	}

	@Override
	public void history(Object... args) {
		executeOnClient(() -> Screens.history(args));
	}

	@Override
	public void error(String... args) {
		executeOnClient(() -> Screens.error(args));
	}

	@Override
	public void menu(Object... args) {
		executeOnClient(() -> Screens.menu(args.length > 0 ? args[0] : null));
	}

	@Override
	public void title(Object... args) {
		executeOnClient(Screens::title);
	}

	@Override
	public void validation(Object... args) {
		executeOnClient(() -> Screens.validation(args[0], args[1], args[2], args[3]));
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

	private static class Screens {
		private static Screen interactiveParent;

		private static Screen getScreen() {
			/*? if >=26.2 {*/
			return Minecraft.getInstance().gui.screen();
			/*?} else {*/
			/*return Minecraft.getInstance().screen;
			*//*?}*/
		}

		public static void setScreen(Screen screen) {
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

		public static void download(Object downloadManager, Object header) {
			Screens.setScreen(new DownloadScreen((DownloadManager) downloadManager, (String) header));
		}

		public static void changelog(Object parent, Object changelog) {
			Screens.setScreen(new ChangelogScreen((Screen) parent, (Changelogs) changelog));
		}

		public static void restart(Object updateType, Object changelogs) {
			Screens.setScreen(new RestartScreen((UpdateType) updateType, (Changelogs) changelogs));
		}

		public static void danger(Object modpackUpdaterInstance) {
			Screens.setScreen(new DangerScreen((ModpackUpdater) modpackUpdaterInstance));
		}

		public static void welcome(Object modpackUpdaterInstance) {
			Screens.setScreen(new FirstConnectScreen((ModpackUpdater) modpackUpdaterInstance));
		}

		public static void preview(Object... args) {
			Screen parent = Screens.getScreen();
			if (isTransient(parent)) parent = interactiveParent;
			interactiveParent = null;
			boolean removal = args.length > 4 && Boolean.TRUE.equals(args[4]);
			FetchManager sourceFetchManager = args.length > 5 && args[5] instanceof FetchManager manager
					? manager
					: null;
			Screens.setScreen(new UpdatePreviewScreen(parent, (UpdatePreview) args[0], (String) args[1], removal, (Runnable) args[2], (Runnable) args[3], sourceFetchManager));
		}

		private static boolean isTransient(Screen screen) {
			return screen instanceof PreparingScreen || screen instanceof DownloadScreen;
		}

		public static void recovery(Object... args) {
			Screen parent = Screens.getScreen();
			Runnable closed = args.length > 3 && args[3] instanceof Runnable callback ? callback : () -> {};
			Screens.setScreen(new RecoveryArchiveScreen(parent, (ModpackUpdater) args[0], (ModpackUpdater.RecoverySnapshot) args[1], (String) args[2], closed));
		}

		public static void history(Object... args) {
			Screen parent = Screens.getScreen();
			Runnable closed = args.length > 2 && args[2] instanceof Runnable callback ? callback : () -> {};
			@SuppressWarnings("unchecked")
			java.util.List<GenerationRecord> history = (java.util.List<GenerationRecord>) args[0];
			Screens.setScreen(new ContentHistoryScreen(parent, history, (String) args[1], closed));
		}

		public static void error(String... errors) {
			Screens.setScreen(new ErrorScreen(errors));
		}

		public static void title() {
			Screens.setScreen(new TitleScreen());
		}

		public static void multiplayer() {
			Screens.setScreen(new JoinMultiplayerScreen(new TitleScreen()));
		}

		public static void menu(Object parent) {
			Screens.setScreen(ModpackSelectionScreen.forSelectedModpack((Screen) parent));
		}

		public static void validation(Object parent, Object serverFingerprint, Object validatedCallback, Object canceledCallback) {
			Screens.setScreen(new FingerprintVerificationScreen((Screen) parent, (String) serverFingerprint, (Runnable) validatedCallback, (Runnable) canceledCallback));
		}

		public static void waiting() {
			Screens.setScreen(new PreparingScreen());
		}
	}
}
