package pl.skidam.automodpack_core.modpack;

import static pl.skidam.automodpack_core.Constants.LOGGER;
import static pl.skidam.automodpack_core.Constants.clientResourcePackStateFile;

import java.io.IOException;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Set;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

/**
 * Remembers, per modpack, which auto-applied resource pack filenames the player manually disabled
 * in-game, so a later join doesn't re-enable something they deliberately turned off.
 */
public class ClientResourcePackStateManager {

	private static final ClientResourcePackStateManager INSTANCE = new ClientResourcePackStateManager(clientResourcePackStateFile);

	private final Path stateFile;
	private final Jsons.ClientResourcePackStateFields state;

	public static ClientResourcePackStateManager getManager() {
		return INSTANCE;
	}

	ClientResourcePackStateManager(Path stateFile) {
		this.stateFile = stateFile;
		this.state = ConfigTools.readOrCreate(stateFile, Jsons.ClientResourcePackStateFields.class, Jsons.ClientResourcePackStateFields::new);
		if (this.state.modpacks == null) this.state.modpacks = new HashMap<>();
	}

	public Set<String> getDisabled(String modpackId) {
		if (modpackId == null || modpackId.isBlank()) return Set.of();
		var modpackState = state.modpacks.get(modpackId);
		return modpackState == null || modpackState.userDisabled == null ? Set.of() : modpackState.userDisabled;
	}

	/** Records that the player turned this resource pack off; future joins will leave it disabled. */
	public void markDisabled(String modpackId, String fileName) {
		if (modpackId == null || modpackId.isBlank() || fileName == null || fileName.isBlank()) return;
		state.modpacks.computeIfAbsent(modpackId, id -> new Jsons.ClientResourcePackStateFields.ModpackResourcePackState(new HashSet<>())).userDisabled.add(fileName);
		save();
	}

	/** Clears a remembered disable, e.g. if the player re-enabled the pack manually. */
	public void markEnabled(String modpackId, String fileName) {
		if (modpackId == null || modpackId.isBlank() || fileName == null || fileName.isBlank()) return;
		var modpackState = state.modpacks.get(modpackId);
		if (modpackState == null || modpackState.userDisabled == null) return;
		if (modpackState.userDisabled.remove(fileName)) save();
	}

	public void save() {
		try {
			ConfigTools.writeAtomic(stateFile, state);
		} catch (IOException e) {
			LOGGER.error("Failed to save client resource pack state", e);
		}
	}
}
