package pl.skidam.automodpack.client.audio;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.FloatControl;
import javax.sound.sampled.SourceDataLine;

import net.minecraft.client.sounds.AudioStream;
/*? if <1.21.1 {*/
/*import com.mojang.blaze3d.audio.OggAudioStream;
*//*?} else {*/
import net.minecraft.client.sounds.JOrbisAudioStream;
/*?}*/

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import pl.skidam.automodpack_core.Constants;

/**
 * The waiting-music loop, decoded straight from our jar with Minecraft's own vorbis decoder and played through a
 * java sound line. Deliberately not the vanilla sound engine: playing through it needed FAPI's registry module, and
 * a server-pushed resource pack could override the sound definition or the ogg itself. Everything here runs on one
 * daemon thread; the gui thread only starts and stops it, so playMusic() never stutters the frame. In-game only -
 * the decoder classes do not exist during preload.
 */
public class AudioManager {
	private static final String MUSIC_PATH = "/assets/automodpack/sounds/music/waiting.ogg";
	/** The old sound-engine instance played at a fixed 0.25 gain; the slider scaling keeps that ceiling. */
	private static final float GAIN_SCALE = 0.25f;
	/** The line buffer and write chunk size; one chunk is also one volume poll, so this keeps the poll around 0.5s. */
	private static final int WRITE_CHUNK = 1 << 16;

	private static final Object LOCK = new Object();
	private static volatile Loop PLAYER;

	/** Kept so every loader's init call site stays identical; the loop itself starts lazily in playMusic(). */
	public AudioManager() {}

	public static void playMusic() {
		synchronized (LOCK) {
			if (PLAYER != null) return;
			Loop loop = new Loop();
			PLAYER = loop;
			Thread thread = new Thread(loop, "AutoModpack waiting music");
			thread.setDaemon(true);
			thread.start();
		}
	}

	public static void stopMusic() {
		Loop loop;
		synchronized (LOCK) {
			loop = PLAYER;
			if (loop == null) return;
			PLAYER = null;
		}
		loop.stop();
	}

	public static boolean isMusicPlaying() {
		return PLAYER != null;
	}

	/** One playMusic() session: decodes the bundled ogg to raw PCM once, then writes it to a source line in chunks, looping from the top of the buffer forever until stopMusic() drops the reference. */
	private static final class Loop implements Runnable {
		private volatile boolean stopped = false;
		private volatile Thread thread;
		private volatile AudioFormat format;

		void stop() {
			stopped = true;
			Thread thread = this.thread;
			if (thread != null && thread != Thread.currentThread()) {
				try {
					thread.join(500);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}

		@Override
		public void run() {
			this.thread = Thread.currentThread();
			try {
				playLoop();
			} catch (Exception e) {
				Constants.LOGGER.error("The waiting music loop crashed", e);
			} finally {
				if (PLAYER == this) PLAYER = null;
			}
		}

		private void playLoop() {
			byte[] pcm = decode();
			if (pcm == null) return;
			SourceDataLine line = openLine(this.format);
			if (line == null) return;
			try {
				while (!stopped) {
					for (int offset = 0; offset < pcm.length && !stopped; offset += WRITE_CHUNK) {
						line.write(pcm, offset, Math.min(WRITE_CHUNK, pcm.length - offset));
						applyVolume(line);
					}
				}
			} finally {
				line.close();
			}
		}

		/** Decodes the whole ogg through Minecraft's vorbis decoder; the PCM stays resident so looping never re-decodes. */
		private byte[] decode() {
			try (InputStream input = AudioManager.class.getResourceAsStream(MUSIC_PATH); AudioStream stream = openStream(input)) {
				this.format = stream.getFormat();
				ByteArrayOutputStream pcm = new ByteArrayOutputStream();
				ByteBuffer chunk;
				while ((chunk = stream.read(WRITE_CHUNK)) != null && chunk.hasRemaining()) {
					byte[] bytes = new byte[chunk.remaining()];
					chunk.get(bytes);
					pcm.write(bytes);
				}
				return pcm.toByteArray();
			} catch (Exception e) {
				Constants.LOGGER.error("Failed to decode the bundled waiting music from {}", MUSIC_PATH, e);
				return null;
			}
		}

		private AudioStream openStream(InputStream input) throws Exception {
			/*? if <1.21.1 {*/
			/*return new OggAudioStream(input);
			*//*?} else {*/
			return new JOrbisAudioStream(input);
			/*?}*/
		}

		private SourceDataLine openLine(AudioFormat format) {
			if (format == null) return null;
			try {
				SourceDataLine line = AudioSystem.getSourceDataLine(format);
				line.open(format, WRITE_CHUNK);
				line.start();
				return line;
			} catch (Exception e) {
				// A machine with no audio output must not take the mod down with it; the music is optional by nature.
				Constants.LOGGER.error("Failed to open a sound line for the waiting music", e);
				return null;
			}
		}

		/** Follows the client's music slider; at zero the loop keeps running inaudibly so raising the slider resumes it. */
		private void applyVolume(SourceDataLine line) {
			try {
				if (!line.isControlSupported(FloatControl.Type.MASTER_GAIN)) return;
				FloatControl gain = (FloatControl) line.getControl(FloatControl.Type.MASTER_GAIN);
				float slider = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MUSIC) * GAIN_SCALE;
				float decibels = slider <= 0.0001f ? gain.getMinimum() : (float) (20.0 * Math.log10(slider));
				gain.setValue(Math.max(gain.getMinimum(), Math.min(gain.getMaximum(), decibels)));
			} catch (IllegalArgumentException e) {
				// The control can vanish mid-playback; keep playing at its current gain until the next chunk.
			}
		}
	}
}
