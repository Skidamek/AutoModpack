package pl.skidam.automodpack_core.storage;

import java.nio.file.Path;

/**
 * Canonical relative layout of AutoModpack's game-local files.
 *
 * <p>
 * These paths describe layout only. Runtime services and process state do not belong here.
 * Shared content-addressed bytes live outside this tree, in the resolved data root.
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
	public static final Path CLIENT_INCOMING_PROJECTION_DIR = CLIENT_INCOMING_DIR.resolve("projection");
	public static final Path CLIENT_BACKUP_DIR = CLIENT_DIR.resolve("backup");
	public static final Path CLIENT_BACKUP_PROJECTION_DIR = CLIENT_BACKUP_DIR.resolve("projection");
	public static final Path CLIENT_PRESERVATION_DIR = CLIENT_DIR.resolve("preservation");
	public static final Path RECOVERED_DIR = AUTOMODPACK_DIR.resolve("recovered");
	public static final Path CLIENT_ACTIVE_STATE_FILE = CLIENT_DIR.resolve("active-state.json");
	public static final Path CLIENT_SELECTION_FILE = CLIENT_DIR.resolve("selections.json");
	public static final Path CLIENT_RESTART_LOOP_STATE_FILE = CLIENT_DIR.resolve("restart-state.json");
	public static final Path CLIENT_TRANSACTION_FILE = CLIENT_DIR.resolve("update-transaction.json");
	public static final Path CLIENT_REPAIR_FILE = CLIENT_DIR.resolve("repair.json");
	public static final Path CLIENT_COMPACTION_FILE = CLIENT_DIR.resolve("compaction.json");
	public static final Path CLIENT_MUTATION_LOCK_FILE = CLIENT_DIR.resolve("mutation.lock");
	public static final Path CLIENT_CONTENT_TEMP_FILE = CLIENT_DIR.resolve("incoming-manifest.json.temp");
	public static final Path CLIENT_HELPER_DIR = CLIENT_DIR.resolve("helper");
	public static final Path CLIENT_HELPER_LEASE_FILE = CLIENT_HELPER_DIR.resolve("running.lock");
	public static final Path LOCAL_DATA_DIR = AUTOMODPACK_DIR.resolve("data");

	public static final Path SERVER_DIR = AUTOMODPACK_DIR.resolve("server");
	public static final Path SERVER_PROJECTION_FILE = SERVER_DIR.resolve("current-projection.json");
	public static final Path SERVER_JOURNAL_FILE = SERVER_DIR.resolve("journal.jsonl");
	public static final Path SERVER_LEDGER_FILE = SERVER_DIR.resolve("ledger.json");
	public static final Path SERVER_STAGING_DIR = SERVER_DIR.resolve("staging");
	public static final Path PATCH_NOTES_FILE = AUTOMODPACK_DIR.resolve("patch-notes.md");

	public static final Path CREDENTIALS_DIR = AUTOMODPACK_DIR.resolve("credentials");
	public static final Path SERVER_CERT_FILE = CREDENTIALS_DIR.resolve("certificate.crt");
	public static final Path SERVER_PRIVATE_KEY_FILE = CREDENTIALS_DIR.resolve("private-key.pem");
	public static final Path PROVISIONING_SECRET_FILE = CREDENTIALS_DIR.resolve("provisioning-secret");
	public static final Path SERVER_SECRETS_FILE = SERVER_DIR.resolve("secrets.json");

	public static final Path HOST_MODPACK_DIR = AUTOMODPACK_DIR.resolve("host-modpack");
	public static final Path HOST_CONTENT_MODPACK_DIR = HOST_MODPACK_DIR.resolve("main");
	public static final Path MODPACK_CONTENT_FILE = Path.of("automodpack-content.json");
	public static final Path SERVER_CONFIG_FILE = AUTOMODPACK_DIR.resolve("server-config.json");
	public static final Path BOOTSTRAP_FILE = AUTOMODPACK_DIR.resolve("automodpack-bootstrap.json");
	public static final Path BOOTSTRAP_EXPORT_FILE = AUTOMODPACK_DIR.resolve("automodpack-bootstrap.exported.json");
	public static final Path CLIENT_CONFIG_FILE = AUTOMODPACK_DIR.resolve("client-config.json");

	public static final String DATA_ROOT_PROPERTY = "automodpack.data.root";
	public static final String DATA_ROOT_ENV = "AUTOMODPACK_DATA_ROOT";

	private StoragePaths() {}
}
