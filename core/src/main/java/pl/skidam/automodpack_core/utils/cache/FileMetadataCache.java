package pl.skidam.automodpack_core.utils.cache;

import org.h2.mvstore.MVMap;
import org.h2.mvstore.MVStore;
import pl.skidam.automodpack_core.utils.SmartFileUtils;

import java.io.IOException;
import java.io.Serializable;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static pl.skidam.automodpack_core.Constants.LOGGER;

public class FileMetadataCache implements AutoCloseable {

    private static final Map<Path, FileMetadataCache> INSTANCES = new HashMap<>();
    private final AtomicInteger refCount = new AtomicInteger(1);
    private final Path dbPath;

    public static synchronized FileMetadataCache open(Path path) {
        Path absPath = path.toAbsolutePath().normalize();
        FileMetadataCache existing = INSTANCES.get(absPath);

        if (existing != null) {
            existing.refCount.incrementAndGet();
            return existing;
        }

        FileMetadataCache newCache = new FileMetadataCache(absPath);
        INSTANCES.put(absPath, newCache);
        return newCache;
    }


    private final MVStore store;
    private final MVMap<String, CachedFile> fileMetadataMap;
    private final AtomicInteger uncommittedWrites = new AtomicInteger(0);
    private static final int COMMIT_THRESHOLD = 50;
    private final Object[] locks = new Object[64];

    public record CachedFile(
            String contentHash,
            long lastModified,
            long size,
            String fileKey
    ) implements Serializable {
        @java.io.Serial
        private static final long serialVersionUID = 1L;
    }

    private FileMetadataCache(Path dbPath) {
        this.dbPath = dbPath;
        this.store = new MVStore.Builder()
                .fileName(dbPath.toString())
                .cacheSize(20)
                .autoCommitDisabled()
                .open();

        this.fileMetadataMap = store.openMap("file_metadata");

        for (int i = 0; i < locks.length; i++) {
            locks[i] = new Object();
        }
    }

    public String getOrComputeHash(Path file) throws IOException {
        Path absPath = file.toAbsolutePath().normalize();
        String pathKey = absPath.toString();

        BasicFileAttributes attrs = Files.readAttributes(absPath, BasicFileAttributes.class);
        long currentSize = attrs.size();
        long currentTime = attrs.lastModifiedTime().toMillis();
        String currentFileKey = attrs.fileKey() != null ? attrs.fileKey().toString() : "null";

        CachedFile cached = fileMetadataMap.get(pathKey);
        if (isCacheValid(cached, currentSize, currentTime, currentFileKey)) {
            return cached.contentHash(); // CACHE HIT
        }

        // Calculate which lock bucket to use
        int lockIndex = Math.abs(pathKey.hashCode() % locks.length);

        synchronized (locks[lockIndex]) {
            // Check if another thread has already updated the cache
            cached = fileMetadataMap.get(pathKey);
            if (isCacheValid(cached, currentSize, currentTime, currentFileKey)) {
                return cached.contentHash();
            }

            // Actual work happens here
            String newHash = SmartFileUtils.getHash(absPath);

            CachedFile newRecord = new CachedFile(newHash, currentTime, currentSize, currentFileKey);
            fileMetadataMap.put(pathKey, newRecord);
            checkAndCommit();

            return newHash;
        }
    }

    private boolean isCacheValid(CachedFile cached, long size, long time, String key) {
        if (cached == null) return false;
        return cached.size() == size &&
                cached.lastModified() == time &&
                cached.fileKey().equals(key);
    }

    public String getHashOrNull(Path path) {
        try {
            return getOrComputeHash(path);
        } catch (IOException e) {
            LOGGER.error("Failed to compute hash for path: {}", path, e);
            return null;
        }
    }

    public boolean fastHashCompare(Path file1, Path file2) throws IOException {
        if (!Files.exists(file1) || !Files.exists(file2)) return false;

        String hash1 = getOrComputeHash(file1);
        String hash2 = getOrComputeHash(file2);

        if (hash1 == null || hash2 == null) return false;

        return hash1.equals(hash2);
    }

    // Use only if you are SURE of the file state!
    public void overwriteCache(Path file, String hash) throws IOException {
        Path absPath = file.toAbsolutePath().normalize();
        String pathKey = absPath.toString();

        BasicFileAttributes attrs = Files.readAttributes(absPath, BasicFileAttributes.class);
        long currentSize = attrs.size();
        long currentTime = attrs.lastModifiedTime().toMillis();
        String currentFileKey = attrs.fileKey() != null ? attrs.fileKey().toString() : "null";

        CachedFile newRecord = new CachedFile(hash, currentTime, currentSize, currentFileKey);
        fileMetadataMap.put(pathKey, newRecord);
        checkAndCommit();
    }

    private void checkAndCommit() {
        if (uncommittedWrites.incrementAndGet() >= COMMIT_THRESHOLD) {
            uncommittedWrites.set(0);
            store.commit();
        }
    }

    @Override
    public void close() {
        synchronized (FileMetadataCache.class) {
            if (refCount.decrementAndGet() <= 0) {
                try {
                    if (!store.isClosed()) {
                        store.commit();
                        store.close();
                    }
                } finally {
                    INSTANCES.remove(this.dbPath, this);
                }
            }
        }
    }
}