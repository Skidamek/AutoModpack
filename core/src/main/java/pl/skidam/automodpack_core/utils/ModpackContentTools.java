package pl.skidam.automodpack_core.utils;

import java.io.IOException;
import java.nio.file.Path;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;
import pl.skidam.automodpack_core.modpack.generation.GenerationTarget;
import pl.skidam.automodpack_core.modpack.generation.OwnershipLedger;

public class ModpackContentTools {
	public static ModpackJsons.ModpackContentFields read(Path path) {
		return ConfigTools.read(path, ModpackJsons.ModpackContentFields.class).map(ModpackContentTools::requireValid).orElse(null);
	}

	public static void write(Path path, ModpackJsons.ModpackContentFields content) throws IOException {
		if (!isValid(content)) throw new ConfigTools.ConfigException("Invalid selected modpack content");
		ConfigTools.writeAtomic(path, content);
	}

	public static GenerationRecord readGenerationRecord(Path path) {
		return ConfigTools.read(path, ModpackJsons.CompleteModpackContentFields.class).map(GenerationRecord::fromFields).orElse(null);
	}

	public static ModpackJsons.CompleteModpackContentFields readCompleteFields(Path path) {
		ModpackJsons.CompleteModpackContentFields fields = ConfigTools.read(path, ModpackJsons.CompleteModpackContentFields.class).orElse(null);
		if (fields == null) return null;
		GenerationRecord.fromFields(fields);
		GenerationPatchNoteHistory.fromFields(fields);
		return fields;
	}

	private static ModpackJsons.ModpackContentFields requireValid(ModpackJsons.ModpackContentFields content) {
		if (!isValid(content)) throw new ConfigTools.ConfigException("Invalid selected modpack content");
		return content;
	}

	private static boolean isValid(ModpackJsons.ModpackContentFields content) {
		if (content == null || content.list == null || content.selectedGroups == null || content.ownershipLedger == null) return false;
		try {
			GenerationTarget.fromFlat(content);
			OwnershipLedger ledger = OwnershipLedger.fromFields(content.ownershipLedger);
			return content.modpackId.equals(ledger.modpackId());
		} catch (RuntimeException e) {
			return false;
		}
	}
}
