package pl.skidam.automodpack_core.client;

import java.io.IOException;
import java.util.EnumSet;
import java.util.Set;

import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.launchers.LauncherVersionSwapper;
import pl.skidam.automodpack_core.storage.GameDirectory;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdatePlan.RestartReason;
import pl.skidam.automodpack_core.update.UpdateTransaction;
import pl.skidam.automodpack_core.update.UpdateTransactionExecutor;

public final class UpdateTransactionSupport {
	private UpdateTransactionSupport() {}

	public static ClientStorage storage() {
		return ClientStorage.open(GameDirectory.current());
	}

	/**
	 * The executor for a booted client or server process and for the detached helper alike: the planned switch axes
	 * ride the transaction's restart reasons, so no client session state is needed to apply the launcher metadata.
	 */
	public static UpdateTransactionExecutor executor() {
		ClientStorage storage = storage();
		return new UpdateTransactionExecutor(new UpdateTransactionExecutor.Context(storage, (transaction, manifest) -> applyLauncherMetadata(transaction, manifest)));
	}

	private static void applyLauncherMetadata(UpdateTransaction transaction, ModpackJsons.ModpackContentFields manifest) throws IOException {
		EnumSet<LauncherVersionSwapper.Axis> axes = switchAxes(transaction.plan().restartReasons());
		if (axes.isEmpty()) return;
		LauncherVersionSwapper.apply(axes, manifest.loader, manifest.loaderVersion, manifest.mcVersion);
	}

	private static EnumSet<LauncherVersionSwapper.Axis> switchAxes(Set<RestartReason> reasons) {
		EnumSet<LauncherVersionSwapper.Axis> axes = EnumSet.noneOf(LauncherVersionSwapper.Axis.class);
		if (reasons.contains(RestartReason.CHANGED_GAME_VERSION)) axes.add(LauncherVersionSwapper.Axis.GAME_VERSION);
		if (reasons.contains(RestartReason.CHANGED_LOADER_TYPE)) axes.add(LauncherVersionSwapper.Axis.LOADER_TYPE);
		if (reasons.contains(RestartReason.CHANGED_LOADER_VERSION)) axes.add(LauncherVersionSwapper.Axis.LOADER_VERSION);
		return axes;
	}
}
