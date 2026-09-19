package pl.skidam.automodpack_core.client;

import java.io.IOException;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.protocol.PackTransport;
import pl.skidam.automodpack_core.update.ClientStorage;

/** Owns one transfer session opened from a stored per-modpack route. */
public final class StoredModpackConnection implements AutoCloseable {
	private final String modpackId;
	private final ConnectionJsons.ConnectionInfo connection;
	private final Secrets.Secret secret;
	private final GenerationJsons.HeadDocumentFields advertisedDocument;
	private PackTransport transport;

	private StoredModpackConnection(String modpackId, ConnectionJsons.ConnectionInfo connection, Secrets.Secret secret, GenerationJsons.HeadDocumentFields advertisedDocument,
			PackTransport transport) {
		this.modpackId = modpackId;
		this.connection = connection;
		this.secret = secret;
		this.advertisedDocument = advertisedDocument;
		this.transport = transport;
	}

	/** A stored connection seeded with its exact certificate pin and, for the custom modes, the client secret of its origin; HTTP packs carry no secret. */
	public record Seeded(ConnectionJsons.ConnectionInfo connection, Secrets.Secret secret) {}

	/** Loads the stored connection route and seeds its fingerprint-checked connection and secret; null when no complete connection is stored. */
	public static Seeded seed(ClientStorage storage, String modpackId) throws IOException {
		ConnectionJsons.ConnectionInfo stored = ConnectionStore.getConnection(storage, modpackId);
		if (stored == null || stored.connectionMode == null || stored.origin == null || stored.endpoint == null) return null;
		ConnectionJsons.ConnectionInfo connection = new ConnectionJsons.ConnectionInfo(stored.origin, stored.endpoint, stored.connectionMode,
				CertificateTrustStore.getFingerprint(stored.origin), null);
		stored.approvedOrigins().forEach(connection::approveOrigin);
		return new Seeded(connection, ConnectionStore.getClientSecret(storage, modpackId, stored.origin));
	}

	public static StoredModpackConnection open(ClientStorage storage, String modpackId, boolean allowAskingUser) throws Exception {
		Seeded seeded = seed(storage, modpackId);
		if (seeded == null) throw new IOException("Saved modpack connection is unavailable");
		ConnectionJsons.ConnectionInfo connection = seeded.connection();
		Secrets.Secret secret = seeded.secret();
		ManifestFetcher.ManifestFetchResult result = ManifestFetcher.requestServerModpackContent(storage, connection, secret, allowAskingUser, modpackId);
		if (!result.successful())
			throw new IOException(result.failure() == null ? "Could not fetch the latest modpack generation" : result.failure().getMessage(), result.failure());
		PackTransport fetchedTransport = result.transport();
		try {
			PackDocument advertised = PackDocument.fromFields(result.content());
			if (!modpackId.equals(advertised.manifest().modpackId())) throw new IOException("Connected modpack identity does not match the installed pack");
			StoredModpackConnection session = new StoredModpackConnection(modpackId, connection, secret, result.content(), fetchedTransport);
			fetchedTransport = null;
			return session;
		} finally {
			if (fetchedTransport != null) fetchedTransport.close();
		}
	}

	/** Returns the complete head document validated when this session was opened. */
	public GenerationJsons.HeadDocumentFields advertisedFields() {
		return advertisedDocument;
	}

	/** Transfers this session's transport ownership to an updater. This connection becomes empty. */
	public synchronized ModpackUpdater newUpdater(SelectedModpackTarget target, ClientStorage storage) throws IOException {
		if (!modpackId.equals(target.manifest().modpackId())) throw new IOException("Selected modpack identity does not match the connected pack");
		if (transport == null) throw new IllegalStateException("Stored modpack transfer session was already consumed");
		PackTransport transferred = transport;
		transport = null;
		return new ModpackUpdater(target, connection, secret, storage, transferred);
	}

	@Override
	public synchronized void close() {
		if (transport != null) {
			transport.close();
			transport = null;
		}
	}
}
