package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.generation.PackTarget;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.utils.HashUtils;

/**
 * The single write-ahead record for one client update. It carries the reviewed {@link UpdatePlan} itself — the one
 * durable spelling of what this update means — plus the transactional context the plan cannot know (the observed
 * client state it was planned against, the selection intents, the target's ledger) and the execution lifecycle.
 */
public final class UpdateTransaction {
	public static final int CURRENT_SCHEMA_VERSION = 1;

	public int schemaVersion;
	public String transactionId;
	public Purpose purpose;
	public Phase phase;
	/** The durable plan. A class tree, not records: the Gson shipped in Minecraft 1.18 cannot deserialize records. */
	public UpdatePlan plan;
	public String targetPlatform;
	public boolean expectedPriorSelectionPresent;
	public List<String> expectedPriorRequestedGroups;
	public List<String> expectedPriorRequestedCategories;
	public List<String> expectedPriorExcludedGroups;
	public List<String> requestedGroups;
	public List<String> requestedCategories;
	public List<String> excludedGroups;
	public String overlayDigest;
	public ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig;
	public GenerationJsons.OwnershipLedgerFields ownershipLedger;
	/** The state-history kind of this mutation when the flow itself knows it better than the purpose mapping: rollbacks declare it, everything else derives. */
	public String stateKind = "";
	/** The state-history entry this mutation restores, once the state revert engine lands; {@code -1} when this is not a restore. */
	public long stateRestoreOfSeq = -1;
	public Status resultStatus;
	public String resultOperation;
	public String resultPath;
	public String resultMessage;

	public UpdateTransaction() {}

	/**
	 * Reads the persisted transaction file, returning null when none exists; unusable content is set aside as evidence
	 * and treated as absent, since the replan recovery rebuilds from the leftover directories, while read failures propagate.
	 */
	public static UpdateTransaction read(Path path) throws IOException {
		return ConfigTools.readState(path, UpdateTransaction.class, "Persisted update transaction", UpdateTransaction::validated).orElse(null);
	}

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

	public static UpdateTransaction create(UpdatePlan plan, SelectedModpackTarget target, String overlayDigest,
			ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) {
		Objects.requireNonNull(plan, "plan");
		Objects.requireNonNull(target, "target");
		if (!plan.modpackId().equals(target.manifest().modpackId())) throw new IllegalArgumentException("Plan and selected target modpack IDs disagree");
		if (!plan.packTarget().equals(target.packTarget())) throw new IllegalArgumentException("Plan and selected target generation identities disagree");
		if (!plan.packTarget().equals(PackTarget.fromFlat(target.flatTarget())))
			throw new IllegalArgumentException("Plan and selected flat target generation identities disagree");

		UpdateTransaction transaction = base(Purpose.MODPACK_UPDATE);
		fillGeneration(transaction, plan.packTarget(), target.document().ownershipLedger());
		transaction.targetPlatform = target.platform() == null ? null : target.platform().id();
		transaction.expectedPriorSelectionPresent = target.expectedPriorIntent() != null;
		transaction.expectedPriorRequestedGroups = intentValues(target.expectedPriorIntent(), IntentPart.GROUPS);
		transaction.expectedPriorRequestedCategories = intentValues(target.expectedPriorIntent(), IntentPart.CATEGORIES);
		transaction.expectedPriorExcludedGroups = intentValues(target.expectedPriorIntent(), IntentPart.EXCLUDED);
		transaction.requestedGroups = new ArrayList<>(target.selection().intent().requestedGroups());
		transaction.requestedCategories = new ArrayList<>(target.selection().intent().requestedCategories());
		transaction.excludedGroups = new ArrayList<>(target.selection().intent().excludedGroups());
		transaction.overlayDigest = overlayDigest == null ? "" : overlayDigest;
		transaction.expectedClientConfig = copyConfig(expectedClientConfig);
		transaction.plan = plan;
		return transaction;
	}

	public static UpdateTransaction createRemoval(UpdatePlan plan, ClientPlatform platform, SelectionIntent expectedPriorIntent, GenerationJsons.OwnershipLedgerFields ownershipLedger,
			String overlayDigest, ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) {
		return createRemovalLike(Purpose.MODPACK_REMOVAL, plan, platform, expectedPriorIntent, ownershipLedger, overlayDigest, expectedClientConfig);
	}

	public static UpdateTransaction createDeactivation(UpdatePlan plan, ClientPlatform platform, SelectionIntent expectedPriorIntent, GenerationJsons.OwnershipLedgerFields ownershipLedger,
			String overlayDigest, ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) {
		return createRemovalLike(Purpose.MODPACK_DEACTIVATION, plan, platform, expectedPriorIntent, ownershipLedger, overlayDigest, expectedClientConfig);
	}

	private static UpdateTransaction createRemovalLike(Purpose purpose, UpdatePlan plan, ClientPlatform platform, SelectionIntent expectedPriorIntent,
			GenerationJsons.OwnershipLedgerFields ownershipLedger, String overlayDigest, ClientConfigJsons.ClientConfigFieldsV3 expectedClientConfig) {
		Objects.requireNonNull(plan, "plan");
		UpdateTransaction transaction = base(purpose);
		fillGeneration(transaction, plan.packTarget(), OwnershipLedger.fromFields(ownershipLedger));
		transaction.targetPlatform = platform == null ? null : platform.id();
		transaction.expectedPriorSelectionPresent = expectedPriorIntent != null;
		transaction.expectedPriorRequestedGroups = intentValues(expectedPriorIntent, IntentPart.GROUPS);
		transaction.expectedPriorRequestedCategories = intentValues(expectedPriorIntent, IntentPart.CATEGORIES);
		transaction.expectedPriorExcludedGroups = intentValues(expectedPriorIntent, IntentPart.EXCLUDED);
		transaction.requestedGroups = List.of();
		transaction.requestedCategories = List.of();
		transaction.excludedGroups = List.of();
		transaction.overlayDigest = overlayDigest == null ? "" : overlayDigest;
		transaction.expectedClientConfig = copyConfig(expectedClientConfig);
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
		transaction.phase = Phase.PLANNED;
		return transaction;
	}

	private enum IntentPart {
		GROUPS, CATEGORIES, EXCLUDED
	}

	private static List<String> intentValues(SelectionIntent intent, IntentPart part) {
		if (intent == null) return List.of();
		return switch (part) {
			case GROUPS -> new ArrayList<>(intent.requestedGroups());
			case CATEGORIES -> new ArrayList<>(intent.requestedCategories());
			case EXCLUDED -> new ArrayList<>(intent.excludedGroups());
		};
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
		return targetPlatform == null ? null : ClientPlatform.parse(targetPlatform);
	}

	public SelectionIntent expectedPriorIntent() {
		return expectedPriorSelectionPresent ? new SelectionIntent(expectedPriorRequestedGroups, expectedPriorRequestedCategories, expectedPriorExcludedGroups) : null;
	}

	public SelectionIntent targetIntent() {
		return new SelectionIntent(requestedGroups, requestedCategories, excludedGroups);
	}

	/** The selection the transaction plans for; generated-copy state is keyed by it. */
	public String selectionDigest() {
		return purpose == Purpose.MODPACK_UPDATE ? digest(targetIntent()) : digest(expectedPriorIntent());
	}

	public static String digest(SelectionIntent intent) {
		if (intent == null) return "";
		MessageDigest digest = HashUtils.newSha1Digest();
		digest.update("automodpack-selection-v2\n".getBytes(StandardCharsets.UTF_8));
		for (String value : intent.requestedGroups().stream().sorted().toList()) digest.update(("group=" + value + "\n").getBytes(StandardCharsets.UTF_8));
		for (String value : intent.requestedCategories().stream().sorted().toList()) digest.update(("category=" + value + "\n").getBytes(StandardCharsets.UTF_8));
		for (String value : intent.excludedGroups().stream().sorted().toList()) digest.update(("excluded=" + value + "\n").getBytes(StandardCharsets.UTF_8));
		return HexFormat.of().formatHex(digest.digest());
	}

	private static ClientConfigJsons.ClientConfigFieldsV3 copyConfig(ClientConfigJsons.ClientConfigFieldsV3 config) {
		return new ClientConfigJsons.ClientConfigFieldsV3(Objects.requireNonNull(config, "expectedClientConfig"));
	}

	public enum Phase {
		PLANNED,
		PREPARING,
		/** No longer persisted by the executor, but older journals carry it and it still means publication started on read. */
		PROJECTED,
		SWAPPING,
		COMMITTED,
		DEFERRED
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
