package pl.skidam.automodpack_core.utils;

import java.nio.file.Path;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.ModpackJsons;
import pl.skidam.automodpack_core.modpack.generation.GenerationPatchNoteHistory;
import pl.skidam.automodpack_core.modpack.generation.GenerationRecord;

public class ModpackContentTools {
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
}
