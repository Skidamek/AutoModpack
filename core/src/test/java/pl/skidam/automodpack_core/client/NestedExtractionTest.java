package pl.skidam.automodpack_core.client;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.Test;

import pl.skidam.automodpack_core.utils.FileTrees;

class NestedExtractionTest {

	@Test
	void backslashEntryNamesMaterializeAtTheCanonicalDerivedPath() throws IOException {
		Path inspection = Files.createTempDirectory("nested-extraction-");
		try {
			Path root = inspection.resolve("mods").resolve("pack.jar");
			Files.createDirectories(root.getParent());
			Files.write(root, jar(Map.of("META-INF/jars/a\\b.jar", jar(Map.of("x.txt", "x".getBytes())))));

			ClientUpdatePlanBuilder.extractNestedJars(inspection);

			// The detector derives paths through LogicalPath.normalize, which splits backslash names - extraction must agree.
			Path base = inspection.resolve("nested").resolve("mods").resolve("pack.jar");
			assertTrue(Files.isRegularFile(base.resolve("META-INF/jars/a/b.jar")), "extracted: " + list(inspection));
		} finally {
			FileTrees.delete(inspection);
		}
	}

	@Test
	void extractionFlattensEveryDepthUnderTheRootPrefix() throws IOException {
		Path inspection = Files.createTempDirectory("nested-extraction-");
		try {
			Path root = inspection.resolve("mods").resolve("pack.jar");
			Files.createDirectories(root.getParent());
			Files.write(root, jar(Map.of("META-INF/jars/a.jar", jar(Map.of("META-INF/jars/b.jar", jar(Map.of("b.txt", "b".getBytes())))))));

			ClientUpdatePlanBuilder.extractNestedJars(inspection);

			// Every extracted jar keeps its entry path relative to the root's prefix, matching NestedConflicts' derivation.
			Path base = inspection.resolve("nested").resolve("mods").resolve("pack.jar");
			assertTrue(Files.isRegularFile(base.resolve("META-INF/jars/a.jar")), "extracted: " + list(inspection));
			assertTrue(Files.isRegularFile(base.resolve("META-INF/jars/b.jar")), "extracted: " + list(inspection));
		} finally {
			FileTrees.delete(inspection);
		}
	}

	private static String list(Path root) throws IOException {
		StringBuilder names = new StringBuilder();
		try (var stream = Files.walk(root)) {
			for (Path path : stream.sorted().toList()) names.append(inspectionRelative(root, path)).append(' ');
		}
		return names.toString();
	}

	private static String inspectionRelative(Path root, Path path) {
		return root.relativize(path).toString();
	}

	private static byte[] jar(Map<String, byte[]> entries) throws IOException {
		ByteArrayOutputStream bytes = new ByteArrayOutputStream();
		try (ZipOutputStream zip = new ZipOutputStream(bytes)) {
			for (Map.Entry<String, byte[]> entry : entries.entrySet()) {
				zip.putNextEntry(new ZipEntry(entry.getKey()));
				zip.write(entry.getValue());
				zip.closeEntry();
			}
		}
		return bytes.toByteArray();
	}
}
