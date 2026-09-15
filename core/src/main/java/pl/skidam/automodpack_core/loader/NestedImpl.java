package pl.skidam.automodpack_core.loader;

import static pl.skidam.automodpack_core.Constants.LOGGER;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import pl.skidam.automodpack_core.utils.DurableFiles;
import pl.skidam.automodpack_core.utils.FileIntegrity;
import pl.skidam.automodpack_core.utils.HashUtils;
import pl.skidam.automodpack_core.utils.JarUtils;

/**
 * Real-file access to the universal outer jar's nested game impl ({@code META-INF/jarjar/automodpack-mod.jar}).
 * Loaders that cannot mount a nested zip (Knot needs a {@code file:} path; FML 10+ extracts nested jars exactly
 * like its native Jar-in-Jar) get the impl under the instance's impl-cache directory, re-extracted whenever the
 * outer jar's copy changes size or SHA-1.
 */
public final class NestedImpl {
	public static final String JAR_ENTRY = "META-INF/jarjar/automodpack-mod.jar";
	public static final String JAR_FILE_NAME = "automodpack-mod.jar";
	private static final int STREAM_BUFFER = 32 * 1024;

	private NestedImpl() {}

	/** Returns a real-file Path for {@code outerClass}'s nested impl jar, extracting it under {@code cacheDir} when missing or changed. */
	public static Path extract(Class<?> outerClass, Path cacheDir) throws IOException {
		Path outerJar = JarUtils.getJarPath(outerClass);
		Files.createDirectories(cacheDir);
		Path target = cacheDir.resolve(JAR_FILE_NAME);

		try (ZipFile zip = new ZipFile(outerJar.toFile())) {
			ZipEntry entry = zip.getEntry(JAR_ENTRY);
			if (entry == null) throw new IllegalStateException("Outer jar " + outerJar + " carries no nested impl at " + JAR_ENTRY);
			String sha1 = entrySha1(zip, entry);
			if (FileIntegrity.matches(target, entry.getSize(), sha1)) return target;

			Path temporary = cacheDir.resolve(JAR_FILE_NAME + DurableFiles.TEMPORARY_SUFFIX);
			try (InputStream input = zip.getInputStream(entry); OutputStream output = Files.newOutputStream(temporary)) {
				input.transferTo(output);
			}
			DurableFiles.replace(temporary, target);
			LOGGER.info("Extracted the nested AutoModpack impl {} ({} bytes) to {}", JAR_ENTRY, entry.getSize(), target);
			return target;
		}
	}

	private static String entrySha1(ZipFile zip, ZipEntry entry) throws IOException {
		MessageDigest digest = HashUtils.newSha1Digest();
		try (InputStream input = zip.getInputStream(entry)) {
			byte[] buffer = new byte[STREAM_BUFFER];
			int read;
			while ((read = input.read(buffer)) != -1) digest.update(buffer, 0, read);
		}
		return HexFormat.of().formatHex(digest.digest());
	}
}
