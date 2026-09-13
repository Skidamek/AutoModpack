package pl.skidam.automodpack_core.update;

import java.util.List;

import pl.skidam.automodpack_core.change.ChangeSet;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;

/**
 * The durable spelling of an {@link UpdatePlan}. Gson versions shipped in older Minecraft releases cannot
 * deserialize records, so the persisted document is this class tree — flat generation identity, the row classes,
 * and the generated copies reduced to their loader-facing path, hash, and size — while the in-memory record is
 * built back from it.
 */
public class UpdatePlanFields {
	public String modpackId;
	public String contentToken;
	public String policySha1;
	public String ledgerDigest;
	public List<UpdatePlan.Operation> operations;
	public List<UpdatePlan.ProjectedFile> projectedFinalState;
	public ClientConfigJsons.ClientConfigFieldsV3 plannedClientConfig;
	public List<UpdatePlan.RestartReason> restartReasons;
	public List<UpdatePlan.Preservation> preservations;
	public List<UpdatePlan.BaselineCapture> baselineCaptures;
	public List<UpdatePlan.Conflict> conflicts;
	public List<ClientStorageJsons.ClientGeneratedCopiesFields.EntryFields> generatedCopies;
	public ChangeSet.Fields consequences;
}
