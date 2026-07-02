package pl.skidam.automodpack_loader_core_modlauncher;

import cpw.mods.cl.ModuleClassLoader;
import sun.misc.Unsafe;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.util.Map;

/**
 * Reads the three private routing maps of a {@link ModuleClassLoader} (and its subclasses, such as
 * ModLauncher's {@code TransformingClassLoader} that backs the GAME layer) so each loader's own
 * {@code EarlyServiceLayer} (NeoForge fml4 and Forge each have their own, package-private to them)
 * can bridge the GAME loader to a modpack jar's child layer:
 * <ul>
 *   <li>{@code packageLookup}: package -&gt; the module on <em>this</em> loader that owns it
 *       (consulted first by both {@code loadClass} and {@code findResourceList});</li>
 *   <li>{@code parentLoaders}: package -&gt; the classloader {@code loadClass} delegates to;</li>
 *   <li>{@code resolvedRoots}: module name -&gt; its jar reference, scanned by
 *       {@code findResourceList} for <em>resources</em> (a package not in {@code packageLookup} is
 *       NOT delegated to {@code parentLoaders} for resources - only classes).</li>
 * </ul>
 * Adding a {@code parentLoaders} entry (classes) plus the child's {@code resolvedRoots} (resources)
 * is exactly what a real SERVICE-layer ancestor gives the GAME loader; the maps are mutable and never
 * swapped after construction, so we only mutate their contents, never the fields.
 *
 * <p>Why {@link Unsafe} and not plain reflection: {@code cpw.mods.cl.ModuleClassLoader} lives in the
 * {@code cpw.mods.securejarhandler} module, which does <em>not</em> {@code opens cpw.mods.cl} to us -
 * {@code Field.setAccessible} throws {@code InaccessibleObjectException}. Unsafe field offsets read the
 * object layout, not the field value, so they need no {@code setAccessible} and cross the sealed
 * module boundary. (The launch-plugin injection touches {@code cpw.mods.modlauncher} instead, which
 * <em>is</em> reachable, so {@link EarlyServiceBridgePlugin} in this package uses ordinary reflection there.)
 *
 * <p>It also carries {@link #addReads(Module, Module)}: routing the GAME loader to a child layer
 * makes the outer classes <em>loadable</em>, but JPMS still enforces module readability at access
 * time. Natively the inner mod's module reads the outer module because loader resolution links them
 * across the SERVICE-&gt;GAME parent boundary; our child layer is only a sibling of GAME, so that
 * edge never forms and the inner mod hits {@code IllegalAccessError: module X does not read module
 * Y}. Adding the read edge needs the unconditional {@code jdk.internal.module.Modules.addReads}
 * ({@code java.lang.Module.addReads} is caller-sensitive and only self-adds), reached through the
 * trusted {@code MethodHandles.Lookup.IMPL_LOOKUP} - itself only readable via {@link Unsafe}, since
 * {@code java.lang.invoke} is not open to us. A read edge only grants access, so adding them broadly
 * cannot break anything; it only prevents the {@code IllegalAccessError}.
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
            MethodHandles.Lookup trusted = (MethodHandles.Lookup) UNSAFE.getObject(
                    UNSAFE.staticFieldBase(impl), UNSAFE.staticFieldOffset(impl));
            Class<?> modules = Class.forName("jdk.internal.module.Modules");
            return trusted.findStatic(modules, "addReads",
                    MethodType.methodType(void.class, Module.class, Module.class));
        } catch (Throwable t) {
            // Not fatal by itself, but every read edge this class is asked to add will now silently
            // no-op (see addReads) - which resurrects the exact IllegalAccessError this bridge exists
            // to prevent, with nothing in the log to explain why. Surface it once, loudly, here instead.
            pl.skidam.automodpack_core.Constants.LOGGER.error(
                    "[AutoModpack] Could not resolve jdk.internal.module.Modules.addReads; in-place early-service mods may crash with IllegalAccessError when their inner mod accesses the outer jar's classes", t);
            return null;
        }
    }

    /**
     * Makes module {@code from} read module {@code to}, unconditionally (bypassing
     * {@code Module.addReads}' caller check). No-op if {@code from} is unnamed (already reads all),
     * already reads {@code to}, or the internal API could not be resolved.
     */
    public static void addReads(Module from, Module to) {
        if (ADD_READS == null || from == null || to == null || from == to
                || !from.isNamed() || from.canRead(to)) {
            return;
        }
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

    // ---- Plain reflection on cpw.mods.modlauncher internals: that package IS opened/reachable to
    // us (unlike the sealed cpw.mods.cl/securejarhandler module the fields above cross), so ordinary
    // Field#setAccessible reaches it without Unsafe. Shared here so EarlyServiceLayer and
    // EarlyServiceBridgePlugin - both of which reach into ModLauncher's Launcher/handler internals -
    // use one implementation instead of two copies. ----

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
