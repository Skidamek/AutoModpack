package pl.skidam.automodpack_loader_core_forge;

/**
 * Launch facts captured by {@code AutoModpackTransformationService#onLoad} before any locator can
 * scan. Pure JDK statics on purpose: {@code LazyModLocatorBase} reads these from wherever FML loads
 * it, so this holder must never drag a Forge-only type into a class-load - which reading the
 * transformation service itself could. Null until {@code onLoad} ran, which ModLauncher always does
 * before mod discovery on both forge generations (fml40 and fml47).
 */
public final class EarlyLaunchEnvironment {
	public static volatile String MC_VERSION;
	public static volatile String FORGE_VERSION;
	public static volatile Boolean IS_CLIENT;

	private EarlyLaunchEnvironment() {}
}
