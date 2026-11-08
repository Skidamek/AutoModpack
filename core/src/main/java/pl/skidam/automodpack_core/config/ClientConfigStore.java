package pl.skidam.automodpack_core.config;

import java.io.IOException;
import java.nio.file.Path;
import java.util.function.UnaryOperator;

import pl.skidam.automodpack_core.config.ClientConfigJsons.ClientConfigFieldsV3;

/**
 * One instance's client config document: the current copy, the lock that serializes its read-modify-write, and the
 * durable file behind it.
 * A caller derives a new document from {@link #current()} and hands it to {@link #commit}, which writes and lands it as
 * one step, so a landing can never silently discard a concurrent one. The engine persists through its own transaction
 * and only needs the landing, which is what {@link #critical} holds the lock for.
 */
public final class ClientConfigStore {
	private final Path file;
	private final Object lock = new Object();
	private volatile ClientConfigFieldsV3 current;

	public ClientConfigStore(Path file) {
		this.file = file;
	}

	/**
	 * The document this instance runs on. A storage instance can outlive or precede the boot that adopted the document,
	 * and the cache may have dropped it, so a missing copy is read back from the file the store owns.
	 */
	public ClientConfigFieldsV3 current() {
		ClientConfigFieldsV3 document = current;
		if (document != null) return document;
		synchronized (lock) {
			if (current == null) current = ReconfConfigs.read(file, ClientConfigFieldsV3.class).orElse(null);
			return current;
		}
	}

	/** Boot only: the process is still single-threaded, so the document lands without the lock and without a write. */
	public ClientConfigFieldsV3 adopt(ClientConfigFieldsV3 document) {
		current = document;
		return document;
	}

	/** A preference change: derive from the document, write it, land it, one critical section. */
	public ClientConfigFieldsV3 commit(UnaryOperator<ClientConfigFieldsV3> next) {
		synchronized (lock) {
			ClientConfigFieldsV3 landed = next.apply(current());
			write(landed);
			current = landed;
			return landed;
		}
	}

	/**
	 * The engine's larger durable write: the caller owns persistence (the transaction commit writes the document), so the
	 * lock only has to keep a concurrent preference save out of the landing.
	 */
	public <T> T critical(CriticalSection<T> body) throws Exception {
		synchronized (lock) {
			return body.run(this::land);
		}
	}

	public interface Writer {
		/** Lands an already-persisted document. */
		void land(ClientConfigFieldsV3 document);
	}

	public interface CriticalSection<T> {
		T run(Writer writer) throws Exception;
	}

	private void land(ClientConfigFieldsV3 document) {
		current = document;
	}

	private void write(ClientConfigFieldsV3 document) {
		try {
			ReconfConfigs.save(file, document, ClientConfigFieldsV3.class, ClientConfigFieldsV3::new);
		} catch (IOException e) {
			throw new ConfigTools.ConfigException("Failed to save client configuration", e);
		}
	}
}
