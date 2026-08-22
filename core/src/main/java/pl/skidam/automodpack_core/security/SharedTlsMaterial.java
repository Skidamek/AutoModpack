package pl.skidam.automodpack_core.security;

import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.protocol.NetUtils;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.KeyPair;
import java.security.cert.X509Certificate;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Coordinates a shared certificate/private-key pair. All readers and writers
 * take tls.lock, so a reader never accepts a half-published pair.
 */
public final class SharedTlsMaterial {
    private static final long EXPIRY_WARNING_MILLIS = Duration.ofDays(30).toMillis();

    private final X509Certificate certificate;
    private final String fingerprint;

    private SharedTlsMaterial(X509Certificate certificate, String fingerprint) {
        this.certificate = certificate;
        this.fingerprint = fingerprint;
    }

    @FunctionalInterface
    public interface LockedMaterialAction<T> {
        T run(SharedTlsMaterial material) throws Exception;
    }

    public static SharedTlsMaterial loadOrCreate(SharedSecurityPaths paths) throws Exception {
        return withLockedMaterial(paths, material -> material);
    }

    /**
     * Loads or creates the pair and executes the callback while tls.lock is
     * still held. Readers that open the configured files must use this method
     * so publication cannot happen between validation and opening the files.
     */
    public static <T> T withLockedMaterial(SharedSecurityPaths paths, LockedMaterialAction<T> action) throws Exception {
        if (paths == null || !paths.enabled()) {
            throw new IllegalArgumentException("Shared TLS material requires enabled shared security");
        }
        Objects.requireNonNull(action, "action");
        paths.ensureDirectory();
        return SharedSecurityFileLock.withExclusiveLock(paths.tlsLockFile(), paths.lockTimeoutMs(), () -> {
            SharedTlsMaterial material = loadOrCreateLocked(paths);
            return action.run(material);
        });
    }

    private static SharedTlsMaterial loadOrCreateLocked(SharedSecurityPaths paths) throws Exception {
        prepareDirectories(paths);
        rejectSymlink(paths.certificateFile());
        rejectSymlink(paths.privateKeyFile());
        cleanupOldPending(paths);
        cleanupOldStages(paths);

        Optional<PendingPair> pending = findValidPending(paths);
        if (hasBothPrimaryFiles(paths)) {
            try {
                X509Certificate certificate = validatePrimary(paths);
                paths.protectFile(paths.certificateFile(), true, false);
                paths.protectFile(paths.privateKeyFile(), false, false);
                if (pending.isPresent()) {
                    deletePending(pending.get());
                }
                return material(certificate);
            } catch (Exception primaryFailure) {
                // A valid pending pair or a same-generation backup can
                // recover a crash without trusting the mixed/invalid pair.
                if (pending.isPresent()) {
                    publishPair(paths, pending.get().certificate(), pending.get().privateKey());
                    deletePending(pending.get());
                    return material(validatePrimary(paths));
                }
                Optional<BackupPair> backup = findNewestValidBackup(paths);
                if (backup.isPresent()) {
                    preserveCorruptPrimary(paths);
                    publishPair(paths, backup.get().certificate(), backup.get().privateKey());
                    return material(validatePrimary(paths));
                }
                throw new IOException("Shared TLS certificate/key pair is invalid and has no valid backup: "
                        + paths.certificateFile().toAbsolutePath().normalize(), primaryFailure);
            }
        }

        if (pending.isPresent()) {
            preserveIncompletePrimary(paths);
            publishPair(paths, pending.get().certificate(), pending.get().privateKey());
            deletePending(pending.get());
            return material(validatePrimary(paths));
        }

        Optional<BackupPair> backup = findNewestValidBackup(paths);
        if (backup.isPresent()) {
            preserveIncompletePrimary(paths);
            publishPair(paths, backup.get().certificate(), backup.get().privateKey());
            return material(validatePrimary(paths));
        }

        boolean certificateExists = Files.exists(paths.certificateFile());
        boolean privateKeyExists = Files.exists(paths.privateKeyFile());
        if (!certificateExists && !privateKeyExists && paths.autoGenerateCertificate()) {
            generateAndPublish(paths);
            return material(validatePrimary(paths));
        }

        if (!certificateExists && !privateKeyExists) {
            throw new IOException("Shared TLS certificate/key pair is missing and autoGenerateCertificate=false: "
                    + paths.certificateFile().toAbsolutePath().normalize());
        }
        throw new IOException("Shared TLS certificate/key pair is incomplete; refusing to generate or replace half of it: "
                + paths.certificateFile().toAbsolutePath().normalize());
    }

    private static void prepareDirectories(SharedSecurityPaths paths) throws IOException {
        Files.createDirectories(paths.certificateFile().toAbsolutePath().normalize().getParent());
        Files.createDirectories(paths.privateKeyFile().toAbsolutePath().normalize().getParent());
        paths.ensureDirectory();
    }

    private static SharedTlsMaterial material(X509Certificate certificate) throws Exception {
        warnIfExpiring(certificate);
        return new SharedTlsMaterial(certificate, NetUtils.getFingerprint(certificate));
    }

    private static X509Certificate validatePrimary(SharedSecurityPaths paths) throws Exception {
        return NetUtils.validateCertificateAndPrivateKey(paths.certificateFile(), paths.privateKeyFile());
    }

    private static void warnIfExpiring(X509Certificate certificate) {
        long remaining = certificate.getNotAfter().getTime() - System.currentTimeMillis();
        if (remaining <= EXPIRY_WARNING_MILLIS) {
            GlobalVariables.LOGGER.warn("Shared TLS certificate expires soon: {}", certificate.getNotAfter());
        }
    }

    private static void generateAndPublish(SharedSecurityPaths paths) throws Exception {
        KeyPair keyPair = NetUtils.generateKeyPair();
        X509Certificate certificate = NetUtils.selfSign(keyPair);
        String id = UUID.randomUUID().toString();
        Path pendingCertificate = pendingCertificatePath(paths, id);
        Path pendingPrivateKey = pendingPrivateKeyPath(paths, id);
        try {
            NetUtils.saveCertificate(certificate, pendingCertificate);
            NetUtils.savePrivateKey(keyPair.getPrivate(), pendingPrivateKey);
            forceFile(paths, pendingCertificate);
            forceFile(paths, pendingPrivateKey);
            NetUtils.validateCertificateAndPrivateKey(pendingCertificate, pendingPrivateKey);
            publishPair(paths, pendingCertificate, pendingPrivateKey);
        } finally {
            if (Files.exists(paths.certificateFile()) && Files.exists(paths.privateKeyFile())) {
                boolean primaryValid = false;
                try {
                    NetUtils.validateCertificateAndPrivateKey(paths.certificateFile(), paths.privateKeyFile());
                    primaryValid = true;
                } catch (Exception ignored) {
                    // Keep the pending pair as recovery evidence if publication
                    // stopped after only one primary file was replaced.
                }
                if (primaryValid) {
                    Files.deleteIfExists(pendingCertificate);
                    Files.deleteIfExists(pendingPrivateKey);
                }
            }
        }
    }

    private static void publishPair(SharedSecurityPaths paths, Path sourceCertificate, Path sourcePrivateKey) throws Exception {
        NetUtils.validateCertificateAndPrivateKey(sourceCertificate, sourcePrivateKey);
        backupCurrentPair(paths);

        Path certificateStage = createManagedStageFile(paths.certificateFile(), ".crt");
        Path privateKeyStage = createManagedStageFile(paths.privateKeyFile(), ".key");
        boolean certificateMoved = false;
        boolean privateKeyMoved = false;
        try {
            Files.copy(sourceCertificate, certificateStage, StandardCopyOption.REPLACE_EXISTING);
            Files.copy(sourcePrivateKey, privateKeyStage, StandardCopyOption.REPLACE_EXISTING);
            forceFile(paths, certificateStage);
            forceFile(paths, privateKeyStage);
            NetUtils.validateCertificateAndPrivateKey(certificateStage, privateKeyStage);

            moveReplacing(certificateStage, paths.certificateFile(), "shared TLS certificate");
            certificateMoved = true;
            moveReplacing(privateKeyStage, paths.privateKeyFile(), "shared TLS private key");
            privateKeyMoved = true;
            forceFile(paths, paths.certificateFile());
            forceFile(paths, paths.privateKeyFile());
            NetUtils.validateCertificateAndPrivateKey(paths.certificateFile(), paths.privateKeyFile());
            paths.protectFile(paths.certificateFile(), true, true);
            paths.protectFile(paths.privateKeyFile(), false, true);
            cleanupBackups(paths);
        } finally {
            if (!certificateMoved) {
                Files.deleteIfExists(certificateStage);
            }
            if (!privateKeyMoved) {
                Files.deleteIfExists(privateKeyStage);
            }
        }
    }

    private static void backupCurrentPair(SharedSecurityPaths paths) throws Exception {
        boolean certificateExists = Files.exists(paths.certificateFile());
        boolean privateKeyExists = Files.exists(paths.privateKeyFile());
        if (!certificateExists && !privateKeyExists) {
            return;
        }
        if (!certificateExists || !privateKeyExists) {
            preserveIncompletePrimary(paths);
            return;
        }
        try {
            NetUtils.validateCertificateAndPrivateKey(paths.certificateFile(), paths.privateKeyFile());
        } catch (Exception invalid) {
            preserveCorruptPrimary(paths);
            return;
        }

        String id = System.currentTimeMillis() + "-" + UUID.randomUUID();
        Path certificateBackup = backupCertificatePath(paths, id);
        Path privateKeyBackup = backupPrivateKeyPath(paths, id);
        Files.copy(paths.certificateFile(), certificateBackup, StandardCopyOption.REPLACE_EXISTING);
        Files.copy(paths.privateKeyFile(), privateKeyBackup, StandardCopyOption.REPLACE_EXISTING);
        forceFile(paths, certificateBackup);
        forceFile(paths, privateKeyBackup);
        paths.protectFile(certificateBackup, true, true);
        paths.protectFile(privateKeyBackup, false, true);
    }

    private static Optional<PendingPair> findValidPending(SharedSecurityPaths paths) throws IOException {
        Path directory = paths.tlsDirectory();
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        String prefix = paths.certificateFile().getFileName() + ".pending-";
        List<PendingPair> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path certificate : stream) {
                String name = certificate.getFileName().toString();
                if (!isGeneratedPendingName(name, paths.certificateFile().getFileName().toString(), ".crt")) {
                    continue;
                }
                String id = name.substring(prefix.length(), name.length() - 4);
                Path privateKey = pendingPrivateKeyPath(paths, id);
                if (!Files.isRegularFile(privateKey) || Files.isSymbolicLink(certificate) || Files.isSymbolicLink(privateKey)) {
                    continue;
                }
                try {
                    NetUtils.validateCertificateAndPrivateKey(certificate, privateKey);
                    result.add(new PendingPair(certificate, privateKey, lastModified(certificate)));
                } catch (Exception ignored) {
                    // Invalid pending material is never accepted.
                }
            }
        }
        return result.stream().max(Comparator.comparingLong(PendingPair::lastModified));
    }

    private static Optional<BackupPair> findNewestValidBackup(SharedSecurityPaths paths) throws IOException {
        Path directory = paths.tlsDirectory();
        if (!Files.isDirectory(directory)) {
            return Optional.empty();
        }
        String prefix = paths.certificateFile().getFileName() + ".backup-";
        List<BackupPair> result = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path certificate : stream) {
                String name = certificate.getFileName().toString();
                if (!isGeneratedBackupName(name, paths.certificateFile().getFileName().toString(), ".crt")) {
                    continue;
                }
                String id = name.substring(prefix.length(), name.length() - 4);
                Path privateKey = backupPrivateKeyPath(paths, id);
                if (!Files.isRegularFile(privateKey) || Files.isSymbolicLink(certificate) || Files.isSymbolicLink(privateKey)) {
                    continue;
                }
                try {
                    NetUtils.validateCertificateAndPrivateKey(certificate, privateKey);
                    result.add(new BackupPair(certificate, privateKey, lastModified(certificate)));
                } catch (Exception ignored) {
                    // Backup is only useful if the pair is cryptographically valid.
                }
            }
        }
        return result.stream().max(Comparator.comparingLong(BackupPair::lastModified));
    }

    private static void preserveCorruptPrimary(SharedSecurityPaths paths) throws IOException {
        preserveFile(paths.certificateFile());
        preserveFile(paths.privateKeyFile());
        preserveCorruptFiles(paths);
    }

    private static void preserveIncompletePrimary(SharedSecurityPaths paths) throws IOException {
        preserveFile(paths.certificateFile());
        preserveFile(paths.privateKeyFile());
    }

    private static void preserveFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            return;
        }
        Path target = path.resolveSibling(path.getFileName() + ".corrupt-" + System.currentTimeMillis() + "-" + UUID.randomUUID());
        try {
            Files.move(path, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(path, target);
        }
    }

    private static void deletePending(PendingPair pair) throws IOException {
        Files.deleteIfExists(pair.certificate());
        Files.deleteIfExists(pair.privateKey());
    }

    private static void cleanupOldPending(SharedSecurityPaths paths) throws IOException {
        Path directory = paths.tlsDirectory();
        if (!Files.isDirectory(directory)) {
            return;
        }
        long cutoff = System.currentTimeMillis() - Duration.ofDays(7).toMillis();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                String name = candidate.getFileName().toString();
                if ((isGeneratedPendingName(name, paths.certificateFile().getFileName().toString(), ".crt")
                        || isGeneratedPendingName(name, paths.privateKeyFile().getFileName().toString(), ".key"))
                        && lastModified(candidate) < cutoff) {
                    Files.deleteIfExists(candidate);
                }
            }
        }
    }

    private static void cleanupOldStages(SharedSecurityPaths paths) throws IOException {
        long cutoff = System.currentTimeMillis() - Duration.ofDays(1).toMillis();
        List<Path> stages = new ArrayList<>();
        collectStages(paths.certificateFile(), ".crt", stages);
        collectStages(paths.privateKeyFile(), ".key", stages);
        stages.sort(Comparator.comparingLong(SharedTlsMaterial::lastModified).reversed());
        for (int i = 0; i < stages.size(); i++) {
            Path stage = stages.get(i);
            if (i >= 32 || lastModified(stage) < cutoff) {
                Files.deleteIfExists(stage);
            }
        }
    }

    private static void collectStages(Path primary, String extension, List<Path> stages) throws IOException {
        Path directory = primary.toAbsolutePath().normalize().getParent();
        if (directory == null || !Files.isDirectory(directory)) {
            return;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                if (isGeneratedStageName(candidate.getFileName().toString(), primary.getFileName().toString(), extension)) {
                    stages.add(candidate);
                }
            }
        }
    }

    private static void cleanupBackups(SharedSecurityPaths paths) throws IOException {
        Path directory = paths.tlsDirectory();
        if (!Files.isDirectory(directory)) {
            return;
        }
        String certificatePrefix = paths.certificateFile().getFileName() + ".backup-";
        List<Path> certificates = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                if (isGeneratedBackupName(candidate.getFileName().toString(), paths.certificateFile().getFileName().toString(), ".crt")) {
                    certificates.add(candidate);
                }
            }
        }
        certificates.sort(Comparator.comparingLong(SharedTlsMaterial::lastModified).reversed());
        for (int i = paths.backupCount(); i < certificates.size(); i++) {
            Path certificate = certificates.get(i);
            String id = certificate.getFileName().toString().substring(certificatePrefix.length(), certificate.getFileName().toString().length() - 4);
            Files.deleteIfExists(certificate);
            Files.deleteIfExists(backupPrivateKeyPath(paths, id));
        }
    }

    private static void preserveCorruptFiles(SharedSecurityPaths paths) throws IOException {
        Path directory = paths.tlsDirectory();
        if (!Files.isDirectory(directory)) {
            return;
        }
        List<Path> corrupt = new ArrayList<>();
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory)) {
            for (Path candidate : stream) {
                String name = candidate.getFileName().toString();
                if (isGeneratedCorruptName(name, paths.certificateFile().getFileName().toString())
                        || isGeneratedCorruptName(name, paths.privateKeyFile().getFileName().toString())) {
                    corrupt.add(candidate);
                }
            }
        }
        corrupt.sort(Comparator.comparingLong(SharedTlsMaterial::lastModified).reversed());
        for (int i = 8; i < corrupt.size(); i++) {
            Files.deleteIfExists(corrupt.get(i));
        }
    }

    private static boolean isGeneratedCorruptName(String name, String baseName) {
        String prefix = baseName + ".corrupt-";
        if (!name.startsWith(prefix)) {
            return false;
        }
        String suffix = name.substring(prefix.length());
        int separator = suffix.indexOf('-');
        if (separator <= 0 || separator == suffix.length() - 1) {
            return false;
        }
        try {
            long timestamp = Long.parseLong(suffix.substring(0, separator));
            UUID.fromString(suffix.substring(separator + 1));
            return timestamp >= 0;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isGeneratedPendingName(String name, String baseName, String extension) {
        String prefix = baseName + ".pending-";
        if (!name.startsWith(prefix) || !name.endsWith(extension)) {
            return false;
        }
        try {
            UUID.fromString(name.substring(prefix.length(), name.length() - extension.length()));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isGeneratedStageName(String name, String baseName, String extension) {
        String prefix = baseName + ".stage-";
        if (!name.startsWith(prefix) || !name.endsWith(extension)) {
            return false;
        }
        try {
            UUID.fromString(name.substring(prefix.length(), name.length() - extension.length()));
            return true;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static boolean isGeneratedBackupName(String name, String baseName, String extension) {
        String prefix = baseName + ".backup-";
        if (!name.startsWith(prefix) || !name.endsWith(extension)) {
            return false;
        }
        String id = name.substring(prefix.length(), name.length() - extension.length());
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

    private static boolean hasBothPrimaryFiles(SharedSecurityPaths paths) {
        return Files.isRegularFile(paths.certificateFile()) && Files.isRegularFile(paths.privateKeyFile());
    }

    private static Path createManagedStageFile(Path primary, String extension) throws IOException {
        Path directory = primary.toAbsolutePath().normalize().getParent();
        if (directory == null) {
            throw new IOException("TLS material path has no parent directory: " + primary.toAbsolutePath().normalize());
        }
        String prefix = primary.getFileName() + ".stage-";
        for (int attempt = 0; attempt < 3; attempt++) {
            Path candidate = directory.resolve(prefix + UUID.randomUUID() + extension);
            try {
                return Files.createFile(candidate);
            } catch (java.nio.file.FileAlreadyExistsException ignored) {
                // UUID collision is extraordinarily unlikely; try again.
            }
        }
        throw new IOException("Could not allocate a unique TLS staging file");
    }

    private static Path pendingCertificatePath(SharedSecurityPaths paths, String id) {
        return paths.certificateFile().resolveSibling(paths.certificateFile().getFileName() + ".pending-" + id + ".crt");
    }

    private static Path pendingPrivateKeyPath(SharedSecurityPaths paths, String id) {
        return paths.privateKeyFile().resolveSibling(paths.privateKeyFile().getFileName() + ".pending-" + id + ".key");
    }

    private static Path backupCertificatePath(SharedSecurityPaths paths, String id) {
        return paths.certificateFile().resolveSibling(paths.certificateFile().getFileName() + ".backup-" + id + ".crt");
    }

    private static Path backupPrivateKeyPath(SharedSecurityPaths paths, String id) {
        return paths.privateKeyFile().resolveSibling(paths.privateKeyFile().getFileName() + ".backup-" + id + ".key");
    }

    private static void forceFile(SharedSecurityPaths paths, Path path) throws IOException {
        if (!paths.fsync()) {
            return;
        }
        try (FileChannel channel = FileChannel.open(path, StandardOpenOption.WRITE)) {
            channel.force(true);
        }
    }

    private static void moveReplacing(Path source, Path target, String description) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
        } catch (AtomicMoveNotSupportedException exception) {
            GlobalVariables.LOGGER.warn("Atomic move is unavailable for {}; using replace move with a validated pair backup", description);
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
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
            throw new IOException("Shared TLS material path cannot be a symbolic link: " + path.toAbsolutePath().normalize());
        }
    }

    public X509Certificate certificate() {
        return certificate;
    }

    public String fingerprint() {
        return fingerprint;
    }

    private record PendingPair(Path certificate, Path privateKey, long lastModified) {
    }

    private record BackupPair(Path certificate, Path privateKey, long lastModified) {
    }
}
