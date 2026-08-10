package pl.skidam.automodpack_loader_core.screen;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.update.UpdatePlan;
import pl.skidam.automodpack_core.update.UpdatePreview;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.client.ModpackUpdater;
import pl.skidam.automodpack_loader_core.utils.DownloadManager;
import pl.skidam.automodpack_loader_core.utils.UpdateType;

public class ScreenManager implements ScreenService {

	public static ScreenService INSTANCE = new PreloadScreenImpl();

	@Override
	public void download(DownloadManager downloadManager, String modpackName) {
		INSTANCE.download(downloadManager, modpackName);
	}

	@Override
	public void changelog(Object parent, Changelogs changelogs) {
		INSTANCE.changelog(parent, changelogs);
	}

	@Override
	public void restart(UpdateType updateType, Changelogs changelogs) {
		INSTANCE.restart(updateType, changelogs);
	}

	@Override
	public void completeWithoutRestart() {
		INSTANCE.completeWithoutRestart();
	}

	@Override
	public void welcome(ModpackUpdater modpackUpdater) {
		INSTANCE.welcome(modpackUpdater);
	}

	@Override
	public boolean preview(UpdatePreview preview, String modpackName, Runnable continueAction, Runnable cancelAction, boolean returnToSelection,
			Map<UpdatePlan.FileKey, List<String>> mainPageUrls) {
		return INSTANCE.preview(preview, modpackName, continueAction, cancelAction, returnToSelection, mainPageUrls);
	}

	@Override
	public void recovery(ModpackUpdater modpackUpdater, ModpackUpdater.RecoverySnapshot recoverySnapshot, String modpackName, Runnable closed) {
		INSTANCE.recovery(modpackUpdater, recoverySnapshot, modpackName, closed);
	}

	@Override
	public void history(List<GenerationRecord> history, String modpackName, List<GenerationPatchNoteHistory.Entry> patchNotesHistory, Runnable closed) {
		INSTANCE.history(history, modpackName, patchNotesHistory, closed);
	}

	@Override
	public void error(String... args) {
		INSTANCE.error(args);
	}

	@Override
	public void title() {
		INSTANCE.title();
	}

	@Override
	public void validation(Object parent, String fingerprint, Runnable validated, Runnable canceled) {
		INSTANCE.validation(parent, fingerprint, validated, canceled);
	}

	@Override
	public void waiting() {
		INSTANCE.waiting();
	}

	@Override
	public Optional<String> getScreenString() {
		return INSTANCE.getScreenString();
	}

	@Override
	public Optional<Object> getScreen() {
		return INSTANCE.getScreen();
	}
}
