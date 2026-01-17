package pl.skidam.automodpack_core.utils;

import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;

import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;

import static pl.skidam.automodpack_core.GlobalVariables.*;

public class ClientCacheUtils {

    // TODO change this dummy byte array to contain also some metadata that we have created it
    private static final byte[] smallDummyJar = {
            80, 75, 3, 4, 20, 0, 8, 8, 8, 0, 89, 116, -44, 86, 0, 0, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 20, 0, 4, 0, 77, 69, 84,
            65, 45, 73, 78, 70, 47, 77, 65, 78, 73, 70, 69, 83, 84, 46, 77,
            70, -2, -54, 0, 0, -13, 77, -52, -53, 76, 75, 45, 46, -47, 13, 75,
            45, 42, -50, -52, -49, -77, 82, 48, -44, 51, -32, -27, -30, -27, 2, 0,
            80, 75, 7, 8, -78, 127, 2, -18, 27, 0, 0, 0, 25, 0, 0, 0,
            80, 75, 1, 2, 20, 0, 20, 0, 8, 8, 8, 0, 89, 116, -44, 86,
            -78, 127, 2, -18, 27, 0, 0, 0, 25, 0, 0, 0, 20, 0, 4, 0,
            0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 77, 69,
            84, 65, 45, 73, 78, 70, 47, 77, 65, 78, 73, 70, 69, 83, 84, 46,
            77, 70, -2, -54, 0, 0, 80, 75, 5, 6, 0, 0, 0, 0, 1, 0,
            1, 0, 70, 0, 0, 0, 97, 0, 0, 0, 0, 0,
    };
    private static final Jsons.ClientDummyFiles cacheDummyFiles = ConfigTools.load(clientDummyFilesFile, Jsons.ClientDummyFiles.class);
    private static final Jsons.LocalMetadata clientMetadataCache = ConfigTools.load(clientLocalMetadataFile, Jsons.LocalMetadata.class);
    private static final Jsons.ClientDeletedNonModpackFilesTimestamps clientDeletedNonModpackFilesTimestamps = ConfigTools.load(clientDeletionTimeStamps, Jsons.ClientDeletedNonModpackFilesTimestamps.class);

    // Metadata

    // Cheap cache check - 0 bytes read
    public static String getVerifiedCacheHash(Path path) {
        if (clientMetadataCache == null) return null;

        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            Jsons.LocalMetadata.FileFingerprint fingerprint = clientMetadataCache.files.get(path.toAbsolutePath().normalize().toString());

            if (fingerprint != null && fingerprint.lastSize == attrs.size() && fingerprint.lastModified == attrs.lastModifiedTime().toMillis()) {
                return fingerprint.sha1;
            }
        } catch (IOException e) {
            LOGGER.debug("Could not read attributes for {}", path);
        }
        return null; // Metadata mismatch or missing cache
    }

    public static void updateMetadataCache(Path path, String hash) {
        if (clientMetadataCache == null) return;
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            clientMetadataCache.files.put(path.toAbsolutePath().normalize().toString(), new Jsons.LocalMetadata.FileFingerprint(hash, attrs.size(), attrs.lastModifiedTime().toMillis()));
        } catch (IOException e) {
            LOGGER.error("Could not update cache for {}", path, e);
        }
    }

    public static String computeHashIfNeeded(Path modPath) {
        // Check cache first
        String cachedHash = getVerifiedCacheHash(modPath);
        if (cachedHash != null) return cachedHash;

        // Fallback to hashing and update cache
        String diskHash = CustomFileUtils.getHash(modPath);
        updateMetadataCache(modPath, diskHash);
        return diskHash;
    }

    public static boolean fastHashCompare(Path file1, Path file2) {
        if (!Files.exists(file1) || !Files.exists(file2)) return false;

        String hash1 = computeHashIfNeeded(file1);
        String hash2 = computeHashIfNeeded(file2);

        if (hash1 == null || hash2 == null) return false;

        return hash1.equals(hash2);
    }

    public static void updateCache(Path path, String hash) {
        if (clientMetadataCache == null) return;
        try {
            BasicFileAttributes attrs = Files.readAttributes(path, BasicFileAttributes.class);
            clientMetadataCache.files.put(path.toAbsolutePath().normalize().toString(), new Jsons.LocalMetadata.FileFingerprint(hash, attrs.size(), attrs.lastModifiedTime().toMillis()));
        } catch (IOException e) {
            LOGGER.error("Could not update cache for {}", path, e);
        }
    }

    public static void saveMetadataCache() {
        if (clientMetadataCache == null) {
            return;
        }
        ConfigTools.save(clientLocalMetadataFile, clientMetadataCache);
    }

    // Dummy

    public static void deleteDummyFiles() {
        if (cacheDummyFiles == null || cacheDummyFiles.files.isEmpty()) {
            return;
        }

        var iterator = cacheDummyFiles.files.iterator();
        while (iterator.hasNext()) {
            try {
                String filePath = iterator.next();
                Path file = Path.of(filePath);
                if (CustomFileUtils.compareFilesByteByByte(file, smallDummyJar)) {
                    CustomFileUtils.executeOrder66(file, false);
                }
                iterator.remove();
            } catch (Exception e) {
                LOGGER.error("Failed to delete dummy file", e);
            }
        }

        saveDummyFiles();
    }

    // our trick for not able to just delete file on specific filesystem (windows...)
    public static void dummyIT(Path file) {
        try (FileOutputStream fos = new FileOutputStream(file.toFile())) {
            fos.write(smallDummyJar);
            fos.flush();

            if (cacheDummyFiles == null) {
                throw new IllegalStateException("clientDummyFiles is null");
            }

            cacheDummyFiles.files.add(file.toAbsolutePath().normalize().toString());
        } catch (IOException e) {
            LOGGER.error("Failed to create dummy file: {}", file, e);
        }
    }

    public static void saveDummyFiles() {
        if (cacheDummyFiles == null) {
            return;
        }
        ConfigTools.save(clientDummyFilesFile, cacheDummyFiles);
    }

    public static boolean wasThisTimestampEvaluatedBefore(String timestamp) {
        if (clientDeletedNonModpackFilesTimestamps == null) return false;
        return clientDeletedNonModpackFilesTimestamps.timestamps.contains(timestamp);
    }

    public static void markTimestampAsEvaluated(String timestamp) {
        if (clientDeletedNonModpackFilesTimestamps == null) return;
        clientDeletedNonModpackFilesTimestamps.timestamps.add(timestamp);
    }

    public static void saveDeletedFilesTimestamps() {
        if (clientDeletedNonModpackFilesTimestamps == null) {
            return;
        }
        ConfigTools.save(clientDeletionTimeStamps, clientDeletedNonModpackFilesTimestamps);
    }
}
