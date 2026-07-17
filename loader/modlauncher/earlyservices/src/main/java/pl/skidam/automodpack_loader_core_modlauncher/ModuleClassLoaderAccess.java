package pl.skidam.automodpack_loader_core_modlauncher;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Map;

import cpw.mods.cl.ModuleClassLoader;
import sun.misc.Unsafe;

import pl.skidam.automodpack_core.Constants;

/**
 * Reads the three private routing maps of a {@link ModuleClassLoader} (and subclasses such as
 * ModLauncher's {@code TransformingClassLoader} backing the GAME layer) so each loader's own
 * {@code EarlyServiceLayer} can bridge the GAME loader to a modpack jar's child layer:
 * <ul>
 * <li>{@code packageLookup}: package -&gt; owning module, consulted first by {@code loadClass}
 * and {@code findResourceList};</li>
 * <li>{@code parentLoaders}: package -&gt; the classloader {@code loadClass} delegates to;</li>
 * <li>{@code resolvedRoots}: module name -&gt; jar reference, scanned by {@code findResourceList}
 * for <em>resources</em> (a package outside {@code packageLookup} only resolves classes via
 * {@code parentLoaders}, never resources).</li>
 * </ul>
 * A {@code parentLoaders} entry plus the child's {@code resolvedRoots} entry is exactly what a real
 * SERVICE-layer ancestor would give the GAME loader; the maps are mutable and never swapped after
 * construction, so only their contents are mutated, never the fields.
 *
 * <p>
 * {@link Unsafe} is used instead of plain reflection because {@code cpw.mods.cl.ModuleClassLoader}
 * lives in a module that does not {@code opens cpw.mods.cl} to us, so {@code setAccessible} would
 * throw. Field offsets read the object layout, not the value, so they need no {@code setAccessible}.
 * (ModLauncher's own package IS open to us, so {@link EarlyServiceBridgePlugin} uses ordinary
 * reflection there instead.)
 *
 * <p>
 * {@link #addReads(Module, Module)} exists because routing the GAME loader to a child layer makes
 * classes <em>loadable</em> but JPMS still checks module readability separately. The read edge that
 * would normally form via the SERVICE-&gt;GAME parent boundary never forms for our sibling child
 * layer, so the inner mod hits {@code IllegalAccessError}. Fixing it needs the unconditional
 * {@code jdk.internal.module.Modules.addReads} (unlike caller-sensitive {@code Module.addReads}),
 * reached through the trusted {@code IMPL_LOOKUP} - itself only readable via {@link Unsafe}.
 */
public final class ModuleClassLoaderAccess {

	private static final Unsafe UNSAFE;
	private static final long PACKAGE_LOOKUP_OFFSET;
	private static final long PARENT_LOADERS_OFFSET;
	private static final long RESOLVED_ROOTS_OFFSET;
	// jdk.internal.module.Modules.addReads(Module from, Module to); null if unavailable.
	private static final MethodHandle ADD_READS;

	static {
		try {
			Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
			theUnsafe.setAccessible(true);
			UNSAFE = (Unsafe) theUnsafe.get(null);
			PACKAGE_LOOKUP_OFFSET = offsetOf("packageLookup");
			PARENT_LOADERS_OFFSET = offsetOf("parentLoaders");
			RESOLVED_ROOTS_OFFSET = offsetOf("resolvedRoots");
			ADD_READS = resolveAddReads();
		} catch (Throwable t) {
			throw new ExceptionInInitializerError(t);
		}
	}

	private ModuleClassLoaderAccess() {}

	private static MethodHandle resolveAddReads() {
		try {
			// IMPL_LOOKUP is a full-power (TRUSTED) lookup; java.lang.invoke is not open to us, so
			// read the private static field through Unsafe rather than setAccessible.
			Field impl = MethodHandles.Lookup.class.getDeclaredField("IMPL_LOOKUP");
			MethodHandles.Lookup trusted = (MethodHandles.Lookup) UNSAFE.getObject(UNSAFE.staticFieldBase(impl), UNSAFE.staticFieldOffset(impl));
			Class<?> modules = Class.forName("jdk.internal.module.Modules");
			return trusted.findStatic(modules, "addReads", MethodType.methodType(void.class, Module.class, Module.class));
		} catch (Throwable t) {
			// Not fatal, but addReads becomes a silent no-op afterward, resurrecting the
			// IllegalAccessError this bridge exists to prevent with no log to explain why.
			Constants.LOGGER.error(
					"[AutoModpack] Could not resolve jdk.internal.module.Modules.addReads; in-place early-service mods may crash with IllegalAccessError when their inner mod accesses the outer jar's classes",
					t);
			return null;
		}
	}

	/**
	 * Makes module {@code from} read module {@code to}, unconditionally (bypassing
	 * {@code Module.addReads}' caller check). No-op if {@code from} is unnamed (already reads all),
	 * already reads {@code to}, or the internal API could not be resolved.
	 */
	public static void addReads(Module from, Module to) {
		if (ADD_READS == null || from == null || to == null || from == to || !from.isNamed() || from.canRead(to)) return;
		try {
			ADD_READS.invoke(from, to);
		} catch (Throwable ignored) {
			// Best effort: a missing read edge only risks the IllegalAccessError we set out to avoid.
		}
	}

	private static long offsetOf(String field) throws NoSuchFieldException {
		// getDeclaredField does not require access; objectFieldOffset reads the layout, not the value.
		return UNSAFE.objectFieldOffset(ModuleClassLoader.class.getDeclaredField(field));
	}

	@SuppressWarnings("unchecked")
	public static Map<String, Object> packageLookup(ClassLoader loader) {
		return (Map<String, Object>) UNSAFE.getObject(loader, PACKAGE_LOOKUP_OFFSET);
	}

	@SuppressWarnings("unchecked")
	public static Map<String, ClassLoader> parentLoaders(ClassLoader loader) {
		return (Map<String, ClassLoader>) UNSAFE.getObject(loader, PARENT_LOADERS_OFFSET);
	}

	/** {@code Map<moduleName, JarModuleReference>} - the value type is internal to securejarhandler, so raw. */
	@SuppressWarnings("unchecked")
	public static Map<String, Object> resolvedRoots(ClassLoader loader) {
		return (Map<String, Object>) UNSAFE.getObject(loader, RESOLVED_ROOTS_OFFSET);
	}

	// ---- Plain reflection on cpw.mods.modlauncher internals below: that package IS open to us
	// (unlike the sealed module the fields above cross), so ordinary setAccessible works. ----

	/** {@code cpw.mods.modlauncher.Launcher.INSTANCE}, or throws if ModLauncher isn't on the classpath. */
	public static Object launcherInstance() throws Exception {
		return Class.forName("cpw.mods.modlauncher.Launcher").getField("INSTANCE").get(null);
	}

	static Object readField(Object owner, String name) throws Exception {
		Field f = findField(owner.getClass(), name);
		f.setAccessible(true);
		return f.get(owner);
	}

	static void writeField(Object owner, String name, Object value) throws Exception {
		Field f = findField(owner.getClass(), name);
		f.setAccessible(true);
		f.set(owner, value);
	}

	private static Field findField(Class<?> type, String name) throws NoSuchFieldException {
		for (Class<?> c = type; c != null; c = c.getSuperclass()) {
			try {
				return c.getDeclaredField(name);
			} catch (NoSuchFieldException ignored) {
				// walk up
			}
		}
		throw new NoSuchFieldException(name);
	}
}
