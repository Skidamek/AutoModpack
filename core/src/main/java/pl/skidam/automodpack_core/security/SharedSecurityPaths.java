package pl.skidam.automodpack_core.security;

import pl.skidam.automodpack_core.GlobalVariables;
import pl.skidam.automodpack_core.config.Jsons;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Resolved and validated paths for server-side shared security material.
 *
 * The object is immutable so a running server never observes half-resolved
 * configuration. It is created only after the server configuration is loaded.
 */
public final class SharedSecurityPaths {
    public enum AuthorizationMode {
        ISSUER_AT_LOGIN,
        HOST_RECHECK;

        public static AuthorizationMode parse(String value) {
            if (value == null || value.isBlank()) {
                return ISSUER_AT_LOGIN;
            }
            try {
                return valueOf(value.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException exception) {
                throw new IllegalArgumentException("Unsupported sharedSecurity.authorizationMode: " + value);
            }
        }
    }

    private static final Set<Path> ACL_WARNINGS = ConcurrentHashMap.newKeySet();

    private final boolean enabled;
    private final String nodeId;
    private final Path directory;
    private final Path pathValidationRoot;
    private final Path secretsFile;
    private final Path secretsLockFile;
    private final Path certificateFile;
    private final Path privateKeyFile;
    private final Path tlsLockFile;
    private final long lockTimeoutMs;
    private final int backupCount;
    private final boolean fsync;
    private final boolean failClosed;
    private final boolean autoGenerateCertificate;
    private final AuthorizationMode authorizationMode;
    private final int maxEntries;

    private SharedSecurityPaths(
            boolean enabled,
            String nodeId,
            Path directory,
            Path pathValidationRoot,
            Path secretsFile,
            Path secretsLockFile,
            Path certificateFile,
            Path privateKeyFile,
            Path tlsLockFile,
            long lockTimeoutMs,
            int backupCount,
            boolean fsync,
            boolean failClosed,
            boolean autoGenerateCertificate,
            AuthorizationMode authorizationMode,
            int maxEntries
    ) {
        this.enabled = enabled;
        this.nodeId = nodeId;
        this.directory = directory;
        this.pathValidationRoot = pathValidationRoot;
        this.secretsFile = secretsFile;
        this.secretsLockFile = secretsLockFile;
        this.certificateFile = certificateFile;
        this.privateKeyFile = privateKeyFile;
        this.tlsLockFile = tlsLockFile;
        this.lockTimeoutMs = lockTimeoutMs;
        this.backupCount = backupCount;
        this.fsync = fsync;
        this.failClosed = failClosed;
        this.autoGenerateCertificate = autoGenerateCertificate;
        this.authorizationMode = authorizationMode;
        this.maxEntries = maxEntries;
    }

    public static SharedSecurityPaths resolve(Jsons.SharedSecurityFields input, Path workingDirectory) {
        Jsons.SharedSecurityFields config = input == null ? new Jsons.SharedSecurityFields() : input;
        if (!config.enabled) {
            return new SharedSecurityPaths(
                    false,
                    "",
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    10000,
                    0,
                    false,
                    true,
                    false,
                    AuthorizationMode.ISSUER_AT_LOGIN,
                    10000
            );
        }

        if (workingDirectory == null) {
            throw new IllegalArgumentException("Shared security working directory cannot be null");
        }

        String nodeId = requireNodeId(config.nodeId);
        Path base = workingDirectory.toAbsolutePath().normalize();
        Path directory = resolveDirectory(config.directory, base);
        Path configuredDirectory = Path.of(config.directory.trim());
        boolean relativeDirectory = !configuredDirectory.isAbsolute();
        Path pathValidationRoot = relativeDirectory ? base : null;
        Path secretsFile = resolveFile(directory, config.secretsFile, "secretsFile");
        Path secretsLockFile = resolveFile(directory, config.secretsLockFile, "secretsLockFile");
        Path certificateFile = resolveFile(directory, config.certificateFile, "certificateFile");
        Path privateKeyFile = resolveFile(directory, config.privateKeyFile, "privateKeyFile");
        Path tlsLockFile = resolveFile(directory, config.tlsLockFile, "tlsLockFile");

        Set<Path> uniquePaths = Set.of(secretsFile, secretsLockFile, certificateFile, privateKeyFile, tlsLockFile);
        if (uniquePaths.size() != 5) {
            throw new IllegalArgumentException("Shared security data and lock paths must be distinct");
        }

        if (config.lockTimeoutMs < 1 || config.lockTimeoutMs > 24L * 60L * 60L * 1000L) {
            throw new IllegalArgumentException("sharedSecurity.lockTimeoutMs must be between 1 and 86400000");
        }
        if (config.backupCount < 0 || config.backupCount > 32) {
            throw new IllegalArgumentException("sharedSecurity.backupCount must be between 0 and 32");
        }
        if (config.maxEntries < 1 || config.maxEntries > 1_000_000) {
            throw new IllegalArgumentException("sharedSecurity.maxEntries must be between 1 and 1000000");
        }

        return new SharedSecurityPaths(
                true,
                nodeId,
                directory,
                pathValidationRoot,
                secretsFile,
                secretsLockFile,
                certificateFile,
                privateKeyFile,
                tlsLockFile,
                config.lockTimeoutMs,
                config.backupCount,
                config.fsync,
                config.failClosed,
                config.autoGenerateCertificate,
                AuthorizationMode.parse(config.authorizationMode),
                config.maxEntries
        );
    }

    private static String requireNodeId(String value) {
        if (value == null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("sharedSecurity.nodeId must match [A-Za-z0-9][A-Za-z0-9._-]{0,127}");
        }
        return value;
    }

    private static Path resolveDirectory(String value, Path workingDirectory) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("sharedSecurity.directory is required when shared security is enabled");
        }
        Path configured = Path.of(value.trim());
        Path directory = (configured.isAbsolute() ? configured : workingDirectory.resolve(configured))
                .toAbsolutePath()
                .normalize();
        Path hostModpackDirectory = workingDirectory.resolve("automodpack").resolve("host-modpack").toAbsolutePath().normalize();
        if (directory.startsWith(hostModpackDirectory)) {
            throw new IllegalArgumentException("sharedSecurity.directory must not be inside automodpack/host-modpack");
        }
        if (Files.isSymbolicLink(directory)) {
            throw new IllegalArgumentException("sharedSecurity.directory cannot be a symbolic link: " + directory);
        }
        try {
            rejectEscapingSymlink(directory, configured.isAbsolute() ? null : workingDirectory);
            Path hostModpackReal = realPathAllowingMissing(hostModpackDirectory);
            Path directoryReal = realPathAllowingMissing(directory);
            if (directoryReal.startsWith(hostModpackReal)) {
                throw new IllegalArgumentException("sharedSecurity.directory must not resolve inside automodpack/host-modpack: " + directory);
            }
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot validate shared security directory: " + directory, exception);
        }
        return directory;
    }

    private static Path resolveFile(Path directory, String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("sharedSecurity." + fieldName + " cannot be blank");
        }
        Path configured = Path.of(value.trim());
        Path resolved = (configured.isAbsolute() ? configured : directory.resolve(configured))
                .toAbsolutePath()
                .normalize();
        if (!configured.isAbsolute() && !resolved.startsWith(directory)) {
            throw new IllegalArgumentException("sharedSecurity." + fieldName + " escapes sharedSecurity.directory");
        }
        if (Files.isSymbolicLink(resolved)) {
            throw new IllegalArgumentException("sharedSecurity." + fieldName + " cannot be a symbolic link: " + resolved);
        }
        try {
            rejectEscapingSymlink(resolved, configured.isAbsolute() ? null : directory);
        } catch (IOException exception) {
            throw new IllegalArgumentException("Cannot validate shared security path: " + resolved, exception);
        }
        return resolved;
    }

    private static void rejectEscapingSymlink(Path path, Path allowedRoot) throws IOException {
        if (allowedRoot == null) {
            return;
        }
        Path real = realPathAllowingMissing(path);
        Path realRoot = realPathAllowingMissing(allowedRoot);
        if (!real.startsWith(realRoot)) {
            throw new IllegalArgumentException("Shared security path resolves outside its directory: " + path);
        }
    }

    private static Path realPathAllowingMissing(Path path) throws IOException {
        Path absolute = path.toAbsolutePath().normalize();
        List<String> missingNames = new ArrayList<>();
        Path existing = absolute;
        while (existing != null) {
            if (Files.isSymbolicLink(existing)) {
                if (!Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException("Shared security path contains a dangling symbolic link: " + existing);
                }
                break;
            }
            if (Files.exists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                break;
            }
            if (!Files.notExists(existing, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Cannot inspect shared security path: " + existing);
            }
            Path fileName = existing.getFileName();
            if (fileName != null) {
                missingNames.add(fileName.toString());
            }
            existing = existing.getParent();
        }
        if (existing == null) {
            return absolute;
        }

        Path real = existing.toRealPath();
        Collections.reverse(missingNames);
        for (String missingName : missingNames) {
            real = real.resolve(missingName);
        }
        return real.normalize();
    }

    public void ensureDirectory() throws IOException {
        if (!enabled) {
            return;
        }
        boolean existed = Files.exists(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS);
        if (existed && !Files.isDirectory(directory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Shared security path is not a directory: " + directory);
        }
        rejectEscapingSymlink(directory, pathValidationRoot);
        Files.createDirectories(directory);
        if (Files.isSymbolicLink(directory)) {
            throw new IOException("Shared security directory cannot be a symbolic link: " + directory);
        }
        rejectEscapingSymlink(directory, pathValidationRoot);

        trySetPosixDirectoryPermissions(directory);
        warnIfPosixDirectoryIsBroad(directory);
        warnIfWindowsAclIsBroad(directory);
    }

    public void protectFile(Path file, boolean certificate, boolean newlyCreated) {
        if (file == null) {
            return;
        }
        if (isWindows()) {
            return;
        }
        Set<PosixFilePermission> permissions = certificate
                ? EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                PosixFilePermission.GROUP_READ, PosixFilePermission.OTHERS_READ)
                : EnumSet.of(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE);
        trySetPosixPermissions(file, permissions);
    }

    private static void warnIfPosixDirectoryIsBroad(Path path) {
        try {
            Set<PosixFilePermission> permissions = Files.getPosixFilePermissions(path);
            boolean broad = permissions.stream().anyMatch(permission -> permission.name().startsWith("GROUP_") || permission.name().startsWith("OTHERS_"));
            if (broad) {
                GlobalVariables.LOGGER.warn("Shared security directory has group/other permissions; review ACLs: {}", path.toAbsolutePath().normalize());
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Non-POSIX filesystem.
        }
    }

    private static void trySetPosixPermissions(Path path, Set<PosixFilePermission> permissions) {
        try {
            Set<PosixFilePermission> current = Files.getPosixFilePermissions(path);
            if (permissions.containsAll(current)) {
                return;
            }
            Files.setPosixFilePermissions(path, permissions);
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and filesystems without POSIX permissions are handled by ACL warnings/documentation.
        }
    }

    private static void trySetPosixDirectoryPermissions(Path path) {
        try {
            Set<PosixFilePermission> current = Files.getPosixFilePermissions(path);
            Set<PosixFilePermission> target = EnumSet.noneOf(PosixFilePermission.class);
            target.addAll(current);
            target.remove(PosixFilePermission.GROUP_READ);
            target.remove(PosixFilePermission.GROUP_WRITE);
            target.remove(PosixFilePermission.GROUP_EXECUTE);
            target.remove(PosixFilePermission.OTHERS_READ);
            target.remove(PosixFilePermission.OTHERS_WRITE);
            target.remove(PosixFilePermission.OTHERS_EXECUTE);
            target.add(PosixFilePermission.OWNER_READ);
            target.add(PosixFilePermission.OWNER_WRITE);
            target.add(PosixFilePermission.OWNER_EXECUTE);
            if (!current.equals(target)) {
                Files.setPosixFilePermissions(path, target);
            }
        } catch (UnsupportedOperationException | IOException ignored) {
            // Windows and filesystems without POSIX permissions are handled by ACL warnings/documentation.
        }
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
    }

    private static void warnIfWindowsAclIsBroad(Path directory) {
        if (!isWindows() || !ACL_WARNINGS.add(directory)) {
            return;
        }
        try {
            AclFileAttributeView view = Files.getFileAttributeView(directory, AclFileAttributeView.class);
            if (view == null) {
                GlobalVariables.LOGGER.warn("Could not inspect Windows ACLs for shared security directory; restrict it to the configured service accounts: {}", directory);
                return;
            }
            for (AclEntry entry : view.getAcl()) {
                String principal = entry.principal().getName().toLowerCase(Locale.ROOT);
                boolean broadPrincipal = principal.contains("everyone")
                        || principal.endsWith("\\users")
                        || principal.contains("authenticated users");
                boolean allowsRead = entry.type() == AclEntryType.ALLOW
                        && (entry.permissions().contains(AclEntryPermission.READ_DATA)
                        || entry.permissions().contains(AclEntryPermission.READ_ATTRIBUTES));
                if (broadPrincipal && allowsRead) {
                    GlobalVariables.LOGGER.warn("Shared security directory ACL appears broadly readable; restrict it to the configured service accounts: {}", directory);
                    return;
                }
            }
        } catch (IOException | UnsupportedOperationException exception) {
            GlobalVariables.LOGGER.warn("Could not inspect Windows ACLs for shared security directory; restrict it to the configured service accounts: {}", directory);
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public String nodeId() {
        return nodeId;
    }

    public Path directory() {
        return directory;
    }

    public Path secretsFile() {
        return secretsFile;
    }

    public Path secretsLockFile() {
        return secretsLockFile;
    }

    public Path certificateFile() {
        return certificateFile;
    }

    public Path privateKeyFile() {
        return privateKeyFile;
    }

    public Path tlsLockFile() {
        return tlsLockFile;
    }

    public long lockTimeoutMs() {
        return lockTimeoutMs;
    }

    public int backupCount() {
        return backupCount;
    }

    public boolean fsync() {
        return fsync;
    }

    public boolean failClosed() {
        return failClosed;
    }

    public boolean autoGenerateCertificate() {
        return autoGenerateCertificate;
    }

    public AuthorizationMode authorizationMode() {
        return authorizationMode;
    }

    public int maxEntries() {
        return maxEntries;
    }

    /**
     * Returns whether a running host can keep using its current TLS and secret
     * files after a config reload. Policy knobs may change live, but changing
     * the storage identity would split authentication from the running host.
     */
    public boolean hasSameStorageIdentity(SharedSecurityPaths other) {
        return other != null
                && enabled == other.enabled
                && Objects.equals(directory, other.directory)
                && Objects.equals(secretsFile, other.secretsFile)
                && Objects.equals(secretsLockFile, other.secretsLockFile)
                && Objects.equals(certificateFile, other.certificateFile)
                && Objects.equals(privateKeyFile, other.privateKeyFile)
                && Objects.equals(tlsLockFile, other.tlsLockFile);
    }

    public Path secretsDirectory() {
        return secretsFile.getParent() == null ? directory : secretsFile.getParent();
    }

    public Path tlsDirectory() {
        return certificateFile.getParent() == null ? directory : certificateFile.getParent();
    }
}
