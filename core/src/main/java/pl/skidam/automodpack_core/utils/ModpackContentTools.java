package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Path;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
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
		Jsons.CompleteModpackContentFields fields = ConfigTools.read(path, Jsons.CompleteModpackContentFields.class).orElse(null);
		if (fields == null) return null;
		GenerationRecord.fromFields(fields);
		GenerationPatchNoteHistory.fromFields(fields);
		return fields;
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

}
