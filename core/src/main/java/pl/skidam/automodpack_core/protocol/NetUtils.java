package pl.skidam.automodpack_core.protocol;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.LockFreeInputStream;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.InvalidKeySpecException;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.Base64;
import java.time.Duration;
import java.util.Calendar;
import java.util.Date;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static pl.skidam.automodpack_core.GlobalVariables.AM_VERSION;
import static pl.skidam.automodpack_core.GlobalVariables.LOGGER;

public class NetUtils {

    public static final String USER_AGENT = "github/skidamek/automodpack/" + AM_VERSION;
    public static final Duration NETWORK_TIMEOUT = Duration.ofSeconds(15);

    // Magic numbers
    public static final int MAGIC_AMMH = 0x414D4D48;
    public static final int MAGIC_AMOK = 0x414D4F4B;

    // Protocol versions
    public static final byte PROTOCOL_VERSION = 0x01;

    // Compression types
    public static final byte COMPRESSION_NONE = 0x00;
    public static final byte COMPRESSION_ZSTD = 0x01;
    public static final byte COMPRESSION_GZIP = 0x02;

    // Message types and configuration message types should not overlap
    // Message types
    public static final byte ECHO_TYPE = 0x00;
    public static final byte FILE_REQUEST_TYPE = 0x01;
    public static final byte FILE_RESPONSE_TYPE = 0x02;
    public static final byte REFRESH_REQUEST_TYPE = 0x03;
    public static final byte END_OF_TRANSMISSION = 0x04;
    public static final byte ERROR = 0x05;

    // Configuration message types
    public static final byte CONFIGURATION_ECHO_TYPE = 0x40;
    public static final byte CONFIGURATION_COMPRESSION_TYPE = 0x41;
    public static final byte CONFIGURATION_CHUNK_SIZE_TYPE = 0x42;

    // Chunk size
    public static final int DEFAULT_CHUNK_SIZE = 256 * 1024; // 256 KB
    public static final int MIN_CHUNK_SIZE = 8 * 1024; // 8 KB
    public static final int MAX_CHUNK_SIZE = 512 * 1024; // 512 KB

    private static final Set<PosixFilePermission> OWNER_ONLY = PosixFilePermissions.fromString("rw-------");

    public static String getFingerprint(X509Certificate cert) throws CertificateEncodingException {
        byte[] certificate = cert.getEncoded();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fingerprint = digest.digest(certificate);
            return HexFormat.of().formatHex(fingerprint);
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException(e);
        }
    }

    public static KeyPair generateKeyPair() throws Exception {
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        return keyPairGenerator.generateKeyPair();
    }

    public static X509Certificate selfSign(KeyPair keyPair) throws Exception {
        Provider bcProvider = new BouncyCastleProvider();
        Security.addProvider(bcProvider);

        long now = System.currentTimeMillis();
        Date startDate = new Date(now);

        X500Name dnName = new X500Name("CN=AutoModpack Self Signed Certificate");
        BigInteger certSerialNumber = new BigInteger(Long.toString(now)); // <-- Using the current timestamp as the certificate serial number

        Calendar calendar = Calendar.getInstance();
        calendar.setTime(startDate);
        calendar.add(Calendar.YEAR, 1); // <-- 1 Yr validity, does not matter, we don't validate it anyway
        Date endDate = calendar.getTime();

        String signatureAlgorithm = "SHA256WithRSA"; // <-- Use appropriate signature algorithm based on your keyPair algorithm.
        ContentSigner contentSigner = new JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.getPrivate());
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(dnName, certSerialNumber, startDate, endDate, dnName, keyPair.getPublic());

        return new JcaX509CertificateConverter().setProvider(bcProvider).getCertificate(certBuilder.build(contentSigner));
    }

    public static void saveCertificate(X509Certificate cert, Path path) throws Exception {
        String certPem = "-----BEGIN CERTIFICATE-----\n"
                + formatBase64(Base64.getEncoder().encodeToString(cert.getEncoded()))
                + "-----END CERTIFICATE-----";
        CustomFileUtils.setupFilePaths(path);
        Files.writeString(path, certPem);
    }

    public static X509Certificate loadCertificate(Path path) throws Exception {
        if (!Files.exists(path)) return null;
        try (InputStream in = new LockFreeInputStream(path)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            return (X509Certificate) cf.generateCertificate(in);
        }
    }

    public static void savePrivateKey(PrivateKey key, Path path) throws Exception {
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(key.getEncoded());
        String keyPem = "-----BEGIN PRIVATE KEY-----\n"
                + formatBase64(Base64.getEncoder().encodeToString(keySpec.getEncoded()))
                + "-----END PRIVATE KEY-----";
        writeOwnerOnly(path, keyPem.getBytes(StandardCharsets.UTF_8));
    }

    /** Reads the PKCS#8 PEM {@link #savePrivateKey} writes; null when the file is missing. */
    public static PrivateKey loadPrivateKey(Path path) throws Exception {
        if (!Files.exists(path)) return null;
        String pem = Files.readString(path, StandardCharsets.UTF_8);
        String base64 = pem.replace("-----BEGIN PRIVATE KEY-----", "").replace("-----END PRIVATE KEY-----", "").replaceAll("\\s", "");
        byte[] der = Base64.getMimeDecoder().decode(base64);
        for (String algorithm : List.of("RSA", "EC")) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(new PKCS8EncodedKeySpec(der));
            } catch (InvalidKeySpecException notThisKeyType) {
            }
        }
        throw new GeneralSecurityException("Unsupported private key format in " + path);
    }

    /**
     * Makes sure the server has a TLS pair without ever replacing a file the operator put there. A pair that exists is
     * left alone. A certificate without a key refuses, because it may be the operator's CA certificate and clients may
     * already trust it. A key without a certificate keeps the key and gets a fresh self-signed certificate built from it.
     * A fresh pair writes the key first, so a crash in between leaves that recoverable shape.
     */
    public static void ensureSelfSignedTlsPair(Path certFile, Path privateKeyFile) throws IOException {
        boolean certExists = Files.exists(certFile);
        boolean keyExists = Files.exists(privateKeyFile);
        if (certExists && keyExists) {
            return;
        }
        if (certExists) {
            throw new IllegalStateException("The TLS certificate exists without a private key (" + certFile + ", " + privateKeyFile + "); make the pair whole or remove both files manually");
        }

        KeyPair keyPair;
        try {
            keyPair = keyExists ? existingRsaKeyPair(privateKeyFile) : generateKeyPair();
        } catch (Exception e) {
            throw new IllegalStateException("The TLS private key " + privateKeyFile + " has no certificate and cannot be given a self-signed one; put its certificate at " + certFile, e);
        }

        try {
            if (!keyExists) savePrivateKey(keyPair.getPrivate(), privateKeyFile);
            saveCertificate(selfSign(keyPair), certFile);
        } catch (IOException e) {
            throw e;
        } catch (Exception e) {
            throw new IOException("Failed to write the TLS pair", e);
        }
    }

    // The pair behind an existing PKCS#8 RSA key: the CRT form carries the public exponent, so the public key needs no other file.
    private static KeyPair existingRsaKeyPair(Path privateKeyFile) throws Exception {
        if (!(loadPrivateKey(privateKeyFile) instanceof RSAPrivateCrtKey key)) {
            throw new GeneralSecurityException("only an RSA private key can sign the self-signed certificate");
        }
        PublicKey publicKey = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(key.getModulus(), key.getPublicExponent()));
        return new KeyPair(publicKey, key);
    }

    /**
     * Writes a secret through a temporary that is owner-only before any byte lands, then moves it into place, so the
     * key never exists under a group- or world-readable name. POSIX creates it rw-------, Windows gets a single
     * owner-allow ACL entry. A rename keeps both.
     */
    public static void writeOwnerOnly(Path target, byte[] bytes) throws IOException {
        Path parent = target.toAbsolutePath().normalize().getParent();
        if (parent == null) throw new IOException("Path has no parent: " + target);
        Files.createDirectories(parent);

        Path temporary = parent.resolve("." + target.getFileName() + "." + UUID.randomUUID() + ".tmp");
        boolean posix = Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null;
        FileAttribute<?>[] attributes = posix ? new FileAttribute<?>[]{PosixFilePermissions.asFileAttribute(OWNER_ONLY)} : new FileAttribute<?>[0];
        try {
            try (FileChannel channel = FileChannel.open(temporary, Set.of(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE), attributes)) {
                if (!posix) restrictAclToOwner(temporary);
                ByteBuffer buffer = ByteBuffer.wrap(bytes);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    // Windows: a single owner-allow ACL entry. Where the filesystem has no ACL view or the ACL APIs fail, the file keeps what the filesystem gave it.
    private static void restrictAclToOwner(Path file) {
        AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl == null) return;
        try {
            AclEntry ownerAllow = AclEntry.newBuilder()
                    .setType(AclEntryType.ALLOW)
                    .setPrincipal(Files.getOwner(file, LinkOption.NOFOLLOW_LINKS))
                    .setPermissions(EnumSet.allOf(AclEntryPermission.class))
                    .build();
            acl.setAcl(List.of(ownerAllow));
        } catch (UnsupportedOperationException | SecurityException | IOException e) {
            LOGGER.warn("Could not restrict {} to the owner: {}", file, e.toString());
        }
    }

    /**
     * New keys are created owner-only; an existing key that group or others can read is left as it is and called out,
     * so the operator can chmod 600 it.
     */
    public static void warnIfPrivateKeyIsTooOpen(Path privateKeyFile) {
        Set<PosixFilePermission> permissions;
        try {
            permissions = Files.getPosixFilePermissions(privateKeyFile);
        } catch (UnsupportedOperationException | IOException e) {
            return;
        }
        boolean shared = permissions.contains(PosixFilePermission.GROUP_READ) || permissions.contains(PosixFilePermission.GROUP_WRITE) || permissions.contains(PosixFilePermission.GROUP_EXECUTE)
                || permissions.contains(PosixFilePermission.OTHERS_READ) || permissions.contains(PosixFilePermission.OTHERS_WRITE) || permissions.contains(PosixFilePermission.OTHERS_EXECUTE);
        if (shared) {
            LOGGER.warn("{} is readable by group or others; chmod 600 it so only the server user can read the private key", privateKeyFile);
        }
    }

    private static String formatBase64(String base64) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length()));
            sb.append("\n");
        }
        return sb.toString();
    }

    /** Closes and swallows the failure: teardown paths never have a better story than the error they are already telling. */
    public static void closeQuietly(AutoCloseable closeable) {
        if (closeable == null) return;
        try {
            closeable.close();
        } catch (Exception ignored) {
        }
    }
}
