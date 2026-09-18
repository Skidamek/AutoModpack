package pl.skidam.automodpack_core.loader;

/**
 * Which mod-loader generation this JVM is actually running. The universal outer jar carries every
 * generation's service providers, so each provider guards its entry points with its own flag here:
 * ServiceLoader instantiates every provider of an interface the running loader knows, and only the
 * matching generation may ever act (or even load the classes of the others' loader APIs).
 */
public final class GenerationProbes {
	/** Present on every ModLauncher-era loader: legacy Forge and NeoForge up to 1.21.1. */
	public static final boolean MODLAUNCHER_PRESENT = present("cpw.mods.modlauncher.api.ITransformationService");
	public static final boolean FORGE_PRESENT = present("net.minecraftforge.fml.loading.FMLLoader");
	public static final boolean NEOFORGE_PRESENT = present("net.neoforged.fml.loading.FMLLoader");
	/** Legacy Forge 1.19+ (fml47); 1.18.2 (fml40) predates the IDependencyLocator SPI. */
	public static final boolean FORGE_FML47 = FORGE_PRESENT && present("net.minecraftforge.forgespi.locating.IDependencyLocator");
	public static final boolean FORGE_FML40 = FORGE_PRESENT && !FORGE_FML47;
	/** NeoForge 1.21.x (fml4), the last ModLauncher-era generation. */
	public static final boolean NEOFORGE_FML4 = NEOFORGE_PRESENT && MODLAUNCHER_PRESENT;
	/** NeoForge 1.21.10+ (fml10/fml11), which removed ModLauncher for a flat FMLLoader classloader chain. */
	public static final boolean NEOFORGE_EARLYSERVICES = NEOFORGE_PRESENT && !MODLAUNCHER_PRESENT;

	private GenerationProbes() {}

	private static boolean present(String className) {
		try {
			Class.forName(className, false, GenerationProbes.class.getClassLoader());
			return true;
		} catch (ClassNotFoundException e) {
			return false;
		}
	}
}
