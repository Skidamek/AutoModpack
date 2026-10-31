package pl.skidam.automodpack_core.protocol.trust;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/**
 * The boundary may resolve only the JDK, its own package and the shaded libraries. A game, loader or mod class in its
 * constant pool would put the game's class loading back on the trust path, which is what the boundary exists to stop.
 * The scan reads the class files as bytes and looks for the package names the constant pool stores verbatim.
 */
class TrustBoundaryIsolationTest {
	private static final String OWN = "pl/skidam/automodpack_core/protocol/trust/";
	private static final String MOD_PREFIX = "pl/skidam/";
	private static final List<String> FORBIDDEN = List.of("net/minecraft", "net/fabricmc", "cpw/mods", "net/neoforged", "net/minecraftforge", "com/google/gson", "org/slf4j",
			"org/apache/logging", "amp_libs", "org/bouncycastle");

	@Test
	void everyBoundaryClassResolvesOnlyTheJdkItsOwnPackageAndShadedLibraries() throws Exception {
		Path classes = Path.of(TrustBoundary.class.getProtectionDomain().getCodeSource().getLocation().toURI()).resolve(OWN);
		List<Path> files;
		try (Stream<Path> entries = Files.list(classes)) {
			files = entries.filter(path -> path.getFileName().toString().endsWith(".class")).toList();
		}
		assertFalse(files.isEmpty(), "no boundary classes under " + classes);

		List<String> violations = new ArrayList<>();
		for (Path file : files) {
			String text = new String(Files.readAllBytes(file), StandardCharsets.ISO_8859_1);
			for (String forbidden : FORBIDDEN) {
				if (text.contains(forbidden)) violations.add(file.getFileName() + " references " + forbidden);
			}
			for (int at = text.indexOf(MOD_PREFIX); at >= 0; at = text.indexOf(MOD_PREFIX, at + 1)) {
				if (!text.startsWith(OWN, at)) violations.add(file.getFileName() + " references " + text.substring(at, Math.min(text.length(), at + 80)));
			}
		}
		assertTrue(violations.isEmpty(), String.join("\n", violations));
	}
}
