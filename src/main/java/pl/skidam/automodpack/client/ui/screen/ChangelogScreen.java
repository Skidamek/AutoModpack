package pl.skidam.automodpack.client.ui.screen;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.client.Changelogs;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_core.screen.HistoryViewRequest;
import pl.skidam.automodpack_core.screen.ScreenManager;

/** Shows the applied file changes through the same browser as previews and installed catalogues. */
public final class ChangelogScreen extends ChangeBrowserScreen {
	private boolean groupNamesLoaded;
	private Future<?> groupNamesLoad;

	public ChangelogScreen(Screen parent, Changelogs changelogs) {
		super(parent, VersionedText.text("automodpack.changelog.title"),
				VersionedText.text("automodpack.changelog.latestNote", latestNote(changelogs)), changelogs.changeSet(), Map.of(),
				new BrowserAction(VersionedText.text("automodpack.management.history"),
						screen -> ScreenImpl.setScreen(new ContentHistoryScreen(screen, new HistoryViewRequest(changelogs.journal(), newestSeq(changelogs.journal()), "", () -> {}))),
						!changelogs.journal().isEmpty()));
		if (AudioManager.isMusicPlaying()) AudioManager.stopMusic();
	}

	@Override
	protected void init() {
		super.init();
		if (groupNamesLoaded || groupNamesLoad != null) return;
		groupNamesLoad = ScreenManager.background(() -> {
			Map<String, String> loaded = new InstalledModpackController().activeGroupNames();
			this.minecraft.execute(() -> {
				groupNamesLoaded = true;
				groupNamesLoad = null;
				setGroupNames(loaded);
				rebuild();
			});
		});
	}

	/** The applied update sits at the head of the journal, so it is the generation the game currently runs. */
	private static long newestSeq(List<JournalEntry> journal) {
		return journal.isEmpty() ? -1 : journal.get(journal.size() - 1).seq();
	}

	private static String latestNote(Changelogs changelogs) {
		String notes = changelogs.latestPatchNotes();
		if (notes.isBlank()) return VersionedText.str("automodpack.patchNotes.none");
		return notes.split("\\R", -1)[0];
	}
}
