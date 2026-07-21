package pl.skidam.automodpack_core.update;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.update.UpdatePlan.Operation;
import pl.skidam.automodpack_core.update.UpdatePlan.ProjectedFile;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;

public final class UpdateTransaction {
	public static final int CURRENT_SCHEMA_VERSION = 1;

	public int schemaVersion;
	public String transactionId;
	public String modpackId;
	public String targetManifestJson;
	public String canonicalModpackDirectory;
	public List<Operation> operations;
	public List<ProjectedFile> projectedFinalState;
	public Jsons.ClientConfigFieldsV3 plannedClientConfig;
	public List<String> plannedDeletionTimestamps;
	public List<RestartReason> restartReasons;

	public UpdateTransaction() {}

	public static UpdateTransaction create(UpdatePlan plan, Jsons.ModpackContentFields targetManifest, Path modpackDirectory) {
		UpdateTransaction transaction = new UpdateTransaction();
		transaction.schemaVersion = CURRENT_SCHEMA_VERSION;
		transaction.transactionId = UUID.randomUUID().toString();
		transaction.modpackId = plan.modpackId();
		transaction.targetManifestJson = ConfigTools.GSON.toJson(targetManifest);
		transaction.canonicalModpackDirectory = modpackDirectory.toAbsolutePath().normalize().toString();
		transaction.operations = List.copyOf(plan.operations());
		transaction.projectedFinalState = List.copyOf(plan.projectedFinalState());
		transaction.plannedClientConfig = plan.plannedClientConfig();
		transaction.plannedDeletionTimestamps = new ArrayList<>(plan.plannedDeletionTimestamps());
		transaction.restartReasons = new ArrayList<>(new LinkedHashSet<>(plan.restartReasons()));
		return transaction;
	}

	public Jsons.ModpackContentFields targetManifest() {
		return ConfigTools.parse(targetManifestJson, Jsons.ModpackContentFields.class);
	}
}
