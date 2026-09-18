package pl.skidam.automodpack_loader_core_neoforge_4.mods;

import java.nio.file.Path;

import cpw.mods.jarhandling.JarContents;
import cpw.mods.jarhandling.SecureJar;
import net.neoforged.fml.loading.moddiscovery.ModFileParser;
import net.neoforged.fml.loading.moddiscovery.ModJarMetadata;
import net.neoforged.neoforgespi.locating.IModFile;

/**
 * Creates the impl mod file the ModLauncher-era NeoForge discovery pipeline can load, mirroring the
 * native reader flow ({@code ModJarMetadata} + parsed {@code neoforge.mods.toml}): the metadata names
 * the module the way FML's own layer build and {@code FMLModContainer} expect - a plain
 * {@code SecureJar.from(path)} names the module after the file and the game layer then cannot find it.
 * The toml parse is what makes the file contribute its {@code automodpack_mod} entry at all; the
 * manifest-based reader reports zero mods and FML silently drops the file.
 *
 * <p>
 * Lives in its own class so {@link pl.skidam.automodpack_loader_core_neoforge_4.LazyModLocator} stays
 * free of securejarhandler bytecode: the universal outer jar registers both generations' locators
 * under the same services, ServiceLoader links every provider it instantiates, and securejarhandler
 * classes do not exist on the flat-classloader generation - linking them there would crash the launch.
 * This class is only linked when the fml4 generation actually mounts the impl.
 */
public final class ImplMount {

	private ImplMount() {}

	/** The impl as an {@link IModFile} parsed from its {@code neoforge.mods.toml}. */
	public static IModFile createModFile(Path implJar) {
		JarContents contents = JarContents.of(implJar);
		ModJarMetadata metadata = new ModJarMetadata(contents);
		SecureJar secureJar = SecureJar.from(contents, metadata);
		IModFile modFile = IModFile.create(secureJar, ModFileParser::modsTomlParser);
		metadata.setModFile(modFile);
		return modFile;
	}
}
