package pl.skidam.automodpack_loader_core_fabric_16.mods;

import java.io.InputStream;
import java.lang.reflect.Method;
import java.nio.file.Path;
import java.util.Collection;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import net.fabricmc.loader.impl.FabricLoaderImpl;
import net.fabricmc.loader.impl.discovery.ModCandidateImpl;
import net.fabricmc.loader.impl.metadata.DependencyOverrides;
import net.fabricmc.loader.impl.metadata.LoaderModMetadata;
import net.fabricmc.loader.impl.metadata.ModMetadataParser;
import net.fabricmc.loader.impl.metadata.VersionOverrides;

/**
 * Mounts the outer's extracted impl jar as a late mod candidate on fabric-loader 0.16.x - the same
 * window the modpack projection goes through, so the impl's mixins, access widener and entrypoints
 * are all picked up by the loader's own passes (access wideners and mixins bootstrap after
 * {@code FabricLoaderImpl#load()} finishes, entrypoints after that).
 */
public class ImplMount16 {

	public static void mount(Path implJar) throws Exception {
		ModCandidateImpl candidate = createPlain(implJar);
		new ModpackLoader16().addMod(candidate);
	}

	/** {@code ModCandidateImpl.createPlain} is package-private, so this reaches it reflectively. */
	private static ModCandidateImpl createPlain(Path implJar) throws Exception {
		Method createPlain = ModCandidateImpl.class.getDeclaredMethod("createPlain", List.class, LoaderModMetadata.class, boolean.class, Collection.class);
		createPlain.setAccessible(true);
		return (ModCandidateImpl) createPlain.invoke(null, List.of(implJar), parseMetadata(implJar), false, List.of());
	}

	private static LoaderModMetadata parseMetadata(Path implJar) throws Exception {
		try (ZipFile zip = new ZipFile(implJar.toFile())) {
			ZipEntry entry = zip.getEntry("fabric.mod.json");
			if (entry == null) throw new IllegalStateException(implJar + " carries no fabric.mod.json");
			try (InputStream input = zip.getInputStream(entry)) {
				return ModMetadataParser.parseMetadata(input, implJar.toString(), List.of(), new VersionOverrides(),
						new DependencyOverrides(FabricLoaderImpl.INSTANCE.getConfigDir()), FabricLoaderImpl.INSTANCE.isDevelopmentEnvironment());
			}
		}
	}
}
