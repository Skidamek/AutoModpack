package pl.skidam.automodpack_core.loader;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileSystem;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/** Reads standard Java service-provider declarations from mounted archives. */
public final class LoaderServiceFiles {
	private LoaderServiceFiles() {}

	public static List<String> readImplementations(FileSystem fileSystem, String serviceFile) {
		Path service = fileSystem.getPath(serviceFile);
		if (!Files.exists(service)) return List.of();
		List<String> implementations = new ArrayList<>();
		try (BufferedReader reader = new BufferedReader(new InputStreamReader(Files.newInputStream(service), StandardCharsets.UTF_8))) {
			String line;
			while ((line = reader.readLine()) != null) {
				int comment = line.indexOf('#');
				if (comment >= 0) line = line.substring(0, comment);
				line = line.trim();
				if (!line.isEmpty()) implementations.add(line);
			}
		} catch (Exception e) {
			LOGGER.error("[AutoModpack] Failed to read {}", serviceFile, e);
		}
		return List.copyOf(implementations);
	}
}
