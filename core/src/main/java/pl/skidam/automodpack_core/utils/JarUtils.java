package pl.skidam.automodpack_core.utils;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.FilterInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.CodeSource;
import java.util.List;
import java.util.Locale;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

public class JarUtils {
	private static final String JAR_SUFFIX = ".jar";

	public static boolean hasJarExtension(Path path) {
		return path != null && path.getFileName() != null && hasJarExtension(path.getFileName().toString());
	}

	public static boolean hasJarExtension(String filename) {
		return filename != null && filename.toLowerCase(Locale.ROOT).endsWith(JAR_SUFFIX);
	}

	public static boolean isRegularJar(Path path) {
		return hasJarExtension(path) && Files.isRegularFile(path);
	}

	/**
	 * Opens the bytes of a jar nested inside {@code jar}: one raw entry path per nesting level, outermost first.
	 * The outermost level reads seekably from the jar's central directory, deeper levels stream-scan their parent
	 * the way fabric-loader does for nests of nests.
	 */
	public static InputStream openNestedJar(Path jar, List<String> entries) throws IOException {
		if (entries.size() == 1) {
			ZipFile zip = new ZipFile(jar.toFile());
			ZipEntry entry = zip.getEntry(entries.get(0));
			if (entry == null) {
				zip.close();
				throw new IOException("Nested jar entry " + entries.get(0) + " is missing from " + jar);
			}
			InputStream stream;
			try {
				stream = zip.getInputStream(entry);
			} catch (IOException e) {
				zip.close();
				throw e;
			}
			return new FilterInputStream(stream) {
				@Override
				public void close() throws IOException {
					super.close();
					zip.close();
				}
			};
		}
		return nestedEntry(openNestedJar(jar, entries.subList(0, entries.size() - 1)), entries.get(entries.size() - 1));
	}

	private static InputStream nestedEntry(InputStream parent, String name) throws IOException {
		ZipInputStream zip = new ZipInputStream(parent);
		ZipEntry entry;
		while ((entry = zip.getNextEntry()) != null) {
			if (name.equals(entry.getName())) return new FilterInputStream(zip) {
			};
		}
		zip.close();
		throw new IOException("Nested jar entry " + name + " is missing from its parent jar");
	}

	/** Reads {@code in} to the end as a zip stream, so a jar whose later entries are corrupt fails the plan instead of fabric-loader's boot scan. */
	public static void validateStreamedJar(InputStream in) throws IOException {
		try (ZipInputStream zip = new ZipInputStream(in)) {
			while (zip.getNextEntry() != null) {
			}
		}
	}

	public static Path getJarPath(Class<?> clazz) {
		try {
			CodeSource codeSource = clazz.getProtectionDomain().getCodeSource();
			if (codeSource == null || codeSource.getLocation() == null) throw new IllegalStateException("CodeSource is null for " + clazz.getSimpleName());

			Path path = Path.of(codeSource.getLocation().toURI());
			return resolvePhysicalPath(path);
		} catch (Exception e) {
			throw new RuntimeException("Failed to determine JAR path for " + clazz.getSimpleName(), e);
		}
	}

	// Reflectively extracts the physical file path from the virtual (Union)FileSystem (e.g. Neo/Forge)
	private static Path resolvePhysicalPath(Path path) {
		try {
			Object fs = path.getFileSystem();

			Method method = fs.getClass().getMethod("getPrimaryPath");
			Object result = method.invoke(fs);

			if (result instanceof Path) return (Path) result;
		} catch (NoSuchMethodException ignored) { // Method doesn't exist, likely not a virtual FS (e.g Fabric)
		} catch (Exception e) {
			LOGGER.error("Failed to resolve physical path for {}", path, e);
		}
		return path;
	}
}
