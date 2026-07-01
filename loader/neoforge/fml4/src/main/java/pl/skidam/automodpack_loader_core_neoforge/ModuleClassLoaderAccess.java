package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.cl.ModuleClassLoader;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Reads the three private routing maps of a {@link ModuleClassLoader} (and its subclasses, such as
 * ModLauncher's {@code TransformingClassLoader} that backs the GAME layer) so {@link EarlyServiceLayer}
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
 * <em>is</em> reachable, so {@link EarlyServiceBridgePlugin} uses ordinary reflection there.)
 */
final class ModuleClassLoaderAccess {

    private static final Unsafe UNSAFE;
    private static final long PACKAGE_LOOKUP_OFFSET;
    private static final long PARENT_LOADERS_OFFSET;
    private static final long RESOLVED_ROOTS_OFFSET;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);
            PACKAGE_LOOKUP_OFFSET = offsetOf("packageLookup");
            PARENT_LOADERS_OFFSET = offsetOf("parentLoaders");
            RESOLVED_ROOTS_OFFSET = offsetOf("resolvedRoots");
        } catch (Throwable t) {
            throw new ExceptionInInitializerError(t);
        }
    }

    private ModuleClassLoaderAccess() {}

    private static long offsetOf(String field) throws NoSuchFieldException {
        // getDeclaredField does not require access; objectFieldOffset reads the layout, not the value.
        return UNSAFE.objectFieldOffset(ModuleClassLoader.class.getDeclaredField(field));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> packageLookup(ClassLoader loader) {
        return (Map<String, Object>) UNSAFE.getObject(loader, PACKAGE_LOOKUP_OFFSET);
    }

    @SuppressWarnings("unchecked")
    static Map<String, ClassLoader> parentLoaders(ClassLoader loader) {
        return (Map<String, ClassLoader>) UNSAFE.getObject(loader, PARENT_LOADERS_OFFSET);
    }

    /** {@code Map<moduleName, JarModuleReference>} - the value type is internal to securejarhandler, so raw. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> resolvedRoots(ClassLoader loader) {
        return (Map<String, Object>) UNSAFE.getObject(loader, RESOLVED_ROOTS_OFFSET);
    }
}
