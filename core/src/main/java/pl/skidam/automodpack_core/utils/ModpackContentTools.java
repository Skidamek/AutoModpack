package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.*;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

public class ModpackContentTools {
	public static Jsons.ModpackContentFields read(Path path) {
		return ConfigTools.read(path, Jsons.ModpackContentFields.class).filter(ModpackContentTools::isValid).orElse(null);
	}

	public static void write(Path path, Jsons.ModpackContentFields content) throws IOException {
		if (!isValid(content)) throw new ConfigTools.ConfigException("Invalid modpack content");
		ConfigTools.writeAtomic(path, content);
	}

	private static boolean isValid(Jsons.ModpackContentFields content) {
		return content != null && content.list != null;
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

		Path path = modpackDir.getParent().resolve(hostModpackContentFile.getFileName()); // server
		if (!Files.exists(path)) {
			path = modpackDir.resolve(hostModpackContentFile.getFileName()); // client
			if (!Files.exists(path)) return Optional.empty();
		}

		return Optional.of(path);
	}
}
