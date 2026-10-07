package pl.skidam.automodpack.client.ui.screen;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import net.minecraft.client.Minecraft;

import pl.skidam.automodpack.client.audio.AudioManager;
import pl.skidam.automodpack_core.client.StoredModpackConnection;
import pl.skidam.automodpack_core.client.WaitingMusic;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.screen.ScreenManager;
import pl.skidam.automodpack_core.update.ClientStorage;

/**
 * One screen's waiting-track playback, start to stop: the refresh runs off the player thread, the generation token
 * cancels a superseded refresh, and the route lives exactly as long as the play that opened it. The screen names the
 * action and renders {@link MusicState}; it holds no async state of its own.
 */
final class WaitingMusicPlayer {
	private final ClientStorage storage;
	private WaitingMusicListen listen;
	private int generation;
	private boolean busy;
	private boolean fetching;
	private boolean refreshFailed;
	private boolean closed;

	WaitingMusicPlayer(ClientStorage storage) {
		this.storage = storage;
	}

	record MusicState(boolean playing, boolean busy, boolean fetching, boolean refreshFailed) {}

	MusicState state() {
		return new MusicState(AudioManager.isMusicPlaying(), busy, fetching, refreshFailed);
	}

	/** Opens the pack's route and plays what it advertises; a press while one is already starting does nothing. */
	void play(InstalledModpackController.Pack pack) {
		if (closed || busy) return;
		release();
		busy = true;
		fetching = true;
		int started = ++generation;
		ScreenManager.background(() -> {
			WaitingMusicListen opened = openWaitingMusic(pack);
			Minecraft.getInstance().execute(() -> {
				if (closed || started != generation) {
					opened.close();
					return;
				}
				listen = opened;
				refreshFailed = opened instanceof WaitingMusicListen.NotYetStored;
				if (opened instanceof WaitingMusicListen.Refreshed refreshed) WaitingMusic.start(refreshed.transport(), storage, refreshed.sha1());
				else WaitingMusic.bundled(storage, ((WaitingMusicListen.NotYetStored) opened).sha1());
				AudioManager.playMusic();
				busy = false;
				fetching = false;
			});
		});
	}

	/** The preview's stop: playback ends and stays a preview, so the remembered preference is untouched. */
	void stop() {
		release();
	}

	/** Leaving the screen releases the route and the audio without touching the remembered preference. */
	void close() {
		closed = true;
		release();
	}

	private void release() {
		generation++;
		AudioManager.stopMusic();
		WaitingMusic.endRun();
		if (listen != null) {
			listen.close();
			listen = null;
		}
		busy = false;
		fetching = false;
		refreshFailed = false;
	}

	private WaitingMusicListen openWaitingMusic(InstalledModpackController.Pack pack) {
		try {
			StoredModpackConnection connection = StoredModpackConnection.open(storage, pack.modpackId(), false);
			PackTransport transport = connection.transport();
			if (transport == null) {
				connection.close();
				LOGGER.warn("The connection to {} answered without a transfer route; playing the stored track or the bundled track", pack.modpackId());
				return new WaitingMusicListen.NotYetStored(pack.record().waitingMusicSha1());
			}
			return new WaitingMusicListen.Refreshed(connection, transport, connection.advertisedFields().waitingMusicSha1);
		} catch (Exception e) {
			LOGGER.warn("Could not refresh waiting music from the server; playing the stored track or the bundled track", e);
			return new WaitingMusicListen.NotYetStored(pack.record().waitingMusicSha1());
		}
	}

	sealed interface WaitingMusicListen extends AutoCloseable {
		@Override
		void close();

		record Refreshed(StoredModpackConnection connection, PackTransport transport, String sha1) implements WaitingMusicListen {
			@Override
			public void close() {
				connection.close();
			}
		}

		record NotYetStored(String sha1) implements WaitingMusicListen {
			@Override
			public void close() {}
		}
	}
}
