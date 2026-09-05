package pl.skidam.automodpack_core;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIf;

/**
 * Tripwire for the NeoForge dedicated-server crash where registering C2S packets class-loaded {@code net.minecraft.client.Minecraft} through lambda bootstrap method descriptors.
 * Scans the compiled main-source classes of every stonecutter target under the {@code versions} directories' build outputs and asserts that no class eagerly loaded from the server-side init entrypoints ({@code init/*})
 * or the shared networking hub ({@code ModPackets}) references {@code net.minecraft.client.Minecraft}.
 * A plain byte scan of the class files is deliberate: any constant-pool entry (including lambda {@code BootstrapMethods} descriptors, which is exactly how the production crash happened) contains the raw type name, so
 * this catches eager-load positions without a full constant-pool parser.
 * The scan only runs when compiled target classes exist (a full {@code gradlew build}); environments that compile core alone, like the Windows CI job, skip it.
 */
public class ClientLeakTripwireTest {
	private static final byte[] CLIENT_MINECRAFT_REF = "net/minecraft/client/Minecraft".getBytes(StandardCharsets.UTF_8);

	private static List<Path> scannedClassFiles() throws IOException {
		Path root = Path.of("").toAbsolutePath();
		while (root != null && !Files.isDirectory(root.resolve("versions"))) root = root.getParent();
		if (root == null) return List.of();
		List<Path> candidates = new ArrayList<>();
		try (Stream<Path> targets = Files.list(root.resolve("versions"))) {
			targets.filter(Files::isDirectory).forEach(target -> collect(target, candidates));
		}
		return candidates;
	}

	private static void collect(Path target, List<Path> candidates) {
		Path initDir = target.resolve("build/classes/java/main/pl/skidam/automodpack/init");
		if (!Files.isDirectory(initDir)) return;
		try (Stream<Path> files = Files.list(initDir)) {
			files.filter(p -> p.toString().endsWith(".class")).forEach(candidates::add);
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
		Path modPackets = target.resolve("build/classes/java/main/pl/skidam/automodpack/networking/ModPackets.class");
		if (Files.isRegularFile(modPackets)) candidates.add(modPackets);
	}

	private static boolean hasCompiledTargets() throws IOException {
		return !scannedClassFiles().isEmpty();
	}

	@Test
	@EnabledIf("hasCompiledTargets")
	public void serverEntryClassesDoNotReferenceClientMinecraft() throws IOException {
		List<String> violations = new ArrayList<>();
		for (Path classFile : scannedClassFiles()) {
			byte[] bytes;
			try {
				bytes = Files.readAllBytes(classFile);
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			if (indexOf(bytes, CLIENT_MINECRAFT_REF) >= 0) violations.add(classFile.toString());
		}
		assertTrue(violations.isEmpty(), "Classes reachable from server init entrypoints reference " + "net/minecraft/client/Minecraft"
				+ " - loading them on a dedicated server crashes (move the client references behind a lazily-classloaded holder): " + violations);
	}

	private static int indexOf(byte[] haystack, byte[] needle) {
		for (int i = 0; i <= haystack.length - needle.length; i++) {
			int j = 0;
			while (j < needle.length && haystack[i + j] == needle[j]) j++;
			if (j == needle.length) return i;
		}
		return -1;
	}
}
