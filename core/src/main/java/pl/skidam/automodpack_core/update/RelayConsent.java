package pl.skidam.automodpack_core.update;

import java.io.IOException;
import java.nio.file.Files;
import java.util.TreeSet;

import pl.skidam.automodpack_core.config.ClientStorageJsons;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.modpack.ModpackId;

/**
 * The installed packs this instance's player agreed to share with players joining their own world. Sharing re-serves
 * bytes a pack's server may only hand to its own players, so a pack is shared only after the player says so here.
 */
public final class RelayConsent {
	private RelayConsent() {}

	public static boolean isRelayed(ClientStorage storage, String modpackId) throws IOException {
		return read(storage).modpackIds.contains(ModpackId.requireValid(modpackId));
	}

	public static void setRelayed(ClientStorage storage, String modpackId, boolean relayed) throws IOException {
		String id = ModpackId.requireValid(modpackId);
		ClientStorageMutation.run(storage, () -> {
			ClientStorageJsons.RelayConsentFields fields = read(storage);
			if (relayed ? !fields.modpackIds.add(id) : !fields.modpackIds.remove(id)) return null;
			Files.createDirectories(storage.relayConsentFile().getParent());
			ConfigTools.writeAtomic(storage.relayConsentFile(), fields);
			return null;
		});
	}

	private static ClientStorageJsons.RelayConsentFields read(ClientStorage storage) throws IOException {
		ClientStorageJsons.RelayConsentFields fields = ConfigTools.readState(storage.relayConsentFile(), ClientStorageJsons.RelayConsentFields.class, "Relay consent", document -> {
			if (document.DO_NOT_CHANGE_IT != 1) throw new IllegalArgumentException("Relay consent file version " + document.DO_NOT_CHANGE_IT + " is not supported");
			return document;
		}).orElseGet(ClientStorageJsons.RelayConsentFields::new);
		fields.modpackIds = fields.modpackIds == null ? new TreeSet<>() : new TreeSet<>(fields.modpackIds);
		return fields;
	}
}
