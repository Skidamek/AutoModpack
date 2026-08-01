package pl.skidam.automodpack.client.resourcepack;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import net.minecraft.client.Minecraft;
import net.minecraft.server.packs.repository.PackRepository;

import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.ClientResourcePackStateManager;
import pl.skidam.automodpack_core.modpack.ClientSelectionManager;

/**
 * Automatically enables the resource packs a server's modpack groups declare via
 * GroupDeclaration.autoApplyResourcePacks, and remembers (per modpack) when the player manually
 * turns one back off so a later join doesn't re-enable it. Only ever touches pack-list entries it
 * owns; everything else the player has - their own resource packs - is left untouched.
 *
 * Must be called on the client thread (see Minecraft#execute).
 */
public class ResourcePackAutoApplier {

	private static final String ID_PREFIX = "file/";

	// What we personally applied last time, and for which modpack. Intentionally in-memory only -
	// reset every launch - so stale entries from a previous server are cleaned up lazily the next
	// time this runs, rather than requiring a dedicated disconnect hook.
	private static Set<String> previouslyAppliedFileNames = Set.of();
	private static String previouslyAppliedModpackId = null;

	public static void apply(Minecraft client, Jsons.ModpackContentFields serverModpackContent) {
		String modpackId = serverModpackContent.modpackId;
		Set<String> rawTarget = ClientSelectionManager.autoApplyResourcePackFiles(serverModpackContent);
		ClientResourcePackStateManager stateManager = ClientResourcePackStateManager.getManager();
		Set<String> disabled = stateManager.getDisabled(modpackId);

		// Prune remembered disables for packs the server no longer declares, so the state file
		// doesn't accumulate orphaned entries.
		for (String fileName : new HashSet<>(disabled)) {
			if (!rawTarget.contains(fileName)) stateManager.markEnabled(modpackId, fileName);
		}

		Set<String> target = new LinkedHashSet<>(rawTarget);
		target.removeAll(disabled);

		PackRepository repository = client.getResourcePackRepository();
		repository.reload(); // pick up files that were just downloaded/deleted this join - cheap, no reload screen
		List<String> currentlySelected = List.copyOf(repository.getSelectedIds());
		List<String> selected = new ArrayList<>(currentlySelected);

		// Detect packs we applied last time (for this same modpack) that are missing now - the
		// player turned them off themselves. Only counts if the server still wants them; if the
		// server dropped the pack entirely that's the cleanup case above, not a player action.
		if (Objects.equals(previouslyAppliedModpackId, modpackId)) {
			for (String fileName : previouslyAppliedFileNames) {
				if (!rawTarget.contains(fileName)) continue;
				if (!selected.contains(ID_PREFIX + fileName)) {
					stateManager.markDisabled(modpackId, fileName);
					target.remove(fileName);
				}
			}
		}

		// Lazy revert: drop whatever we previously enabled that isn't part of this join's target -
		// covers both a switch to a different modpack/server and files the server retired.
		for (String fileName : previouslyAppliedFileNames) {
			if (target.contains(fileName)) continue;
			selected.remove(ID_PREFIX + fileName);
		}

		// Enable the target packs. If one is already present under its own id, leave it exactly
		// where it is instead of forcing a reorder every join.
		for (String fileName : target) {
			String id = ID_PREFIX + fileName;
			if (selected.contains(id)) continue;
			if (!repository.isAvailable(id)) continue; // not on disk yet
			selected.add(id); // end of the list = highest priority
		}

		// Nothing to do - avoid the vanilla "loading resource packs" screen when rejoining with the
		// same set already active (e.g. reconnecting to the same server with no file changes).
		if (!selected.equals(currentlySelected)) {
			repository.setSelected(selected);
			client.options.updateResourcePacks(repository);
			client.options.save();
			client.reloadResourcePacks().exceptionally(e -> {
				LOGGER.error("Failed to reload resource packs after auto-apply", e);
				return null;
			});
		}

		previouslyAppliedFileNames = target;
		previouslyAppliedModpackId = modpackId;
	}
}
