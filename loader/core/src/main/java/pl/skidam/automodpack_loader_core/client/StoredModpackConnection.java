package pl.skidam.automodpack_loader_core.client;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;

import pl.skidam.automodpack_core.auth.ConnectionStore;
import pl.skidam.automodpack_core.auth.Secrets;
import pl.skidam.automodpack_core.auth.SecretsStore;
import pl.skidam.automodpack_core.config.ConnectionJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.CatalogueSnapshot;
import pl.skidam.automodpack_core.modpack.generation.GenerationHistoryIndex;
import pl.skidam.automodpack_core.modpack.generation.GenerationMetadata;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.protocol.DownloadClient;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;

/** Owns one authenticated transfer session opened from a stored per-modpack route. */
public final class StoredModpackConnection implements AutoCloseable {
	private final String modpackId;
	private final ConnectionJsons.ConnectionInfo connection;
	private final Secrets.Secret secret;
	private final GenerationRecord advertisedRecord;
	private final GenerationHistoryIndex advertisedHistoryIndex;
	private final ClientStorage storage;
	private DownloadClient client;

	private StoredModpackConnection(String modpackId, ConnectionJsons.ConnectionInfo connection, Secrets.Secret secret, GenerationRecord advertisedRecord,
			GenerationHistoryIndex advertisedHistoryIndex, ClientStorage storage, DownloadClient client) {
		this.modpackId = modpackId;
		this.connection = connection;
		this.secret = secret;
		this.advertisedRecord = advertisedRecord;
		this.advertisedHistoryIndex = advertisedHistoryIndex;
		this.storage = storage;
		this.client = client;
	}

	public static StoredModpackConnection open(ClientStorage storage, String modpackId, boolean allowAskingUser) throws Exception {
		ConnectionJsons.ConnectionInfo stored = ConnectionStore.getConnection(storage, modpackId);
		if (stored == null || stored.connectionMode == null || stored.origin == null || stored.endpoint == null)
			throw new IOException("Saved modpack connection is unavailable");
		ConnectionJsons.ConnectionInfo connection = new ConnectionJsons.ConnectionInfo(stored.origin, stored.endpoint, stored.connectionMode,
				CertificateTrustStore.getFingerprint(stored.origin), null);
		Secrets.Secret secret = SecretsStore.getClientSecret(storage, modpackId, stored.origin);
		if (secret == null) secret = Secrets.anonymousSecret();
		ModpackUtils.ManifestFetchResult result = ModpackUtils.requestServerModpackContent(storage, connection, secret, allowAskingUser);
		if (!result.successful())
			throw new IOException(result.failure() == null ? "Could not fetch the latest modpack generation" : result.failure().getMessage(), result.failure());
		DownloadClient client = result.client();
		try {
			GenerationRecord advertisedRecord = GenerationRecord.fromFields(result.content());
			if (!modpackId.equals(advertisedRecord.manifest().modpackId())) throw new IOException("Connected modpack identity does not match the installed pack");
			if (result.content().generationHistory == null) throw new IOException("Server generation history is unavailable");
			GenerationHistoryIndex historyIndex = GenerationHistoryIndex.fromFields(result.content().generationHistory);
			if (!modpackId.equals(historyIndex.modpackId())) throw new IOException("Server generation history belongs to another modpack");
			if (!advertisedRecord.metadata().generationId().equals(historyIndex.currentGenerationId()))
				throw new IOException("Server generation history does not describe the advertised generation");
			StoredModpackConnection session = new StoredModpackConnection(modpackId, connection, secret, advertisedRecord, historyIndex, storage, client);
			client = null;
			return session;
		} finally {
			if (client != null) client.close();
		}
	}

	public GenerationRecord advertisedRecord() {
		return advertisedRecord;
	}

	public GenerationHistoryIndex advertisedHistoryIndex() {
		return advertisedHistoryIndex;
	}

	/** Returns the complete current-format advertisement validated when this session was opened. */
	public ModpackJsons.CompleteModpackContentFields advertisedFields() {
		ModpackJsons.CompleteModpackContentFields fields = advertisedRecord.toFields();
		fields.generationHistory = advertisedHistoryIndex.toFields();
		fields.patchNotesHistory = advertisedHistoryIndex.entries().stream().map(entry -> {
			ModpackJsons.CompleteModpackContentFields.PatchNotesHistoryEntryFields history = new ModpackJsons.CompleteModpackContentFields.PatchNotesHistoryEntryFields();
			history.schemaVersion = GenerationMetadata.CURRENT_SCHEMA_VERSION;
			history.generationId = entry.generationId();
			history.parentGenerationId = entry.parentGenerationId();
			history.createdAt = entry.createdAt().toString();
			history.patchNotes = entry.patchNotes();
			history.patchNotesDigest = entry.patchNotesDigest();
			return history;
		}).toList();
		GenerationPatchNoteHistory.fromFields(fields);
		return fields;
	}

	/** Downloads and validates one historical catalogue through this authenticated session. */
	public CompletableFuture<CatalogueSnapshot> downloadHistoricalCatalogue(GenerationHistoryIndex.Entry entry) {
		Objects.requireNonNull(entry, "history entry");
		DownloadClient currentClient;
		synchronized (this) {
			if (client == null) return CompletableFuture.failedFuture(new IOException("Stored modpack transfer session was already consumed"));
			currentClient = client;
		}
		Path destination = storage.helperDirectory().resolve("history-catalogue-" + entry.stateDigest() + ".json").normalize();
		if (!destination.startsWith(storage.helperDirectory())) return CompletableFuture.failedFuture(new IOException("Historical catalogue path escaped client storage"));
		return new ClientGenerationStore(storage).downloadHistoricalCatalogue(currentClient, entry, destination, null);
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
