package pl.skidam.automodpack_core.utils;

import java.lang.reflect.Method;
import java.nio.file.Path;
import java.security.CodeSource;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public class JarUtils {

    public static Path getJarPath(Class<?> clazz) {
        try {
            CodeSource codeSource = clazz.getProtectionDomain().getCodeSource();
            if (codeSource == null || codeSource.getLocation() == null) {
                throw new IllegalStateException("CodeSource is null for " + clazz.getSimpleName());
            }

            Path path = Path.of(codeSource.getLocation().toURI());
            return resolvePhysicalPath(path);
        } catch (Exception e) {
            throw new RuntimeException("Failed to determine JAR path for " + clazz.getSimpleName(), e);
        }
    }

    // Reflectively extracts the physical file path from the virtual (Union)FileSystem (e.g. Neo/Forge)
    private static Path resolvePhysicalPath(Path path) {
        try {
            Object fs = path.getFileSystem();
            
            Method method = fs.getClass().getMethod("getPrimaryPath");
            Object result = method.invoke(fs);

            if (result instanceof Path) {
                return (Path) result;
            }
        } catch (NoSuchMethodException ignored) { // Method doesn't exist, likely not a virtual FS (e.g Fabric)
        } catch (Exception e) {
            LOGGER.error("Failed to resolve physical path for {}", path, e);
        }
        return path;
    }
}