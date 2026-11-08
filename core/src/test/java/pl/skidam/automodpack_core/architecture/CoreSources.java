package pl.skidam.automodpack_core.architecture;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

/** Locates the core main sources the architecture tripwires scan. */
final class CoreSources {
	private CoreSources() {}

	static Path main() throws IOException {
		Path root = Path.of("").toAbsolutePath();
		while (root != null && !Files.isRegularFile(root.resolve("settings.gradle.kts"))) root = root.getParent();
		if (root == null) throw new IllegalStateException("Could not locate the repository root (settings.gradle.kts) from " + Path.of("").toAbsolutePath());
		Path main = root.resolve("core/src/main/java");
		if (!Files.isDirectory(main)) throw new IllegalStateException("Missing core main sources at " + main);
		return main;
	}

	static List<Path> javaFiles() throws IOException {
		List<Path> files = new ArrayList<>();
		try (Stream<Path> walk = Files.walk(main())) {
			walk.filter(path -> path.toString().endsWith(".java")).forEach(files::add);
		}
		return files;
	}
}
