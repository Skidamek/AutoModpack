package pl.skidam.automodpack_core.utils;

import java.nio.file.Path;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.GenerationJsons;
import pl.skidam.automodpack_core.modpack.generation.PackDocument;

public class ModpackContentTools {
	public static GenerationJsons.HeadDocumentFields readHeadDocument(Path path) {
		return ConfigTools.read(path, GenerationJsons.HeadDocumentFields.class).orElse(null);
	}

	public static PackDocument readPackDocument(Path path) {
		GenerationJsons.HeadDocumentFields fields = readHeadDocument(path);
		return fields == null ? null : PackDocument.fromFields(fields);
	}
}
