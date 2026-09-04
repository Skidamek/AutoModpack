package pl.skidam.automodpack.client.ui.screen;

import java.util.List;
import java.util.Map;

import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_core.modpack.generation.JournalEntry;
import pl.skidam.automodpack_loader_core.client.Changelogs;
import pl.skidam.automodpack_loader_core.screen.HistoryViewRequest;

/** Shows the applied file changes through the same browser as previews and installed catalogues. */
public final class ChangelogScreen extends ChangeBrowserScreen {
	public ChangelogScreen(Screen parent, Changelogs changelogs) {
		super(parent, VersionedText.translatable("automodpack.changelog.title"),
				VersionedText.translatable("automodpack.changelog.latestNote", latestNote(changelogs)), changelogs.changeSet(), Map.of(),
				new BrowserAction(VersionedText.translatable("automodpack.management.history"),
						screen -> ScreenImpl.setScreen(new ContentHistoryScreen(screen, new HistoryViewRequest(changelogs.journal(), newestSeq(changelogs.journal()), "", () -> {}))),
						!changelogs.journal().isEmpty()));
		if (AudioManager.isMusicPlaying()) AudioManager.stopMusic();
	}

	/** The applied update sits at the head of the journal, so it is the generation the game currently runs. */
	private static long newestSeq(List<JournalEntry> journal) {
		return journal.isEmpty() ? -1 : journal.get(journal.size() - 1).seq();
	}

	private static String latestNote(Changelogs changelogs) {
		String notes = changelogs.latestPatchNotes();
		if (notes.isBlank()) return VersionedText.translatable("automodpack.patchNotes.none").getString();
		return notes.split("\\R", -1)[0];
	}
}
