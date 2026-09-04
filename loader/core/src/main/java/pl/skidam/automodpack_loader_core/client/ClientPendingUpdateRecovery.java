package pl.skidam.automodpack_loader_core.client;

import java.io.IOException;
import java.util.Comparator;
import java.util.Objects;

import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.modpack.ModpackId;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;
import pl.skidam.automodpack_core.modpack.group.ClientPlatform;
import pl.skidam.automodpack_core.modpack.group.ClientSelectionStore;
import pl.skidam.automodpack_core.modpack.group.SelectedModpackTarget;
import pl.skidam.automodpack_core.modpack.group.SelectionIntent;
import pl.skidam.automodpack_core.update.ClientGenerationStore;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.ReviewedUpdatePlan;
import pl.skidam.automodpack_core.update.UpdateReplanRequiredException;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_core.utils.cache.FileCache;
import pl.skidam.automodpack_core.utils.cache.ModFileCache;
import pl.skidam.automodpack_loader_core.UpdateTransactionSupport;

/** Rebuilds a pending client intent from current mutable inputs after partial live-file work. */
public final class ClientPendingUpdateRecovery {
	private ClientPendingUpdateRecovery() {}

	public static UpdateTransactionExecutor.Execution replan(ClientStorage storage, UpdateTransaction pending, ModpackLoaderService modpackLoader, String loaderType)
			throws IOException {
		Objects.requireNonNull(storage, "storage");
		Objects.requireNonNull(pending, "pending");
		Objects.requireNonNull(modpackLoader, "modpack loader");
		Objects.requireNonNull(loaderType, "loader type");
		try {
			ClientUpdatePlanBuilder builder = new ClientUpdatePlanBuilder(storage, modpackLoader, loaderType);
			if (pending.purpose == UpdateTransaction.Purpose.MODPACK_UPDATE) return replanUpdate(storage, pending, builder);
			if (pending.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL || pending.purpose == UpdateTransaction.Purpose.MODPACK_DEACTIVATION)
				return replanRemoval(storage, pending, builder);
			throw new IOException("Only modpack transactions can be replanned: " + pending.purpose);
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Pending modpack update could not be replanned", e);
		}
	}

	private static UpdateTransactionExecutor.Execution replanUpdate(ClientStorage storage, UpdateTransaction pending, ClientUpdatePlanBuilder builder) throws Exception {
		ClientConfigJsons.ClientConfigFieldsV3 currentConfig = ConfigTools.read(storage.clientConfigFile(), ClientConfigJsons.ClientConfigFieldsV3.class)
				.orElseGet(ClientConfigJsons.ClientConfigFieldsV3::new);
		SelectedModpackTarget target = targetFor(storage, pending, currentConfig);
		try (FileCache cache = FileCache.open(storage.fileCacheDirectory()); ModFileCache modCache = ModFileCache.open(storage.modCacheDirectory())) {
			builder.reconcileEditableState(cache, target.flatTarget());
			ClientUpdatePlanBuilder.PreparedPlan prepared = builder.buildPlan(new ClientUpdatePlanBuilder.Input(target, target.flatTarget(), null, currentConfig, true), cache, modCache);
			if (!ReviewedUpdatePlan.isCompatible(pending, prepared.plan()))
				throw new UpdateReplanRequiredException(null, "Mutable inputs changed the pending update consequences; a new review is required");
			builder.preparePlanObjects(prepared.plan(), target.flatTarget());
			return UpdateTransactionSupport.executor().commit(prepared.plan(), target, prepared.overlayDigest(), prepared.expectedClientConfig());
		}
	}

	private static UpdateTransactionExecutor.Execution replanRemoval(ClientStorage storage, UpdateTransaction pending, ClientUpdatePlanBuilder builder) throws Exception {
		ClientUpdatePlanBuilder.RemovalPreparation preparation = builder.prepareRemoval();
		String overlayDigest = storage.overlayDigest(preparation.installed().modpackId);
		UpdateTransaction transaction;
		if (pending.purpose == UpdateTransaction.Purpose.MODPACK_REMOVAL)
			transaction = UpdateTransaction.createRemoval(preparation.plan(), ClientPlatform.current(), preparation.expectedPriorIntent(), preparation.installed().ownershipLedger,
					overlayDigest, preparation.expectedClientConfig());
		else
			transaction = UpdateTransaction.createDeactivation(preparation.plan(), ClientPlatform.current(), preparation.expectedPriorIntent(), preparation.installed().ownershipLedger,
					overlayDigest, preparation.expectedClientConfig());
		if (!ReviewedUpdatePlan.isCompatible(pending, preparation.plan()))
			throw new UpdateReplanRequiredException(null, "Mutable inputs changed the pending removal consequences; a new review is required");
		return UpdateTransactionSupport.executor().commit(transaction);
	}

	private static SelectedModpackTarget targetFor(ClientStorage storage, UpdateTransaction pending, ClientConfigJsons.ClientConfigFieldsV3 currentConfig) throws IOException {
		ClientGenerationStore generations = new ClientGenerationStore(storage);
		PackDocument pendingDocument = generations.document(pending);
		ClientStorageJsons.ClientGenerationStateFields active = storage.readActiveState();
		boolean configStillDescribesThePendingInput = active == null
				? currentConfig.selectedModpackId == null || currentConfig.selectedModpackId.isBlank()
				: Objects.equals(currentConfig.selectedModpackId, active.modpackId);
		PackDocument record;
		if (configStillDescribesThePendingInput || pending.modpackId.equals(currentConfig.selectedModpackId))
			record = newer(pendingDocument, newest(generations, pending.modpackId));
		else {
			if (!ModpackId.isValid(currentConfig.selectedModpackId))
				throw new IOException("Selected modpack changed to an invalid or empty ID while replanning the pending update");
			record = newest(generations, currentConfig.selectedModpackId);
			if (record == null) throw new IOException("Selected modpack generation is not installed: " + currentConfig.selectedModpackId);
		}
		ClientSelectionStore selections = new ClientSelectionStore(storage.selectionFile());
		SelectionIntent storedIntent = selections.get(record.manifest().modpackId()).orElse(null);
		if (record.manifest().modpackId().equals(pending.modpackId) && Objects.equals(storedIntent, pending.expectedPriorIntent()))
			return SelectedModpackTarget.prepare(record, storedIntent, pending.targetIntent(), pending.platform());
		if (storedIntent == null) return SelectedModpackTarget.prepareDefault(record, pending.platform());
		return SelectedModpackTarget.prepare(record, storedIntent, storedIntent, pending.platform());
	}

	private static PackDocument newest(ClientGenerationStore generations, String modpackId) throws IOException {
		if (!ModpackId.isValid(modpackId)) return null;
		return generations.newestDocument(modpackId);
	}

	private static PackDocument newer(PackDocument first, PackDocument second) {
		if (second == null) return first;
		if (first == null) return second;
		return Comparator.comparing(PackDocument::createdAt).thenComparing(PackDocument::contentToken).compare(first, second) >= 0 ? first : second;
	}
}
