package pl.skidam.automodpack_core.protocol.netty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The reserved documents' etags: hashed once per file version and shared by every conditional fetch, instead of
 * hashing a possibly large document on the event loop once per client sync. The hosting swap clears and rewarms
 * the memos, since it replaces the files behind them.
 */
public final class DocumentEtags {

	private final Map<Path, Memo> memos = new ConcurrentHashMap<>();

	/** Forgets every memo and hashes the two reserved documents on the calling thread, so no conditional fetch ever hashes on the event loop: a publish just hashed the whole generation, so one more journal SHA-1 here is milliseconds. */
	public void replace(Optional<Path> head, Optional<Path> journal) {
		memos.clear();
		head.ifPresent(this::etag);
		journal.ifPresent(this::etag);
	}

	/** The served document's sha1 for conditional fetches; null when it cannot be read, mirroring {@code HashUtils.getHash}. */
	public String etag(Path file) {
		try {
			long size = Files.size(file);
			long mtimeMillis = Files.getLastModifiedTime(file).toMillis();
			Memo memo = memos.get(file);
			if (memo != null && memo.size() == size && memo.mtimeMillis() == mtimeMillis) return memo.sha1();
			String sha1 = HashUtils.getHash(file);
			if (sha1 == null) return null;
			if (Files.size(file) == size && Files.getLastModifiedTime(file).toMillis() == mtimeMillis) memos.put(file, new Memo(size, mtimeMillis, sha1));
			return sha1;
		} catch (IOException e) {
			return null;
		}
	}

	private record Memo(long size, long mtimeMillis, String sha1) {}
}
