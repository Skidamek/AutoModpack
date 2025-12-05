package pl.skidam.automodpack_core.utils;

import java.nio.file.FileSystem;
import java.nio.file.FileSystems;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility for detecting capabilities of a FileSystem (Case sensitivity, etc.).
 * <p>
 * This class uses a lazy-loading cache mechanism to ensure detection logic
 * runs only once per FileSystem instance.
 */
public final class FileSystemCapabilities {

    // Thread-safe cache. Weak keys could be used if FileSystems are created/destroyed rapidly,
    // but for 99% of apps, FS instances are long-lived.
    private static final Map<FileSystem, Boolean> SENSITIVITY_CACHE = new ConcurrentHashMap<>();

    private FileSystemCapabilities() {
        // Prevent instantiation
    }

    /**
     * Checks if the default FileSystem is case-insensitive.
     */
    public static boolean isCaseInsensitive() {
        return isCaseInsensitive(FileSystems.getDefault());
    }

    /**
     * Checks if the provided FileSystem is case-insensitive.
     * <p>
     * Logic:
     * This avoids unreliable OS string checks (e.g. "os.name").
     * Instead, it asks the FileSystemProvider directly by comparing two Path objects
     * ('A' and 'a'). If the FS provider considers them equal, it is case-insensitive.
     *
     * @param fs The FileSystem to check.
     * @return true if 'A' == 'a' on this system.
     */
    public static boolean isCaseInsensitive(FileSystem fs) {
        if (fs == null) return false;
        return SENSITIVITY_CACHE.computeIfAbsent(fs, FileSystemCapabilities::probeSensitivity);
    }

    /**
     * The actual probing logic.
     * Does not perform I/O. Strictly in-memory Path comparison.
     */
    private static boolean probeSensitivity(FileSystem fs) {
        try {
            // We use generic characters that are valid on all known filesystems.
            Path p1 = fs.getPath("A");
            Path p2 = fs.getPath("a");

            // contract of Path.equals():
            // "Whether or not two path objects are equal depends on the file system implementation.
            // In some cases the paths are compared without regard to case."
            if (p1.equals(p2)) {
                return true;
            }

            // Paranoia check: Some implementations might rely on hashCode for fast lookup
            // but implement equals strictly.
            if (p1.hashCode() == p2.hashCode()) {
                // If hashes match but equals doesn't, we assume insensitive to be safe,
                // or it's a very widespread collision.
                // However, usually equals() is the source of truth.
                return true;
            }

            return false;
        } catch (Exception e) {
            // Fallback for weird FileSystems that might reject single-character paths
            // Defaulting to false (Case Sensitive) is the safer bet for security.
            return false;
        }
    }
}