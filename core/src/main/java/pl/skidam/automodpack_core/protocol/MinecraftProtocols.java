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
 * oneJar build from stonecutter.properties.toml as the verbatim mirror of the manifest's covers -
 * one key per cover, a tilde key declaring that its number answers the whole patch line, which is
 * stripped on read so a covered patch release walks up its dotted prefix onto it ({@code 26.1.3}
 * rides {@code ~26.1}'s number). Exact covers and tilde covers share one keyspace after the strip,
 * but that stays exact: a version no cover names crashes in {@code ImplManifest.entryFor} before
 * reaching this class, so only tilde-covered patch releases ever walk. The build fails when the
 * table drifts from the covers in either direction - so this code works in preload too, where
 * Minecraft classes cannot be loaded.
 */
public final class MinecraftProtocols {
	private static final String ENTRY = "mc-protocols.json";
	private static volatile Map<String, Integer> table;

	private MinecraftProtocols() {}

	public static int forVersion(String minecraftVersion) {
		Map<String, Integer> protocols = load();
		String version = minecraftVersion;
		int suffix = version.indexOf('-');
		if (suffix >= 0) version = version.substring(0, suffix);
		Integer protocol = protocols.get(version);
		while (protocol == null && version.indexOf('.') >= 0) {
			version = version.substring(0, version.lastIndexOf('.'));
			protocol = protocols.get(version);
		}
		if (protocol == null) {
			throw new IllegalArgumentException("Unsupported Minecraft version: " + minecraftVersion + "; shipped: " + String.join(", ", protocols.keySet()));
		}
		return protocol;
	}

	private static Map<String, Integer> load() {
		Map<String, Integer> cached = table;
		if (cached != null) return cached;
		synchronized (MinecraftProtocols.class) {
			if (table == null) table = readFromJar();
			return table;
		}
	}

	/** A failed read stays uncached so a retried handshake can succeed once the jar is readable. */
	private static Map<String, Integer> readFromJar() {
		Path outerJar = JarUtils.getJarPath(MinecraftProtocols.class);
		try (ZipFile zip = new ZipFile(outerJar.toFile())) {
			ZipEntry entry = zip.getEntry(ENTRY);
			if (entry == null) throw new IllegalStateException("Outer jar " + outerJar + " carries no protocol table at " + ENTRY);
			Map<String, Integer> protocols = new TreeMap<>();
			for (var field : JsonParser.parseString(new String(zip.getInputStream(entry).readAllBytes(), StandardCharsets.UTF_8)).getAsJsonObject().entrySet()) {
				String version = field.getKey();
				protocols.put(version.startsWith("~") ? version.substring(1) : version, field.getValue().getAsInt());
			}
			return Collections.unmodifiableMap(protocols);
		} catch (IOException e) {
			throw new IllegalStateException("Cannot read " + ENTRY + " from " + outerJar, e);
		}
	}
}
