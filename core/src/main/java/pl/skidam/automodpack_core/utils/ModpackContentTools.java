package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;

public class ModpackContentTools {
	public static Jsons.ModpackContentFields read(Path path) {
		return ConfigTools.read(path, Jsons.ModpackContentFields.class).map(ModpackContentTools::requireValid).orElse(null);
	}

	public static void write(Path path, Jsons.ModpackContentFields content) throws IOException {
		if (!isValid(content)) throw new ConfigTools.ConfigException("Invalid selected modpack content");
		ConfigTools.writeAtomic(path, content);
	}

	public static GenerationRecord readGenerationRecord(Path path) {
		return ConfigTools.read(path, Jsons.CompleteModpackContentFields.class).map(GenerationRecord::fromFields).orElse(null);
	}

	public static Jsons.CompleteModpackContentFields readCompleteFields(Path path) {
		GenerationRecord record = readGenerationRecord(path);
		return record == null ? null : record.toFields();
	}

	private static Jsons.ModpackContentFields requireValid(Jsons.ModpackContentFields content) {
		if (!isValid(content)) throw new ConfigTools.ConfigException("Invalid selected modpack content");
		return content;
	}

	private static boolean isValid(Jsons.ModpackContentFields content) {
		if (content == null || content.list == null || content.selectedGroups == null || content.ownershipLedger == null) return false;
		try {
			GenerationTarget.fromFlat(content);
			OwnershipLedger ledger = OwnershipLedger.fromFields(content.ownershipLedger);
			return content.modpackId.equals(ledger.modpackId());
		} catch (RuntimeException e) {
			return false;
		}
	}

	public static String getFileType(String file, Jsons.ModpackContentFields list) {
		for (Jsons.ModpackContentFields.ModpackContentItem item : list.list) {
			if (item.file.contains(file)) { // compare file absolute path if it contains item.file
				return item.type;
			}
		}
		return "other";
	}

	public static Optional<Path> getModpackDir(String modpack) {
		if (modpack == null || modpack.isEmpty()) return Optional.empty();

		// eg. modpack = /automodpack/modpacks/TestPack `directory`

		return Optional.of(modpacksDir.resolve(modpack));
	}

	public static Optional<Path> getModpackContentFile(Path modpackDir) {
		if (!Files.exists(modpackDir)) return Optional.empty();

		Path path = modpackDir.getParent().resolve(modpackContentFileName); // server
		if (!Files.exists(path)) {
			path = modpackDir.resolve(modpackContentFileName); // client
			if (!Files.exists(path)) return Optional.empty();
		}

		return Optional.of(path);
	}
}
