package pl.skidam.automodpack.client.audio;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import javax.sound.sampled.AudioFormat;

import org.lwjgl.openal.AL10;
import org.lwjgl.openal.ALC;
import org.lwjgl.openal.ALC10;
import org.lwjgl.openal.ALCCapabilities;

import net.minecraft.client.sounds.AudioStream;
/*? if <1.21.1 {*/
/*import com.mojang.blaze3d.audio.OggAudioStream;
*//*?} else {*/
import net.minecraft.client.sounds.JOrbisAudioStream;
/*?}*/

import net.minecraft.client.Minecraft;
import net.minecraft.sounds.SoundSource;

import pl.skidam.automodpack_core.Constants;
import pl.skidam.automodpack_core.utils.Assets;

/**
 * The waiting-music loop, decoded straight from our jar with Minecraft's own vorbis decoder and played through OpenAL
 * on a private device and context - the same LWJGL natives and driver path the game's own sound engine uses, so music
 * works wherever the game's music does (a javax sound line was the fragile link: game-bundled JVMs and PipeWire/Pulse
 * Linux stacks left it silent). Deliberately not the vanilla sound engine: playing through it needed FAPI's registry
 * module, and a server-pushed resource pack could override the sound definition or the ogg itself. Everything here,
 * including context creation and teardown, runs on one daemon thread because AL contexts are thread-local; the gui
 * thread only starts and stops it, so playMusic() never stutters the frame. In-game only - the decoder classes do not
 * exist during preload.
 */
public class AudioManager {
	private static final String MUSIC_PATH = "/assets/automodpack/sounds/music/waiting.ogg";
	/** The old sound-engine instance played at a fixed 0.25 gain; the slider scaling keeps that ceiling. */
	private static final float GAIN_SCALE = 0.25f;
	/** The decoded-PCM chunk one AL buffer carries; the bundled 32kHz stereo s16 ogg decodes to 128000 bytes/s, so one chunk is about 0.5s of audio. */
	private static final int WRITE_CHUNK = 1 << 16;
	/** Buffers kept queued ahead of the source; 4 chunks is about one second of audio, plenty of slack for the 50ms feed cadence. */
	private static final int BUFFER_COUNT = 4;
	/** How often the feed loop wakes to refill buffers and follow the slider. */
	private static final long FEED_INTERVAL_MS = 50;

	private static final Object LOCK = new Object();
	private static volatile Loop PLAYER;

	/** Kept so every loader's init call site stays identical; the loop itself starts lazily in playMusic(). */
	public AudioManager() {}

	public static void playMusic() {
		synchronized (LOCK) {
			if (PLAYER != null) return;
			// Menu music may already be in flight from before this screen; the tracker mixin only blocks
			// new vanilla tracks from starting, so an already-playing one gets stopped here, music source
			// only - every other sound stays untouched.
			Minecraft.getInstance().getSoundManager().stop(null, SoundSource.MUSIC);
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

	/** One playMusic() session: opens a private OpenAL device and context, decodes the bundled ogg to raw PCM once, streams it through a ring of AL buffers looping forever, and tears the context and device back down on exit. */
	private static final class Loop implements Runnable {
		private volatile boolean stopped = false;
		private volatile Thread thread;
		private AudioFormat format;

		void stop() {
			stopped = true;
			Thread thread = this.thread;
			if (thread != null && thread != Thread.currentThread()) {
				try {
					thread.join(1000);
				} catch (InterruptedException e) {
					Thread.currentThread().interrupt();
				}
			}
		}

		@Override
		public void run() {
			this.thread = Thread.currentThread();
			try {
				playSession();
			} catch (Exception e) {
				Constants.LOGGER.error("The waiting music loop crashed", e);
			} finally {
				if (PLAYER == this) PLAYER = null;
			}
		}

		private void playSession() {
			byte[] pcm = decode();
			if (pcm == null || stopped) return;
			int format = openAlFormat(this.format);
			if (format == AL10.AL_NONE) return;
			Output output = Output.open();
			if (output == null) return;
			try (output) {
				streamLoop(pcm, format, output);
			}
		}

	/** Feeds the source's buffer ring until stopped: refill processed buffers from the PCM, wrapping at its end; a source that starved to AL_STOPPED just gets replayed. */
	private void streamLoop(byte[] pcm, int format, Output output) {
		// alBufferData reads straight from the buffer's native address, so the staging buffer must be direct - a heap
		// ByteBuffer.wrap carries address 0 and the driver copies from null.
		ByteBuffer staging = ByteBuffer.allocateDirect(WRITE_CHUNK);
		int position = 0;
		for (int buffer : output.buffers()) {
			position = fill(pcm, format, buffer, staging, position);
			output.queue(buffer);
		}
		output.play();
		while (!stopped) {
			for (int buffer : output.takeProcessed()) {
				position = fill(pcm, format, buffer, staging, position);
				output.queue(buffer);
			}
			if (output.stoppedByStarvation()) output.play();
			output.applyVolume();
			sleepWhile();
		}
		output.stopSource();
	}

	/** Copies one WRITE_CHUNK of PCM through the direct staging buffer into the AL buffer, wrapping back to the top when the loop point passes; returns the position after this fill. */
	private int fill(byte[] pcm, int format, int buffer, ByteBuffer staging, int position) {
		if (position >= pcm.length) position = 0;
		int size = Math.min(WRITE_CHUNK, pcm.length - position);
		staging.clear().put(pcm, position, size).flip();
		AL10.alBufferData(buffer, format, staging, (int) this.format.getSampleRate());
		return position + size;
	}

		private void sleepWhile() {
			try {
				Thread.sleep(FEED_INTERVAL_MS);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				stopped = true;
			}
		}

		/** Our private OpenAL stack: device, context current on the music thread only, one source and its buffer ring. */
		private record Output(long device, long context, int source, int[] buffers) implements AutoCloseable {

			/** Mirrors Minecraft's blaze3d Library init on the default output device; null means the machine has no usable audio output and the music is skipped. */
			static Output open() {
				long device = 0;
				long context = 0;
				try {
					device = ALC10.alcOpenDevice((ByteBuffer) null);
					if (device == 0) {
						Constants.LOGGER.error("No OpenAL device for the waiting music; skipping it");
						return null;
					}
					ALCCapabilities alc = ALC.createCapabilities(device);
					if (!alc.ALC_EXT_thread_local_context) {
						// Without the thread-local extension the only way to make a context current is the
						// process-global slot, which would steal it from the game's own sound engine.
						Constants.LOGGER.error("OpenAL on this machine lacks ALC_EXT_thread_local_context; skipping the waiting music");
						ALC10.alcCloseDevice(device);
						return null;
					}
					context = ALC10.alcCreateContext(device, (IntBuffer) null);
					if (context == 0 || !threadLocalContext(alc, context)) {
						Constants.LOGGER.error("No OpenAL context for the waiting music; skipping it");
						ALC10.alcDestroyContext(context);
						ALC10.alcCloseDevice(device);
						return null;
					}
					int source = AL10.alGenSources();
					int[] buffers = new int[BUFFER_COUNT];
					boolean voiced = source != 0;
					for (int i = 0; i < BUFFER_COUNT; i++) {
						buffers[i] = AL10.alGenBuffers();
						voiced &= buffers[i] != 0;
					}
					if (!voiced) {
						Constants.LOGGER.error("OpenAL gave no voice for the waiting music (source {}, buffers {}); skipping it", source, buffers);
						AL10.alDeleteSources(source);
						AL10.alDeleteBuffers(buffers);
						ALC10.alcDestroyContext(context);
						ALC10.alcCloseDevice(device);
						return null;
					}
					return new Output(device, context, source, buffers);
				} catch (Exception e) {
					// A machine with no audio output must not take the mod down with it; the music is optional by nature.
					Constants.LOGGER.error("Failed to open OpenAL for the waiting music", e);
					ALC10.alcDestroyContext(context);
					ALC10.alcCloseDevice(device);
					return null;
				}
			}

			void play() {
				AL10.alSourcePlay(this.source);
			}

			void stopSource() {
				AL10.alSourceStop(this.source);
			}

			/** The buffers whose audio the source finished with, ready to be refilled. */
			int[] takeProcessed() {
				int processed = AL10.alGetSourcei(this.source, AL10.AL_BUFFERS_PROCESSED);
				if (processed <= 0) return new int[0];
				int[] recycled = new int[processed];
				AL10.alSourceUnqueueBuffers(this.source, recycled);
				return recycled;
			}

			void queue(int buffer) {
				AL10.alSourceQueueBuffers(this.source, new int[]{buffer});
			}

			boolean stoppedByStarvation() {
				return AL10.alGetSourcei(this.source, AL10.AL_SOURCE_STATE) == AL10.AL_STOPPED;
			}

			/** ALC_EXT_thread_local_context's alcSetThreadContext, reached through its raw function pointer because LWJGL does not wrap it; a zero context clears this thread's slot. The process-global context the game uses is never touched. */
			private static boolean threadLocalContext(ALCCapabilities alc, long context) {
				return org.lwjgl.system.JNI.invokePI(context, alc.alcSetThreadContext) != 0;
			}

			/** Follows the client's music slider; at zero the loop keeps running inaudibly so raising the slider resumes it. */
			void applyVolume() {
				float gain = Minecraft.getInstance().options.getSoundSourceVolume(SoundSource.MUSIC) * GAIN_SCALE;
				AL10.alSourcef(this.source, AL10.AL_GAIN, gain);
			}

			/** The thread that owns the context tears it down, so stopMusic() only joins the thread; deleting the source and buffers first keeps the driver quiet about live objects. */
			@Override
			public void close() {
				try {
					AL10.alSourceStop(this.source);
					AL10.alDeleteSources(this.source);
					AL10.alDeleteBuffers(this.buffers);
				} catch (Exception e) {
					Constants.LOGGER.warn("Failed to release the waiting music's OpenAL objects", e);
				}
				// The context is current on this thread only through the thread-local slot; clear it or
				// alcDestroyContext refuses, and the process-global context the game owns is never ours.
				threadLocalContext(ALC.createCapabilities(this.device), 0);
				ALC10.alcDestroyContext(this.context);
				ALC10.alcCloseDevice(this.device);
			}
		}

		/** Maps the decoded format onto OpenAL's s16 constants; the bundled ogg is 32kHz stereo s16 little-endian, which AL takes natively. */
		private int openAlFormat(AudioFormat format) {
			if (format == null) return AL10.AL_NONE;
			if (format.getSampleSizeInBits() != 16 || (format.getChannels() != 1 && format.getChannels() != 2)) {
				Constants.LOGGER.error("Unsupported waiting music format ({} bits, {} channels); skipping it", format.getSampleSizeInBits(), format.getChannels());
				return AL10.AL_NONE;
			}
			return format.getChannels() == 1 ? AL10.AL_FORMAT_MONO16 : AL10.AL_FORMAT_STEREO16;
		}

		/** Decodes the whole ogg through Minecraft's vorbis decoder; the PCM stays resident so looping never re-decodes. */
		private byte[] decode() {
			try (InputStream input = Assets.stream(MUSIC_PATH); AudioStream stream = openStream(input)) {
				this.format = stream.getFormat();
				ByteArrayOutputStream pcm = new ByteArrayOutputStream();
				ByteBuffer chunk;
				while ((chunk = stream.read(WRITE_CHUNK)) != null && chunk.hasRemaining()) {
					byte[] bytes = new byte[chunk.remaining()];
					chunk.get(bytes);
					pcm.write(bytes);
				}
				if (pcm.size() == 0) {
					Constants.LOGGER.error("The bundled waiting music decoded to no audio from {}", MUSIC_PATH);
					return null;
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
	}
}
