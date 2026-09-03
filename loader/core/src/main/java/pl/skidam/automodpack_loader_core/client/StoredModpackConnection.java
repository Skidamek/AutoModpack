package pl.skidam.automodpack_loader_core.client;

import java.io.IOException;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientStorage;

/** Owns one authenticated transfer session opened from a stored per-modpack route. */
public final class StoredModpackConnection implements AutoCloseable {
	private final String modpackId;
	private final ConnectionJsons.ConnectionInfo connection;
	private final Secrets.Secret secret;
	private final GenerationJsons.HeadDocumentFields advertisedDocument;
	private DownloadClient client;

	private StoredModpackConnection(String modpackId, ConnectionJsons.ConnectionInfo connection, Secrets.Secret secret, GenerationJsons.HeadDocumentFields advertisedDocument,
			DownloadClient client) {
		this.modpackId = modpackId;
		this.connection = connection;
		this.secret = secret;
		this.advertisedDocument = advertisedDocument;
		this.client = client;
	}

	/** A stored connection seeded with its exact certificate pin and the client secret for its origin. */
	public record Seeded(ConnectionJsons.ConnectionInfo connection, Secrets.Secret secret, boolean anonymousSecret) {}

	/** Loads the stored connection route and seeds its fingerprint-checked connection and secret; null when no complete connection is stored. */
	public static Seeded seed(ClientStorage storage, String modpackId) throws IOException {
		ConnectionJsons.ConnectionInfo stored = ConnectionStore.getConnection(storage, modpackId);
		if (stored == null || stored.connectionMode == null || stored.origin == null || stored.endpoint == null) return null;
		ConnectionJsons.ConnectionInfo connection = new ConnectionJsons.ConnectionInfo(stored.origin, stored.endpoint, stored.connectionMode,
				CertificateTrustStore.getFingerprint(stored.origin), null);
		Secrets.Secret secret = SecretsStore.getClientSecret(storage, modpackId, stored.origin);
		return new Seeded(connection, secret == null ? Secrets.anonymousSecret() : secret, secret == null);
	}

	public static StoredModpackConnection open(ClientStorage storage, String modpackId, boolean allowAskingUser) throws Exception {
		Seeded seeded = seed(storage, modpackId);
		if (seeded == null) throw new IOException("Saved modpack connection is unavailable");
		ConnectionJsons.ConnectionInfo connection = seeded.connection();
		Secrets.Secret secret = seeded.secret();
		ModpackUtils.ManifestFetchResult result = ModpackUtils.requestServerModpackContent(storage, connection, secret, allowAskingUser);
		if (!result.successful())
			throw new IOException(result.failure() == null ? "Could not fetch the latest modpack generation" : result.failure().getMessage(), result.failure());
		DownloadClient client = result.client();
		try {
			PackDocument advertised = PackDocument.fromFields(result.content());
			if (!modpackId.equals(advertised.manifest().modpackId())) throw new IOException("Connected modpack identity does not match the installed pack");
			StoredModpackConnection session = new StoredModpackConnection(modpackId, connection, secret, result.content(), client);
			client = null;
			return session;
		} finally {
			if (client != null) client.close();
		}
	}

	/** Returns the complete head document validated when this session was opened. */
	public GenerationJsons.HeadDocumentFields advertisedFields() {
		return advertisedDocument;
	}

	/** Transfers this session's client ownership to an updater. This connection becomes empty. */
	public synchronized ModpackUpdater newUpdater(SelectedModpackTarget target, ClientStorage storage) throws IOException {
		if (!modpackId.equals(target.manifest().modpackId())) throw new IOException("Selected modpack identity does not match the connected pack");
		if (client == null) throw new IllegalStateException("Stored modpack transfer session was already consumed");
		DownloadClient transferred = client;
		client = null;
		return new ModpackUpdater(target, connection, secret, storage, transferred);
	}

	@Override
	public synchronized void close() {
		if (client != null) {
			client.close();
			client = null;
		}
	}
}
