package pl.skidam.automodpack_core.storage;

import java.nio.file.Path;

/**
 * Canonical relative layout of AutoModpack's game-local files.
 *
 * <p>
 * These paths describe layout only. Runtime services and process state do not belong here.
 * </p>
 */
public final class StoragePaths {
	public static final Path AUTOMODPACK_DIR = Path.of("automodpack");
	public static final Path CLIENT_DIR = AUTOMODPACK_DIR.resolve("client");
	public static final Path CLIENT_RECORDS_DIR = CLIENT_DIR.resolve("records");
	public static final Path CLIENT_OVERLAYS_DIR = CLIENT_DIR.resolve("overlays");
	public static final Path CLIENT_BASELINES_DIR = CLIENT_DIR.resolve("baselines");
	public static final Path CLIENT_GENERATED_COPIES_DIR = CLIENT_DIR.resolve("generated-copies");
	public static final Path CLIENT_ACTIVE_DIR = CLIENT_DIR.resolve("active");
	public static final Path CLIENT_INCOMING_DIR = CLIENT_DIR.resolve("incoming");
	public static final Path CLIENT_BACKUP_DIR = CLIENT_DIR.resolve("backup");
	public static final Path CLIENT_PRESERVATION_DIR = CLIENT_DIR.resolve("preservation");
	public static final Path CLIENT_ACTIVE_STATE_FILE = CLIENT_DIR.resolve("active-state.json");
	public static final Path CLIENT_SELECTION_FILE = CLIENT_DIR.resolve("selections.json");
	public static final Path CLIENT_RESTART_LOOP_STATE_FILE = CLIENT_DIR.resolve("restart-state.json");
	public static final Path CLIENT_TRANSACTION_FILE = CLIENT_DIR.resolve("update-transaction.json");
	public static final Path CLIENT_CONTENT_TEMP_FILE = CLIENT_DIR.resolve("incoming-content.json.temp");
	public static final Path CLIENT_HELPER_DIR = CLIENT_DIR.resolve("helper");
	public static final Path CLIENT_HELPER_LEASE_FILE = CLIENT_HELPER_DIR.resolve("running.lock");
	public static final Path DATA_ROOT_MARKER_FILE = AUTOMODPACK_DIR.resolve("data-root.json");
	public static final Path DATA_ROOT_LOCK_FILE = AUTOMODPACK_DIR.resolve("data-root.lock");

	public static final Path SERVER_DIR = AUTOMODPACK_DIR.resolve("server");
	public static final Path SERVER_CURRENT_FILE = SERVER_DIR.resolve("current.json");
	public static final Path SERVER_CURRENT_PROJECTION_FILE = SERVER_DIR.resolve("current-projection.json");
	public static final Path SERVER_GENERATION_CHECKPOINT_FILE = SERVER_DIR.resolve("checkpoint.json");
	public static final Path SERVER_CATALOGUES_DIR = SERVER_DIR.resolve("catalogues");
	public static final Path SERVER_COMMITS_DIR = SERVER_DIR.resolve("commits");
	public static final Path SERVER_DELTAS_DIR = SERVER_DIR.resolve("deltas");
	public static final Path SERVER_STAGING_DIR = SERVER_DIR.resolve("staging");
	public static final Path SERVER_PATCH_NOTES_FILE = SERVER_DIR.resolve("patch-notes.md");
	public static final Path SERVER_SECRETS_FILE = SERVER_DIR.resolve("secrets.json");
	public static final Path SERVER_CERT_FILE = SERVER_DIR.resolve("certificate.crt");
	public static final Path SERVER_PRIVATE_KEY_FILE = SERVER_DIR.resolve("private-key.pem");

	public static final Path HOST_MODPACK_DIR = AUTOMODPACK_DIR.resolve("host-modpack");
	public static final Path HOST_CONTENT_MODPACK_DIR = HOST_MODPACK_DIR.resolve("main");
	public static final Path MODPACK_CONTENT_FILE = Path.of("automodpack-content.json");
	public static final Path SERVER_CONFIG_FILE = AUTOMODPACK_DIR.resolve("server-config.json");
	public static final Path BOOTSTRAP_FILE = Path.of("automodpack-bootstrap.json");
	public static final Path CLIENT_CONFIG_FILE = AUTOMODPACK_DIR.resolve("client-config.json");

	private StoragePaths() {}
}
