package pl.skidam.automodpack.client.ui.screen;

import java.util.Map;

import net.minecraft.client.gui.screens.Screen;

import pl.skidam.automodpack.client.ScreenImpl;
import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack.client.ui.*;
import pl.skidam.automodpack.client.ui.versioned.VersionedText;
import pl.skidam.automodpack_loader_core.client.Changelogs;

/** Shows the applied file changes through the same browser as previews and installed catalogues. */
public final class ChangelogScreen extends ChangeBrowserScreen {
	public ChangelogScreen(Screen parent, Changelogs changelogs) {
		super(parent, VersionedText.translatable("automodpack.changelog.title"),
				VersionedText.translatable("automodpack.changelog.latestNote", latestNote(changelogs)), changelogs.changeSet(), Map.of(),
				new BrowserAction(VersionedText.translatable("automodpack.patchNotes.all"),
						screen -> ScreenImpl.setScreen(new PatchNotesHistoryScreen(screen, changelogs.patchNotesHistory(), "")),
						!changelogs.patchNotesHistory().isEmpty()));
		if (AudioManager.isMusicPlaying()) AudioManager.stopMusic();
	}

	private static String latestNote(Changelogs changelogs) {
		String notes = changelogs.latestPatchNotes();
		if (notes.isBlank()) return VersionedText.translatable("automodpack.patchNotes.none").getString();
		return notes.split("\\R", -1)[0];
	}
}
