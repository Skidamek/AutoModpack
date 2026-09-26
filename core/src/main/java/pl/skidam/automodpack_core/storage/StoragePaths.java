package pl.skidam.automodpack_core.storage;

import java.nio.file.Path;

/**
 * Canonical relative layout of AutoModpack's game-local files.
 *
 * <p>
 * These paths describe layout only. Runtime services and process state do not belong here. Content shared across
 * instances lives outside this tree, in the resolved data root; a dedicated server's own cache is the exception
 * and lives at {@code server/data}, inside the server scope it belongs to.
 * </p>
 */
public final class StoragePaths {
	public static final Path AUTOMODPACK_DIR = Path.of("automodpack");
	public static final Path CLIENT_DIR = AUTOMODPACK_DIR.resolve("client");
	public static final Path CLIENT_OVERLAYS_DIR = CLIENT_DIR.resolve("overlays");
	public static final Path CLIENT_GENERATED_COPIES_DIR = CLIENT_DIR.resolve("generated-copies");
	public static final Path CLIENT_ACTIVE_DIR = CLIENT_DIR.resolve("active");
	public static final Path CLIENT_INCOMING_DIR = CLIENT_DIR.resolve("incoming");
	public static final Path CLIENT_BACKUP_DIR = CLIENT_DIR.resolve("backup");
	public static final Path CLIENT_HISTORY_DIR = CLIENT_DIR.resolve("history");
	public static final Path CLIENT_STATE_HISTORY_DIR = CLIENT_DIR.resolve("state-history");
	public static final Path RECOVERED_DIR = AUTOMODPACK_DIR.resolve("recovered");
	public static final Path CLIENT_ACTIVE_STATE_FILE = CLIENT_DIR.resolve("active-state.json");
	public static final Path CLIENT_SELECTED_FILE = CLIENT_DIR.resolve("selected.json");
	public static final Path CLIENT_SELECTION_FILE = CLIENT_DIR.resolve("selections.json");
	public static final Path CLIENT_RESTART_LOOP_STATE_FILE = CLIENT_DIR.resolve("restart-state.json");
	public static final Path CLIENT_STUCK_TRANSACTION_STATE_FILE = CLIENT_DIR.resolve("stuck-transaction-state.json");
	public static final Path CLIENT_TRANSACTION_FILE = CLIENT_DIR.resolve("update-transaction.json");
	public static final Path CLIENT_REPAIR_FILE = CLIENT_DIR.resolve("repair.json");
	public static final Path CLIENT_MUTATION_LOCK_FILE = CLIENT_DIR.resolve("mutation.lock");
	public static final Path CLIENT_CONTENT_TEMP_FILE = CLIENT_DIR.resolve("incoming-manifest.json.temp");
	public static final Path CLIENT_JOURNAL_TEMP_FILE = CLIENT_DIR.resolve("incoming-journal.jsonl.temp");

	/** The client's instance-local cache, used only when the shared platform root is unusable. */
	public static final Path CLIENT_DATA_DIR = CLIENT_DIR.resolve("data");

	/** The client's extracted nested impl jars, mounted by loaders that cannot open nested zips in place. */
	public static final Path CLIENT_IMPL_CACHE_DIR = CLIENT_DIR.resolve("impl-cache");

	/** The instance-level pending self-update swap; consumed at boot before any role machinery wakes up. */
	public static final Path SELF_UPDATE_FILE = AUTOMODPACK_DIR.resolve("self-update.json");
	public static final Path HELPER_DIR = AUTOMODPACK_DIR.resolve("helper");
	public static final Path HELPER_LEASE_FILE = HELPER_DIR.resolve("running.lock");
	public static final Path HELPER_LOG_FILE = HELPER_DIR.resolve("last-helper-run.log");

	public static final Path SERVER_DIR = AUTOMODPACK_DIR.resolve("server");
	public static final Path SERVER_PROJECTION_FILE = SERVER_DIR.resolve("current-projection.json");
	public static final Path SERVER_JOURNAL_FILE = SERVER_DIR.resolve("journal.jsonl");
	public static final Path SERVER_STAGING_DIR = SERVER_DIR.resolve("staging");

	/** The dedicated server's own cache: visible, deletable with the folder, never derived from HOME. */
	public static final Path SERVER_DATA_DIR = SERVER_DIR.resolve("data");

	/** The dedicated server's extracted nested impl jars, mounted by loaders that cannot open nested zips in place. */
	public static final Path SERVER_IMPL_CACHE_DIR = SERVER_DIR.resolve("impl-cache");

	/** Every credential a server host holds: the certificate pair, the download secrets document, and the bootstrap secret. The folder is safe to share between server processes; its code paths assume it might be. */
	public static final Path CREDENTIALS_DIR = AUTOMODPACK_DIR.resolve("credentials");
	public static final Path SERVER_CERT_FILE = CREDENTIALS_DIR.resolve("certificate.crt");
	public static final Path SERVER_PRIVATE_KEY_FILE = CREDENTIALS_DIR.resolve("private-key.pem");
	public static final Path PROVISIONING_SECRET_FILE = CREDENTIALS_DIR.resolve("provisioning-secret");
	public static final Path SERVER_SECRETS_FILE = CREDENTIALS_DIR.resolve("secrets.json");

	public static final Path HOST_MODPACK_DIR = AUTOMODPACK_DIR.resolve("host-modpack");
	public static final Path HOST_CONTENT_MODPACK_DIR = HOST_MODPACK_DIR.resolve("main");
	/** The convention file the server may drop here to publish a custom waiting track; its hash rides in the head document. */
	public static final String WAITING_MUSIC_FILE = "waiting-music.ogg";
	/** The waiting track guardrail: past 5 MiB the server refuses to publish and the client aborts at the response head. Roomy enough for any real song; only broken things touch it. */
	public static final long WAITING_MUSIC_MAX_BYTES = 5 * 1024 * 1024;
	public static final Path MODPACK_CONTENT_FILE = Path.of("automodpack-content.json");
	public static final Path PATCH_NOTES_FILE = Path.of("patch-notes.md");
	public static final Path SERVER_CONFIG_FILE = AUTOMODPACK_DIR.resolve("server.conf");
	public static final Path V4_SERVER_CONFIG_FILE = AUTOMODPACK_DIR.resolve("automodpack-server.json");
	public static final Path V4_SERVER_CONFIG_ALT_FILE = AUTOMODPACK_DIR.resolve("server-config.json");
	public static final Path BOOTSTRAP_FILE = AUTOMODPACK_DIR.resolve("automodpack-bootstrap.json");
	public static final Path BOOTSTRAP_EXPORT_FILE = AUTOMODPACK_DIR.resolve("automodpack-bootstrap.exported.json");
	public static final Path CLIENT_CONFIG_FILE = AUTOMODPACK_DIR.resolve("client.conf");
	public static final Path V4_CLIENT_CONFIG_FILE = AUTOMODPACK_DIR.resolve("automodpack-client.json");
	public static final Path V4_CLIENT_CONFIG_ALT_FILE = AUTOMODPACK_DIR.resolve("client-config.json");

	public static final String DATA_ROOT_PROPERTY = "automodpack.data.root";
	public static final String DATA_ROOT_ENV = "AUTOMODPACK_DATA_ROOT";

	private StoragePaths() {}
}
