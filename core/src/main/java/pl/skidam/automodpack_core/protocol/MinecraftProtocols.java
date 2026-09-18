package pl.skidam.automodpack_core.protocol;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Collections;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import com.google.gson.JsonParser;

import pl.skidam.automodpack_core.utils.JarUtils;

/**
 * Vanilla protocol numbers of the Minecraft versions AutoModpack ships for, sent in the holepunch
 * Handshake. The holepunch login dialect itself is version independent. The table is packed by the
 * oneJar build from stonecutter.properties.toml, which fails the build when it drifts from the
 * manifest's covered versions - so this code works in preload too, where Minecraft classes cannot
 * be loaded.
 */
public final class MinecraftProtocols {
	private static final String ENTRY = "mc-protocols.json";

	private MinecraftProtocols() {}

	public static int forVersion(String minecraftVersion) {
		Map<String, Integer> protocols = load();
		Integer protocol = protocols.get(minecraftVersion);
		if (protocol == null) {
			throw new IllegalArgumentException("Unsupported Minecraft version: " + minecraftVersion + "; shipped: " + String.join(", ", protocols.keySet()));
		}
		return protocol;
	}

	private static Map<String, Integer> load() {
		Path outerJar = JarUtils.getJarPath(MinecraftProtocols.class);
		try (ZipFile zip = new ZipFile(outerJar.toFile())) {
			ZipEntry entry = zip.getEntry(ENTRY);
			if (entry == null) throw new IllegalStateException("Outer jar " + outerJar + " carries no protocol table at " + ENTRY);
			Map<String, Integer> protocols = new TreeMap<>();
			for (var field : JsonParser.parseString(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().entrySet()) {
				protocols.put(field.getKey(), field.getValue().getAsInt());
			}
			return Collections.unmodifiableMap(protocols);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot read " + ENTRY + " from " + outerJar, e);
		}
	}
}
