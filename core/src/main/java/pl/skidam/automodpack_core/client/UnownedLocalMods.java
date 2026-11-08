package pl.skidam.automodpack_core.client;

import java.nio.file.Path;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import pl.skidam.automodpack_core.Environment;
import pl.skidam.automodpack_core.config.ClientConfigJsons;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.loader.PinnedMods;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;
import pl.skidam.automodpack_core.modpack.group.ModpackPathPolicy;
import pl.skidam.automodpack_core.update.ClientStorage;
import pl.skidam.automodpack_core.update.UpdatePlan;

/**
 * Local mod files a pack change leaves untouched: direct children of the mods folder that the plan neither removes nor
 * rewrites, that the outgoing pack's ledger does not prove it owns, that are not client-wide pinned, and that are not
 * this running jar. The review asks about exactly these; an ordinary update of the same pack has none because its own
 * files are either replaced by the plan or already owned.
 */
final class UnownedLocalMods {
	private UnownedLocalMods() {}

	static Map<String, UpdatePlan.FileState> of(ClientStorage storage, ModpackJsons.ModpackContentFields installed, Map<UpdatePlan.FileKey, UpdatePlan.FileState> files, UpdatePlan plan,
			List<UpdatePlan.ModInfo> standardMods, ClientConfigJsons.ClientConfigFieldsV3 config) {
		if (installed != null && plan.modpackId().equals(installed.modpackId)) return Map.of();
		OwnershipLedger ledger = installed == null || installed.ownershipLedger == null ? null : OwnershipLedger.fromFields(installed.ownershipLedger);
		Set<String> pinned = PinnedMods.index(config == null ? List.of() : config.pinnedModIds);
		Map<String, UpdatePlan.ModInfo> modsByPath = new HashMap<>();
		for (UpdatePlan.ModInfo mod : standardMods) modsByPath.put(mod.relativePath(), mod);
		Map<UpdatePlan.FileKey, UpdatePlan.ProjectedFile> finalState = new HashMap<>();
		for (UpdatePlan.ProjectedFile projected : plan.projectedFinalState())
			if (projected != null) finalState.put(new UpdatePlan.FileKey(projected.root(), projected.relativePath()), projected);
		Environment environment = Environment.installed();
		Path thisModJar = environment == null ? null : environment.thisModJar();
		Path runningJar = thisModJar == null ? null : thisModJar.toAbsolutePath().normalize();
		Map<String, UpdatePlan.FileState> unowned = new TreeMap<>();
		for (var entry : files.entrySet()) {
			UpdatePlan.FileKey key = entry.getKey();
			if (key.root() != UpdatePlan.Root.GAME_DIR) continue;
			String relative = key.relativePath();
			if (!isDirectModChild(relative)) continue;
			if (runningJar != null && storage.gamePath(relative).toAbsolutePath().normalize().equals(runningJar)) continue;
			UpdatePlan.FileState observed = entry.getValue();
			UpdatePlan.ProjectedFile projected = finalState.get(key);
			if (projected == null || !projected.present() || projected.expectedSize() != observed.size() || !projected.expectedHash().equalsIgnoreCase(observed.sha1())) continue;
			UpdatePlan.ModInfo mod = modsByPath.get(relative);
			if (mod != null && PinnedMods.matches(pinned, mod.ids())) continue;
			if (ownedBy(ledger, relative, observed)) continue;
			unowned.put(relative, observed);
		}
		return Collections.unmodifiableMap(unowned);
	}

	/** A logical path that is one file directly under the mods root, compared by its separators, never through a Path. */
	private static boolean isDirectModChild(String logicalPath) {
		String prefix = ModpackPathPolicy.MODS_ROOT + "/";
		return logicalPath.startsWith(prefix) && logicalPath.length() > prefix.length() && logicalPath.indexOf('/', prefix.length()) < 0;
	}

	private static boolean ownedBy(OwnershipLedger ledger, String relative, UpdatePlan.FileState observed) {
		if (ledger == null) return false;
		OwnershipLedger.Entry entry = ledger.entries().get(relative);
		return entry != null && entry.historicalHashes().contains(new OwnershipLedger.Content(observed.sha1().toLowerCase(Locale.ROOT), observed.size()));
	}
}
