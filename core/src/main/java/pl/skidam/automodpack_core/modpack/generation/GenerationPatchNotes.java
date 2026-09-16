package pl.skidam.automodpack_core.modpack.generation;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.FileChannel;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Objects;

import pl.skidam.automodpack_core.utils.HashUtils;

/** Resolves the optional notes input for a generation operation and proves file reads were stable. */
public final class GenerationPatchNotes {
	private GenerationPatchNotes() {}

	public enum Source {
		INLINE, FILE, EMPTY
	}

	public enum CleanupStatus {
		NOT_APPLICABLE, NOT_PRESENT, DELETED, PRESERVED_CHANGED, PRESERVED_FAILURE
	}

	public record CleanupResult(CleanupStatus status, String warning) {
		public CleanupResult {
			status = Objects.requireNonNull(status);
			warning = warning == null ? "" : warning;
		}
	}

	public static final class Resolution {
		private final String text;
		private final Source source;
		private final String rawDigest;
		private final Path sourcePath;

		private Resolution(String text, Source source, String rawDigest, Path sourcePath) {
			this.text = Objects.requireNonNull(text);
			this.source = Objects.requireNonNull(source);
			this.rawDigest = Objects.requireNonNull(rawDigest);
			this.sourcePath = sourcePath;
		}

		public String text() {
			return text;
		}

		public Source source() {
			return source;
		}

		public boolean isFileSourced() {
			return source == Source.FILE;
		}

		public CleanupResult consumeIfUnchanged() {
			if (source != Source.FILE) return new CleanupResult(CleanupStatus.NOT_APPLICABLE, "");
			try {
				RawFile current = readStable(sourcePath);
				if (!rawDigest.equals(current.digest()))
					return new CleanupResult(CleanupStatus.PRESERVED_CHANGED,
							"Patch notes file changed during publication and was preserved");
				Files.delete(sourcePath);
				return new CleanupResult(CleanupStatus.DELETED, "");
			} catch (NoSuchFileException e) {
				return new CleanupResult(CleanupStatus.NOT_PRESENT, "");
			} catch (Exception e) {
				return new CleanupResult(CleanupStatus.PRESERVED_FAILURE, "Patch notes file could not be consumed and was preserved");
			}
		}
	}

	public static Resolution resolve(String inlineNotes, Path notesFile) throws IOException {
		Objects.requireNonNull(notesFile, "notesFile");
		Path normalized = notesFile.toAbsolutePath().normalize();
		if (inlineNotes != null) return new Resolution(normalizeAndValidate(inlineNotes), Source.INLINE, "", null);
		if (Files.isSymbolicLink(normalized)) throw new IOException("Patch notes must be a regular non-symlink file");
		if (!Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) return new Resolution("", Source.EMPTY, "", null);
		RawFile raw = readStable(normalized);
		return new Resolution(normalizeAndValidate(decode(raw.bytes())), Source.FILE, raw.digest(), normalized);
	}

	private static String normalizeAndValidate(String notes) throws IOException {
		try {
			return GenerationMetadata.validateNotes(notes);
		} catch (IllegalArgumentException e) {
			throw new IOException(e.getMessage(), e);
		}
	}

	private static RawFile readStable(Path path) throws IOException {
		BasicFileAttributes before = attributes(path);
		byte[] bytes = readBytes(path);
		BasicFileAttributes after = attributes(path);
		if (!same(before, after) || bytes.length != after.size()) throw new IOException("Patch notes file changed while being read");
		return new RawFile(bytes, digest(bytes));
	}

	private static BasicFileAttributes attributes(Path path) throws IOException {
		if (Files.isSymbolicLink(path)) throw new IOException("Patch notes must be a regular non-symlink file");
		BasicFileAttributes attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
		if (!attributes.isRegularFile()) throw new IOException("Patch notes must be a regular non-symlink file");
		return attributes;
	}

	private static byte[] readBytes(Path path) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (FileChannel channel = FileChannel.open(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
				var input = Channels.newInputStream(channel)) {
			input.transferTo(bytes);
		}
		return bytes.toByteArray();
	}

	private static boolean same(BasicFileAttributes first, BasicFileAttributes second) {
		return first.size() == second.size() && first.lastModifiedTime().equals(second.lastModifiedTime())
				&& Objects.equals(first.fileKey(), second.fileKey());
	}

	private static String decode(byte[] bytes) throws IOException {
		try {
			return StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
					.decode(ByteBuffer.wrap(bytes)).toString();
		} catch (CharacterCodingException e) {
			throw new IOException("Patch notes are not valid UTF-8", e);
		}
	}

	private static String digest(byte[] bytes) {
		return HashUtils.sha1(bytes);
	}

	private record RawFile(byte[] bytes, String digest) {}
}
