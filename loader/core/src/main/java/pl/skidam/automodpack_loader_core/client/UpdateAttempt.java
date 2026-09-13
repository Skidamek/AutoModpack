package pl.skidam.automodpack_loader_core.client;

import java.io.IOException;
import java.util.Objects;

import pl.skidam.automodpack_core.loader.ModpackLoaderService;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;
import pl.skidam.automodpack_loader_core.client.RestartDecision.ApplyResult;

/**
 * One player-reviewed mutation of client pack state. Prepare and preview live on the adapter that knows the inputs;
 * this seam is the review and the one commit. Boot recovery of a pending transaction is {@link #resume}, not a third
 * owner.
 */
public interface UpdateAttempt {
	void approve();

	void cancel();

	boolean isApproved();

	ApplyResult commit() throws Exception;

	static UpdateTransactionExecutor.Execution resume(ClientStorage storage, UpdateTransaction pending, ModpackLoaderService modpackLoader, String loaderType)
			throws IOException {
		Objects.requireNonNull(storage, "storage");
		Objects.requireNonNull(pending, "pending");
		Objects.requireNonNull(modpackLoader, "modpack loader");
		Objects.requireNonNull(loaderType, "loader type");
		try {
			return switch (pending.purpose) {
				case MODPACK_UPDATE -> UpdateSession.resume(storage, pending, modpackLoader, loaderType);
				case MODPACK_REMOVAL, MODPACK_DEACTIVATION -> RemovalAttempt.resume(storage, pending, modpackLoader, loaderType);
			};
		} catch (IOException e) {
			throw e;
		} catch (Exception e) {
			throw new IOException("Pending modpack update could not be replanned", e);
		}
	}
}
