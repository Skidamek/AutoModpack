package pl.skidam.automodpack_core.auth;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.ConfigTools;
import pl.skidam.automodpack_core.config.Jsons;
import pl.skidam.automodpack_core.security.SharedSecurityFileLock;
import pl.skidam.automodpack_core.security.SharedSecurityPaths;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Crash-safe, multi-process shared secret persistence.
 *
 * Raw secrets never enter the JSON file. The map key is a URL-safe SHA-256
 * digest of the decoded secret bytes.
 */
public final class SharedSecretsStore {
    private static final int FORMAT_VERSION = 1;
    private static final Pattern BACKUP_PATTERN = Pattern.compile("(.+)\\.backup-(\\d{20})\\.json");

    private static final class StoreValidationException extends IOException {
        private StoreValidationException(String message) {
            super(message);
        }

        private StoreValidationException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final SharedSecurityPaths paths;

    public SharedSecretsStore(SharedSecurityPaths paths) {
        this.paths = Objects.requireNonNull(paths, "paths");
        if (!paths.enabled()) {
            throw new IllegalArgumentException("SharedSecretsStore requires enabled shared security");
        }
    }

    public void save(String playerUuid, Secrets.Secret secret, long secretLifetimeHours) throws Exception {
        if (playerUuid == null || playerUuid.isBlank() || secret == null || secret.secret() == null || secret.secret().isBlank()) {
            throw new IllegalArgumentException("Player UUID and secret are required");
        }
        UUID.fromString(playerUuid);
        String hash = hashSecret(secret.secret());
        if (hash == null) {
            throw new IllegalArgumentException("Secret is not a valid URL-safe base64 value");
        }
        long issuedAt = requireNonNegative(secret.timestamp(), "secret timestamp");
        long lifetimeSeconds;
        try {
            lifetimeSeconds = Math.multiplyExact(Math.max(0, secretLifetimeHours), 3600L);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException("Secret lifetime is too large", exception);
        }
        long computedExpiresAt;
        try {
            computedExpiresAt = Math.addExact(issuedAt, lifetimeSeconds);
        } catch (ArithmeticException exception) {
            computedExpiresAt = Long.MAX_VALUE;
        }
        final long expiresAt = computedExpiresAt;

        SharedSecurityFileLock.withExclusiveLock(paths.secretsLockFile(), paths.lockTimeoutMs(), () -> {
            paths.ensureDirectory();
            cleanupTemporaryFiles();
            Jsons.SharedSecretsFields current = loadOrRecoverLocked();
            long now = epochSeconds();
            pruneExpired(current, now);
            current.entries.entrySet().removeIf(entry ->
                    playerUuid.equals(entry.getValue().playerUuid)
                            && paths.nodeId().equals(entry.getValue().issuerNodeId));
            current.entries.put(hash, new Jsons.SharedSecretEntry(playerUuid, paths.nodeId(), issuedAt, expiresAt));
            limitEntries(current);
            current.generation = incrementGeneration(current.generation);
            persistLocked(current);
            return null;
        });
    }

    public HostSecretRecord find(String secret) throws Exception {
        String hash = hashSecret(secret);
        if (hash == null) {
            return null;
        }

        return SharedSecurityFileLock.withExclusiveLock(paths.secretsLockFile(), paths.lockTimeoutMs(), () -> {
            paths.ensureDirectory();
            cleanupTemporaryFiles();
            Jsons.SharedSecretsFields current = loadOrRecoverLocked();
            Jsons.SharedSecretEntry entry = current.entries.get(hash);
            if (entry == null || entry.expiresAt <= epochSeconds()) {
                return null;
            }
            return new HostSecretRecord(secret, entry.playerUuid, entry.issuerNodeId, entry.issuedAt, entry.expiresAt, current.generation);
        });
    }

    public static String hashSecret(String secret) {
        if (secret == null || secret.isBlank()) {
            return null;
        }
        final byte[] decoded;
        try {
            decoded = Base64.getUrlDecoder().decode(secret);
        } catch (IllegalArgumentException exception) {
            return null;
        }
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(decoded);
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private Jsons.SharedSecretsFields loadOrRecoverLocked() throws Exception {
        Path primary = paths.secretsFile();
        rejectSymlink(primary);
        if (!Files.exists(primary)) {
            Optional<Backup> backup = findNewestValidBackup();
            if (backup.isPresent()) {
                restoreBackupLocked(backup.get());
                return readAndValidate(primary);
            }
            if (hasCorruptEvidence()) {
                throw new IOException("Shared secret store primary is missing after a corruption event; refusing to create an empty store: "
                        + primary.toAbsolutePath().normalize());
            }
            Jsons.SharedSecretsFields empty = emptyDocument();
            persistLocked(empty);
            return empty;
        }

        try {
            return readAndValidate(primary);
        } catch (StoreValidationException primaryFailure) {
            preserveCorruptPrimary(primary);
            Optional<Backup> backup = findNewestValidBackup();
            if (backup.isEmpty()) {
                throw new IOException("Shared secret store is invalid and has no valid backup: " + primary.toAbsolutePath().normalize(), primaryFailure);
            }
            restoreBackupLocked(backup.get());
            return readAndValidate(primary);
        } catch (IOException primaryFailure) {
            throw new IOException("Shared secret store could not be read; preserving the primary file: "
                    + primary.toAbsolutePath().normalize(), primaryFailure);
        }
    }

    private Jsons.SharedSecretsFields emptyDocument() {
        Jsons.SharedSecretsFields document = new Jsons.SharedSecretsFields();
        document.formatVersion = FORMAT_VERSION;
        document.generation = 0;
        document.entries = new HashMap<>();
        document.checksum = checksumFor(document);
        return document;
    }

    private Jsons.SharedSecretsFields readAndValidate(Path file) throws IOException {
        String json = Files.readString(file, StandardCharsets.UTF_8);
        JsonElement parsed;
        try {
            parsed = new JsonParser().parse(json);
        } catch (RuntimeException exception) {
            throw new StoreValidationException("Shared secret store is not valid JSON", exception);
        }
        if (parsed == null || !parsed.isJsonObject()) {
            throw new StoreValidationException("Shared secret store root must be an object");
        }

        JsonObject object = parsed.getAsJsonObject();
        rejectUnexpectedFields(object, Set.of("formatVersion", "generation", "entries", "checksum"), "store");
        Jsons.SharedSecretsFields result = new Jsons.SharedSecretsFields();
        result.formatVersion = readRequiredInt(object, "formatVersion");
        result.generation = readRequiredLong(object, "generation");
        if (result.formatVersion != FORMAT_VERSION) {
            throw new StoreValidationException("Unsupported shared secret store formatVersion: " + result.formatVersion);
        }
        if (result.generation < 0) {
            throw new StoreValidationException("Shared secret store generation cannot be negative");
        }
        JsonElement entriesElement = object.get("entries");
        if (entriesElement == null || !entriesElement.isJsonObject()) {
            throw new StoreValidationException("Shared secret store entries must be an object");
        }
        result.entries = new HashMap<>();
        java.util.Set<String> issuerPlayerPairs = new HashSet<>();
        for (Map.Entry<String, JsonElement> entry : entriesElement.getAsJsonObject().entrySet()) {
            validateHashKey(entry.getKey());
            if (entry.getValue() == null || !entry.getValue().isJsonObject()) {
                throw new StoreValidationException("Shared secret entry must be an object");
            }
            JsonObject value = entry.getValue().getAsJsonObject();
            rejectUnexpectedFields(value, Set.of("playerUuid", "issuerNodeId", "issuedAt", "expiresAt"), "entry");
            String playerUuid = readRequiredString(value, "playerUuid");
            String issuerNodeId = readRequiredString(value, "issuerNodeId");
            try {
                UUID.fromString(playerUuid);
            } catch (IllegalArgumentException exception) {
                throw new StoreValidationException("Shared secret entry contains an invalid player UUID", exception);
            }
            if (!issuerNodeId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
                throw new StoreValidationException("Shared secret entry contains an invalid issuer node id");
            }
            if (!issuerPlayerPairs.add(issuerNodeId + "\u0000" + playerUuid)) {
                throw new StoreValidationException("Shared secret store contains multiple active secrets for one issuer and player");
            }
            long issuedAt = readRequiredLong(value, "issuedAt");
            long expiresAt = readRequiredLong(value, "expiresAt");
            if (issuedAt < 0 || expiresAt < issuedAt) {
                throw new StoreValidationException("Shared secret entry contains invalid timestamps");
            }
            result.entries.put(entry.getKey(), new Jsons.SharedSecretEntry(playerUuid, issuerNodeId, issuedAt, expiresAt));
        }
        if (result.entries.size() > paths.maxEntries()) {
            throw new StoreValidationException("Shared secret store exceeds configured entry limit");
        }

        result.checksum = readRequiredString(object, "checksum");
        if (!MessageDigest.isEqual(result.checksum.getBytes(StandardCharsets.US_ASCII), checksumFor(result).getBytes(StandardCharsets.US_ASCII))) {
            throw new StoreValidationException("Shared secret store checksum mismatch");
        }
        return result;
    }

    private static void rejectUnexpectedFields(JsonObject object, Set<String> allowed, String objectName) throws IOException {
        for (String field : object.keySet()) {
            if (!allowed.contains(field)) {
                throw new StoreValidationException("Shared secret store contains an unexpected " + objectName + " field: " + field);
            }
        }
    }

    private static int readRequiredInt(JsonObject object, String field) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new StoreValidationException("Shared secret store field is missing or not numeric: " + field);
        }
        try {
            return value.getAsInt();
        } catch (RuntimeException exception) {
            throw new StoreValidationException("Shared secret store field is invalid: " + field, exception);
        }
    }

    private static long readRequiredLong(JsonObject object, String field) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber()) {
            throw new StoreValidationException("Shared secret store field is missing or not numeric: " + field);
        }
        try {
            return value.getAsLong();
        } catch (RuntimeException exception) {
            throw new StoreValidationException("Shared secret store field is invalid: " + field, exception);
        }
    }

    private static String readRequiredString(JsonObject object, String field) throws IOException {
        JsonElement value = object.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString()) {
            throw new StoreValidationException("Shared secret store field is missing or not a string: " + field);
        }
        String result = value.getAsString();
        if (result.isBlank()) {
            throw new StoreValidationException("Shared secret store field is blank: " + field);
        }
        return result;
    }

    private static void validateHashKey(String value) throws IOException {
        if (value == null || value.length() != 43 || !value.matches("[A-Za-z0-9_-]+")) {
            throw new StoreValidationException("Shared secret store contains an invalid hash key");
        }
        try {
            if (Base64.getUrlDecoder().decode(value).length != 32) {
                throw new StoreValidationException("Shared secret store contains an invalid hash length");
            }
        } catch (IllegalArgumentException exception) {
            throw new StoreValidationException("Shared secret store contains an invalid hash key", exception);
        }
    }

    private void persistLocked(Jsons.SharedSecretsFields document) throws Exception {
        rejectSymlink(paths.secretsFile());
        document.formatVersion = FORMAT_VERSION;
        if (document.entries == null) {
            document.entries = new HashMap<>();
        }
        document.checksum = checksumFor(document);
        byte[] bytes = ConfigTools.GSON.toJson(toJson(document, true)).getBytes(StandardCharsets.UTF_8);
        Path directory = paths.secretsDirectory();
        Files.createDirectories(directory);
        Path temp = createManagedTempFile(directory, ".tmp-");
        boolean moved = false;
        try {
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            paths.protectFile(temp, false, true);
            forceFile(temp);
            readAndValidate(temp);

            if (Files.exists(paths.secretsFile())) {
                Jsons.SharedSecretsFields previous = readAndValidate(paths.secretsFile());
                if (paths.backupCount() > 0) {
                    Path backup = backupPath(previous.generation);
                    rejectSymlink(backup);
                    Files.copy(paths.secretsFile(), backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES);
                    forceFile(backup);
                    paths.protectFile(backup, false, true);
                    readAndValidate(backup);
                }
            }

            moveReplacing(temp, paths.secretsFile(), "shared secret store");
            moved = true;
            paths.protectFile(paths.secretsFile(), false, false);
            forceDirectory(directory);
            cleanupBackups();
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private void restoreBackupLocked(Backup backup) throws Exception {
        Jsons.SharedSecretsFields document = readAndValidate(backup.path);
        byte[] bytes = ConfigTools.GSON.toJson(toJson(document, true)).getBytes(StandardCharsets.UTF_8);
        Path directory = paths.secretsDirectory();
        Path temp = createManagedTempFile(directory, ".restore-");
        boolean moved = false;
        try {
            Files.write(temp, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            paths.protectFile(temp, false, true);
            forceFile(temp);
            readAndValidate(temp);
            rejectSymlink(paths.secretsFile());
            moveReplacing(temp, paths.secretsFile(), "shared secret store recovery");
            moved = true;
            forceDirectory(directory);
        } finally {
            if (!moved) {
                Files.deleteIfExists(temp);
            }
        }
    }

    private void preserveCorruptPrimary(Path primary) throws IOException {
        if (!Files.exists(primary)) {
            return;
        }
        String suffix = ".corrupt-" + Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
        Path preserved = primary.resolveSibling(primary.getFileName() + suffix);
        try {
            Files.move(primary, preserved, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(primary, preserved);
        }
        cleanupCorruptFiles();
    }

    private Optional<Backup> findNewestValidBackup() throws IOException {
        Path directory = paths.secretsDirectory();
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        List<Backup> backups = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                if (Files.isSymbolicLink(candidate)) {
                    continue;
                }
                Matcher matcher = BACKUP_PATTERN.matcher(candidate.getFileName().toString());
                if (!matcher.matches() || !matcher.group(1).equals(paths.secretsFile().getFileName().toString())) {
                    continue;
                }
                try {
                    long generation = Long.parseLong(matcher.group(2));
                    Jsons.SharedSecretsFields document = readAndValidate(candidate);
                    if (document.generation != generation) {
                        continue;
                    }
                    backups.add(new Backup(candidate, generation));
                } catch (Exception ignored) {
                    // Invalid backups are not candidates for recovery.
                }
            }
        }
        return backups.stream().max(Comparator.comparingLong(Backup::generation));
    }

    private Path backupPath(long generation) {
        return paths.secretsDirectory().resolve(paths.secretsFile().getFileName() + ".backup-" + String.format("%020d", generation) + ".json");
    }

    private void cleanupBackups() throws IOException {
        Path directory = paths.secretsDirectory();
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> backups = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                Matcher matcher = BACKUP_PATTERN.matcher(candidate.getFileName().toString());
                if (matcher.matches() && matcher.group(1).equals(paths.secretsFile().getFileName().toString())) {
                    backups.add(candidate);
                }
            }
        }
        backups.sort(Comparator.comparingLong(this::backupGeneration).reversed());
        for (int i = paths.backupCount(); i < backups.size(); i++) {
            Files.deleteIfExists(backups.get(i));
        }
    }

    private void cleanupCorruptFiles() throws IOException {
        Path directory = paths.secretsDirectory();
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> corrupt = new ArrayList<>();
        String prefix = paths.secretsFile().getFileName() + ".corrupt-";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                if (isManagedTimedUuidArtifact(candidate.getFileName().toString(), prefix)) {
                    corrupt.add(candidate);
                }
            }
        }
        corrupt.sort(Comparator.comparingLong(SharedSecretsStore::lastModified).reversed());
        for (int i = 8; i < corrupt.size(); i++) {
            Files.deleteIfExists(corrupt.get(i));
        }
    }

    private boolean hasCorruptEvidence() throws IOException {
        Path directory = paths.secretsDirectory();
        if (!Files.isDirectory(directory)) {
            return false;
        }
        String prefix = paths.secretsFile().getFileName() + ".corrupt-";
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                if (isManagedTimedUuidArtifact(candidate.getFileName().toString(), prefix)) {
                    return true;
                }
            }
        }
        return false;
    }

    private void cleanupTemporaryFiles() throws IOException {
        Path directory = paths.secretsDirectory();
        if (!Files.isDirectory(directory)) {
            return;
        }
        String base = paths.secretsFile().getFileName().toString();
        List<Path> temporary = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                String name = candidate.getFileName().toString();
                if (isManagedUuidArtifact(name, base + ".tmp-", ".json")
                        || isManagedUuidArtifact(name, base + ".restore-", ".json")) {
                    temporary.add(candidate);
                }
            }
        }
        temporary.sort(Comparator.comparingLong(SharedSecretsStore::lastModified).reversed());
        long staleCutoff = System.currentTimeMillis() - 24L * 60L * 60L * 1000L;
        for (int i = 0; i < temporary.size(); i++) {
            Path candidate = temporary.get(i);
            if (i >= 16 || lastModified(candidate) < staleCutoff) {
                Files.deleteIfExists(candidate);
            }
        }
    }

    private void pruneExpired(Jsons.SharedSecretsFields document, long now) {
        document.entries.entrySet().removeIf(entry -> entry.getValue().expiresAt <= now);
    }

    private void limitEntries(Jsons.SharedSecretsFields document) {
        if (document.entries.size() <= paths.maxEntries()) {
            return;
        }
        List<Map.Entry<String, Jsons.SharedSecretEntry>> ordered = new ArrayList<>(document.entries.entrySet());
        ordered.sort(Comparator.comparingLong(entry -> entry.getValue().expiresAt));
        int removeCount = document.entries.size() - paths.maxEntries();
        for (int i = 0; i < removeCount; i++) {
            document.entries.remove(ordered.get(i).getKey());
        }
    }

    private static long incrementGeneration(long generation) {
        if (generation == Long.MAX_VALUE) {
            throw new IllegalStateException("Shared secret store generation exhausted");
        }
        return generation + 1;
    }

    private static long requireNonNegative(Long value, String name) {
        if (value == null || value < 0) {
            throw new IllegalArgumentException(name + " must be non-negative");
        }
        return value;
    }

    private static long epochSeconds() {
        return System.currentTimeMillis() / 1000L;
    }

    private static JsonObject toJson(Jsons.SharedSecretsFields document, boolean includeChecksum) {
        JsonObject object = new JsonObject();
        object.addProperty("formatVersion", document.formatVersion);
        object.addProperty("generation", document.generation);
        JsonObject entries = new JsonObject();
        for (String key : new TreeSet<>(document.entries.keySet())) {
            Jsons.SharedSecretEntry entry = document.entries.get(key);
            JsonObject value = new JsonObject();
            value.addProperty("playerUuid", entry.playerUuid);
            value.addProperty("issuerNodeId", entry.issuerNodeId);
            value.addProperty("issuedAt", entry.issuedAt);
            value.addProperty("expiresAt", entry.expiresAt);
            entries.add(key, value);
        }
        object.add("entries", entries);
        if (includeChecksum) {
            object.addProperty("checksum", document.checksum);
        }
        return object;
    }

    private static String checksumFor(Jsons.SharedSecretsFields document) {
        try {
            byte[] bytes = ConfigTools.GSON.toJson(toJson(document, false)).getBytes(StandardCharsets.UTF_8);
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
    }

    private Path createManagedTempFile(Path directory, String marker) throws IOException {
        String base = paths.secretsFile().getFileName().toString();
        for (int attempt = 0; attempt < 3; attempt++) {
            Path candidate = directory.resolve(base + marker + UUID.randomUUID() + ".json");
            try {
                return Files.createFile(candidate);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // UUID collision is extraordinarily unlikely; try again.
            }
        }
        throw new IOException("Could not allocate a unique shared secret store temporary file");
    }

    private static boolean isManagedUuidArtifact(String name, String prefix, String extension) {
        if (!name.startsWith(prefix) || !name.endsWith(extension)) {
            return false;
        }
        String id = name.substring(prefix.length(), name.length() - extension.length());
        try {
            UUID.fromString(id);
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isManagedTimedUuidArtifact(String name, String prefix) {
        if (!name.startsWith(prefix)) {
            return false;
        }
        String id = name.substring(prefix.length());
        int separator = id.indexOf('-');
        if (separator <= 0 || separator == id.length() - 1) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(id.substring(0, separator));
            UUID.fromString(id.substring(separator + 1));
            return timestamp >= 0;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private void forceFile(Path file) throws IOException {
        if (!paths.fsync()) {
            return;
        }
        try (FileChannel channel = FileChannel.open(file, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private void forceDirectory(Path directory) {
        if (!paths.fsync()) {
            return;
        }
        try (FileChannel channel = FileChannel.open(directory, StandardOpenOption.READ)) {
            channel.force(true);
        } catch (IOException | UnsupportedOperationException ignored) {
            // Directory fsync is not supported on all platforms, notably Windows.
        }
    }

    private static void moveReplacing(Path source, Path target, String description) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            GlobalVariables.LOGGER.warn("Atomic move is unavailable for {}; using replace move with a validated backup", description);
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private long backupGeneration(Path path) {
        Matcher matcher = BACKUP_PATTERN.matcher(path.getFileName().toString());
        return matcher.matches() ? Long.parseLong(matcher.group(2)) : -1;
    }

    private static long lastModified(Path path) {
        try {
            return Files.getLastModifiedTime(path).toMillis();
        } catch (IOException ignored) {
            return Long.MIN_VALUE;
        }
    }

    private static void rejectSymlink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) {
            throw new IOException("Shared secret store path cannot be a symbolic link: " + path.toAbsolutePath().normalize());
        }
    }

    private record Backup(Path path, long generation) {
    }

    public static final class HostSecretRecord {
        private final String secret;
        private final String playerUuid;
        private final String issuerNodeId;
        private final long issuedAt;
        private final long expiresAt;
        private final long generation;

        public HostSecretRecord(String secret, String playerUuid, String issuerNodeId, long issuedAt, long expiresAt, long generation) {
            this.secret = secret;
            this.playerUuid = playerUuid;
            this.issuerNodeId = issuerNodeId;
            this.issuedAt = issuedAt;
            this.expiresAt = expiresAt;
            this.generation = generation;
        }

        public String secret() {
            return secret;
        }

        public String playerUuid() {
            return playerUuid;
        }

        public String issuerNodeId() {
            return issuerNodeId;
        }

        public long issuedAt() {
            return issuedAt;
        }

        public long expiresAt() {
            return expiresAt;
        }

        public long generation() {
            return generation;
        }

        public Map.Entry<String, Secrets.Secret> asLegacyEntry() {
            return new AbstractMap.SimpleImmutableEntry<>(playerUuid, new Secrets.Secret(secret, issuedAt));
        }
    }
}
