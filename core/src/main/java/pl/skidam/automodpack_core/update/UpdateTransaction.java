package pl.skidam.automodpack_core.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.config.SelectionJsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The single write-ahead record for one client update. It carries the reviewed {@link UpdatePlan} itself - the one
 * durable spelling of what this update means - plus the transactional context the plan cannot know (the observed
 * client state it was planned against, the selection intents, the target's ledger) and the execution result. The
 * execution phase is not part of it: {@link UpdateJournal} owns that, one file, one carrier.
 */
public final class UpdateTransaction {
	public static final int CURRENT_SCHEMA_VERSION = 3;

	public int schemaVersion;
	public String transactionId;
	public Purpose purpose;
	/** The durable plan. A class tree, not records: the Gson shipped in Minecraft 1.18 cannot deserialize records. */
	public UpdatePlan plan;
	public SelectionJsons.SelectionFields expectedPriorSelection;
	public SelectionJsons.SelectionFields targetSelection;
	public String overlayDigest;
	public ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig;
	public String expectedSelectedModpackId;
	public GenerationJsons.OwnershipLedgerFields ownershipLedger;
	/** The installed projection this plan was planned against, null when nothing was installed. */
	public GenerationJsons.OwnershipLedgerFields expectedInstalledLedger;
	/** The state-history kind of this mutation when the flow itself knows it better than the purpose mapping: rollbacks declare it, everything else derives. */
	public String stateKind = "";
	public Status resultStatus;
	public String resultOperation;
	public String resultPath;
	public String resultMessage;

	public UpdateTransaction() {}

	/**
	 * The document's completeness contract: a transaction without a whole, constructor-validated plan is unusable
	 * content and is set aside. Gson fills the class tree without running constructors, so validation lives here.
	 */
	static UpdateTransaction validated(UpdateTransaction transaction) {
		if (transaction.schemaVersion != CURRENT_SCHEMA_VERSION || transaction.plan == null)
			throw new IllegalArgumentException("Persisted update transaction fields are incomplete");
		transaction.plan = transaction.plan.validated();
		return transaction;
	}

	public static UpdateTransaction create(UpdatePlan plan, SelectedModpackTarget target, String overlayDigest, PlannedAgainst plannedAgainst) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(target, "target");
		if (!plan.modpackId().equals(target.manifest().modpackId())) throw new IllegalArgumentException("Plan and selected target modpack IDs disagree");
		if (!plan.packTarget().equals(target.packTarget())) throw new IllegalArgumentException("Plan and selected target generation identities disagree");
		if (!plan.packTarget().equals(PackTarget.fromFlat(target.flatTarget())))
			throw new IllegalArgumentException("Plan and selected flat target generation identities disagree");

		UpdateTransaction transaction = base(Purpose.MODPACK_UPDATE);
		fillGeneration(transaction, plan.packTarget(), target.document().ownershipLedger());
		transaction.expectedPriorSelection = SelectionJsons.SelectionFields.from(target.expectedPriorIntent());
		SelectionIntent targetIntent = target.selection().intent().withPlatform(target.platform());
		transaction.targetSelection = SelectionJsons.SelectionFields.from(targetIntent);
		transaction.overlayDigest = overlayDigest == null ? "" : overlayDigest;
		transaction.expectedClientConfig = plannedAgainst.clientConfig();
		transaction.expectedSelectedModpackId = plannedAgainst.selectedModpackId();
		transaction.expectedInstalledLedger = plannedAgainst.installedLedger();
		transaction.plan = plan;
		return transaction;
	}

	public static UpdateTransaction createRemoval(UpdatePlan plan, ClientPlatform platform, SelectionIntent expectedPriorIntent, String overlayDigest, PlannedAgainst plannedAgainst) {
		return createRemovalLike(Purpose.MODPACK_REMOVAL, plan, platform, expectedPriorIntent, overlayDigest, plannedAgainst);
	}

	public static UpdateTransaction createDeactivation(UpdatePlan plan, ClientPlatform platform, SelectionIntent expectedPriorIntent, String overlayDigest, PlannedAgainst plannedAgainst) {
		return createRemovalLike(Purpose.MODPACK_DEACTIVATION, plan, platform, expectedPriorIntent, overlayDigest, plannedAgainst);
	}

	private static UpdateTransaction createRemovalLike(Purpose purpose, UpdatePlan plan, ClientPlatform platform, SelectionIntent expectedPriorIntent,
			String overlayDigest, PlannedAgainst plannedAgainst) {
		Objects.requireNonNull(plan, "plan");
		// A removal plans the installed generation away, so it always has an installed ledger to prove against.
		OwnershipLedger ledger = OwnershipLedger.fromFields(Objects.requireNonNull(plannedAgainst.installedLedger(), "plannedAgainst.installedLedger"));
		UpdateTransaction transaction = base(purpose);
		fillGeneration(transaction, plan.packTarget(), ledger);
		transaction.expectedPriorSelection = SelectionJsons.SelectionFields.from(expectedPriorIntent);
		transaction.targetSelection = SelectionJsons.SelectionFields.from(new SelectionIntent(Set.of(), Set.of(), platform));
		transaction.overlayDigest = overlayDigest == null ? "" : overlayDigest;
		transaction.expectedClientConfig = plannedAgainst.clientConfig();
		transaction.expectedSelectedModpackId = plannedAgainst.selectedModpackId();
		transaction.expectedInstalledLedger = plannedAgainst.installedLedger();
		transaction.plan = plan;
		return transaction;
	}

	/** The pending work must be able to rebuild its target generation offline, so every transaction carries its target's exact ledger. */
	private static void fillGeneration(UpdateTransaction transaction, PackTarget target, OwnershipLedger ledger) {
		if (!target.modpackId().equals(ledger.modpackId()) || !target.ledgerDigest().equals(ledger.digest()))
			throw new IllegalArgumentException("Transaction generation identity does not match the target ledger");
		transaction.ownershipLedger = ledger.toFields();
	}

	private static UpdateTransaction base(Purpose purpose) {
		UpdateTransaction transaction = new UpdateTransaction();
		transaction.schemaVersion = CURRENT_SCHEMA_VERSION;
		transaction.transactionId = UUID.randomUUID().toString();
		transaction.purpose = purpose;
		return transaction;
	}

	public UpdatePlan plan() {
		return plan;
	}

	public String modpackId() {
		return plan().modpackId();
	}

	public PackTarget packTarget() {
		return plan().packTarget();
	}

	public ClientPlatform platform() {
		return targetSelection == null || targetSelection.platform == null ? null : ClientPlatform.parse(targetSelection.platform);
	}

	/** The installed projection this plan was planned against, or null when the plan saw no installed generation. */
	public OwnershipLedger expectedInstalled() {
		return expectedInstalledLedger == null ? null : OwnershipLedger.fromFields(expectedInstalledLedger);
	}

	public SelectionIntent expectedPriorIntent() {
		return expectedPriorSelection == null ? null : expectedPriorSelection.toIntent();
	}

	/** The target selection carrying the platform this transaction resolved under, so the commit pins the exact resolution. */
	public SelectionIntent targetIntent() {
		return targetSelection.toIntent();
	}

	/** The selection the transaction plans for; generated-copy state is keyed by it. */
	public String selectionDigest() {
		return purpose == Purpose.MODPACK_UPDATE ? digest(targetIntent()) : digest(expectedPriorIntent());
	}

	public static String digest(SelectionIntent intent) {
		if (intent == null) return "";
		MessageDigest digest = HashUtils.newSha1Digest();
		digest.update("automodpack-selection-v3\n".getBytes(StandardCharsets.UTF_8));
		for (String value : intent.enabled().stream().sorted().toList()) digest.update(("enabled=" + value + "\n").getBytes(StandardCharsets.UTF_8));
		for (String value : intent.disabled().stream().sorted().toList()) digest.update(("disabled=" + value + "\n").getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(digest.digest());
	}

	public enum Phase {
		PLANNED,
		PREPARING,
		/** Every live operation landed and the projection convergence may have begun: persisted before its first write. */
		PROJECTED,
		COMMITTED
	}

	public enum Purpose {
		MODPACK_UPDATE,
		MODPACK_DEACTIVATION,
		MODPACK_REMOVAL
	}

	public enum Status {
		SUCCESS,
		DEFERRED_LOCKED,
		REPLAN_REQUIRED,
		FAILED
	}
}
