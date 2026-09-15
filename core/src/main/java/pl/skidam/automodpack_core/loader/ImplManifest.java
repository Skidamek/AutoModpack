package pl.skidam.automodpack_core.loader;

import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;

/**
 * The index of the one jar's solid impl blob ({@code impl/manifest.bin}). Little-endian, hand-rolled:
 * magic {@code AMP1}, the 20-byte SHA-1 generation of {@code impl/all.zst}, an entry count, then per
 * entry the target id, its offset and STORE length inside the uncompressed solid, and the SHA-1 of
 * that slice. Parsing is total: any corruption, truncation or trailing garbage is a broken outer jar
 * and crashes instead of answering a partial question.
 */
public final class ImplManifest {
	/** {@code AMP1} - the only magic this format ever answers to. */
	public static final byte[] MAGIC = {'A', 'M', 'P', '1'};
	public static final int SHA1_BYTES = 20;
	private static final int HEADER_BYTES = MAGIC.length + SHA1_BYTES + 2;

	private final String generation;
	private final List<Entry> entries;

	public record Entry(String id, long offset, long length, String sha1) {}

	private ImplManifest(String generation, List<Entry> entries) {
		this.generation = generation;
		this.entries = entries;
	}

	/** Parses the manifest bytes; throws {@link IllegalStateException} on anything that is not exactly one well-formed manifest. */
	public static ImplManifest parse(byte[] bytes) {
		try {
			ByteBuffer buffer = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
			byte[] magic = new byte[MAGIC.length];
			buffer.get(magic);
			if (!Arrays.equals(magic, MAGIC)) throw new IllegalStateException("Impl manifest magic is " + HexFormat.of().formatHex(magic) + ", expected AMP1");
			byte[] generation = new byte[SHA1_BYTES];
			buffer.get(generation);
			int count = Short.toUnsignedInt(buffer.getShort());
			List<Entry> entries = new ArrayList<>(count);
			for (int i = 0; i < count; i++) {
				byte[] id = new byte[Short.toUnsignedInt(buffer.getShort())];
				buffer.get(id);
				long offset = Integer.toUnsignedLong(buffer.getInt());
				long length = Integer.toUnsignedLong(buffer.getInt());
				byte[] sha1 = new byte[SHA1_BYTES];
				buffer.get(sha1);
				entries.add(new Entry(new String(id, StandardCharsets.UTF_8), offset, length, HexFormat.of().formatHex(sha1)));
			}
			if (buffer.hasRemaining()) throw new IllegalStateException("Impl manifest carries " + buffer.remaining() + " trailing bytes after " + count + " entries");
			return new ImplManifest(HexFormat.of().formatHex(generation), List.copyOf(entries));
		} catch (BufferUnderflowException e) {
			throw new IllegalStateException("Impl manifest is truncated at " + (bytes == null ? 0 : bytes.length) + " bytes", e);
		}
	}

	/** The SHA-1 of {@code impl/all.zst} as lowercase hex - the impl-cache generation key. */
	public String generation() {
		return generation;
	}

	public List<Entry> entries() {
		return entries;
	}

	/** The entry for {@code id}; an id the manifest does not carry is a broken launch and crashes with the ids that exist. */
	public Entry entry(String id) {
		return entries.stream().filter(entry -> entry.id().equals(id)).findFirst().orElseThrow(() -> new IllegalStateException("This AutoModpack jar carries no impl for " + id + "; available impls: " + ids()));
	}

	/** The uncompressed solid size the manifest describes. */
	public long totalSize() {
		return entries.stream().mapToLong(Entry::length).sum();
	}

	private String ids() {
		return entries.stream().map(Entry::id).sorted().collect(Collectors.joining(", "));
	}
}
