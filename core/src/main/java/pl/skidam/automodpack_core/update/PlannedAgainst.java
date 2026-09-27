package pl.skidam.automodpack_core.update;

import java.util.Objects;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;

/**
 * The client state an update was planned against: the configuration and selection the review promised to apply, and
 * the installed projection its ownership proofs were proved on. The transaction carries it as the plan's preconditions,
 * so a commit judges the plan against the state it was built from rather than re-deriving that state when it runs.
 */
public record PlannedAgainst(ClientConfigJsons.ClientConfigFieldsV3 clientConfig, String selectedModpackId, GenerationJsons.OwnershipLedgerFields installedLedger) {
	public PlannedAgainst {
		clientConfig = new ClientConfigJsons.ClientConfigFieldsV3(Objects.requireNonNull(clientConfig, "expectedClientConfig"));
		selectedModpackId = selectedModpackId == null ? "" : selectedModpackId;
		// The ledger a proof names brings the receipt that proves it: OwnershipLedger recomputes the digest and refuses
		// a ledger that disagrees with it, so a recorded projection cannot be edited into a different one.
		installedLedger = installedLedger == null ? null : OwnershipLedger.fromFields(installedLedger).toFields();
	}
}
