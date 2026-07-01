package pl.skidam.automodpack_loader_core_neoforge;

import cpw.mods.cl.ModuleClassLoader;
import sun.misc.Unsafe;

import java.lang.reflect.Field;
import java.util.Map;

/**
 * Reads the private routing maps of a {@link ModuleClassLoader} (and its subclasses, such as
 * ModLauncher's {@code TransformingClassLoader} that backs the GAME layer).
 *
 * <p>cpw's {@code ModuleClassLoader} resolves a class through three plain {@link java.util.HashMap}s:
 * <ul>
 *   <li>{@code packageLookup}: package -&gt; the module on <em>this</em> loader that owns it;</li>
 *   <li>{@code resolvedRoots}: module name -&gt; its jar reference (also scanned for resources);</li>
 *   <li>{@code parentLoaders}: package -&gt; the classloader to delegate to for that package.</li>
 * </ul>
 * They are mutable and never swapped after construction, so {@link EarlyServiceLayer} can add
 * entries to bridge the GAME loader to a modpack jar's child layer - the same package-to-loader
 * delegation FML itself sets up for a real SERVICE-layer mod. The fields are private and live in a
 * module that is not open to us, so we read them through {@link Unsafe} field offsets (which need no
 * {@code setAccessible} on the cpw class itself); we only mutate the {@code HashMap} contents, never
 * the fields, so there is no final-field visibility concern.
 */
final class ModuleClassLoaderAccess {

    private static final Unsafe UNSAFE;
    private static final long PACKAGE_LOOKUP_OFFSET;
    private static final long RESOLVED_ROOTS_OFFSET;
    private static final long PARENT_LOADERS_OFFSET;

    static {
        try {
            Field theUnsafe = Unsafe.class.getDeclaredField("theUnsafe");
            theUnsafe.setAccessible(true);
            UNSAFE = (Unsafe) theUnsafe.get(null);
            PACKAGE_LOOKUP_OFFSET = offsetOf("packageLookup");
            RESOLVED_ROOTS_OFFSET = offsetOf("resolvedRoots");
            PARENT_LOADERS_OFFSET = offsetOf("parentLoaders");
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
    static Map<String, Object> resolvedRoots(ClassLoader loader) {
        return (Map<String, Object>) UNSAFE.getObject(loader, RESOLVED_ROOTS_OFFSET);
    }

    @SuppressWarnings("unchecked")
    static Map<String, ClassLoader> parentLoaders(ClassLoader loader) {
        return (Map<String, ClassLoader>) UNSAFE.getObject(loader, PARENT_LOADERS_OFFSET);
    }

    /** Reads a static field via Unsafe (no {@code setAccessible}, so it works across closed modules). */
    static Object readStatic(Class<?> owner, String fieldName) throws NoSuchFieldException {
        Field f = owner.getDeclaredField(fieldName);
        return UNSAFE.getObject(UNSAFE.staticFieldBase(f), UNSAFE.staticFieldOffset(f));
    }

    /** Reads an instance field (searching up the hierarchy) via Unsafe. */
    static Object read(Object instance, String fieldName) throws NoSuchFieldException {
        return UNSAFE.getObject(instance, UNSAFE.objectFieldOffset(findField(instance.getClass(), fieldName)));
    }

    /** Writes an instance field (searching up the hierarchy) via Unsafe; works on {@code final} too. */
    static void write(Object instance, String fieldName, Object value) throws NoSuchFieldException {
        UNSAFE.putObject(instance, UNSAFE.objectFieldOffset(findField(instance.getClass(), fieldName)), value);
    }

    private static Field findField(Class<?> from, String name) throws NoSuchFieldException {
        for (Class<?> c = from; c != null; c = c.getSuperclass()) {
            try {
                return c.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // try superclass
            }
        }
        throw new NoSuchFieldException(name);
    }
}
