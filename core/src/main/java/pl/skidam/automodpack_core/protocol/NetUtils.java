package pl.skidam.automodpack_core.protocol;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import pl.skidam.automodpack_core.utils.CustomFileUtils;
import pl.skidam.automodpack_core.utils.LockFreeInputStream;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Collection;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HexFormat;
import java.util.ArrayList;
import java.util.List;

public class NetUtils {

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

    public static List<X509Certificate> loadCertificateChain(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Certificate file does not exist: " + path.toAbsolutePath().normalize());
        }
        try (InputStream in = new LockFreeInputStream(path)) {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends java.security.cert.Certificate> certificates = cf.generateCertificates(in);
            List<X509Certificate> result = new ArrayList<>(certificates.size());
            for (java.security.cert.Certificate certificate : certificates) {
                if (!(certificate instanceof X509Certificate x509Certificate)) {
                    throw new GeneralSecurityException("Certificate chain contains a non-X.509 certificate");
                }
                result.add(x509Certificate);
            }
            if (result.isEmpty()) {
                throw new GeneralSecurityException("Certificate chain is empty");
            }
            return result;
        }
    }

    public static PrivateKey loadPrivateKey(Path path) throws Exception {
        if (!Files.isRegularFile(path)) {
            throw new IllegalStateException("Private key file does not exist: " + path.toAbsolutePath().normalize());
        }
        String pem = Files.readString(path, StandardCharsets.US_ASCII);
        String encoded = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        if (encoded.isBlank()) {
            throw new GeneralSecurityException("Private key PEM is empty");
        }
        byte[] der;
        try {
            der = Base64.getDecoder().decode(encoded);
        } catch (IllegalArgumentException exception) {
            throw new GeneralSecurityException("Private key PEM is not valid base64", exception);
        }
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(der);
        List<String> algorithms = List.of("RSA", "EC", "Ed25519", "DSA");
        for (String algorithm : algorithms) {
            try {
                return KeyFactory.getInstance(algorithm).generatePrivate(keySpec);
            } catch (GeneralSecurityException ignored) {
                // Try the next standard PKCS#8 algorithm.
            }
        }
        throw new GeneralSecurityException("Unsupported or invalid PKCS#8 private key");
    }

    /**
     * Parse the complete chain, validate its validity window and prove that
     * the leaf public key matches the PKCS#8 private key.
     */
    public static X509Certificate validateCertificateAndPrivateKey(Path certificatePath, Path privateKeyPath) throws Exception {
        List<X509Certificate> chain = loadCertificateChain(certificatePath);
        for (X509Certificate certificate : chain) {
            certificate.checkValidity();
        }
        X509Certificate leaf = chain.get(0);
        PrivateKey privateKey = loadPrivateKey(privateKeyPath);
        if (!leaf.getPublicKey().getAlgorithm().equalsIgnoreCase(privateKey.getAlgorithm())) {
            throw new GeneralSecurityException("Certificate public key algorithm does not match private key algorithm");
        }

        String signatureAlgorithm = switch (privateKey.getAlgorithm().toUpperCase()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "DSA" -> "SHA256withDSA";
            case "ED25519" -> "Ed25519";
            default -> throw new GeneralSecurityException("Unsupported private key algorithm: " + privateKey.getAlgorithm());
        };
        byte[] challenge = new byte[32];
        SecureRandom.getInstanceStrong().nextBytes(challenge);
        Signature signature = Signature.getInstance(signatureAlgorithm);
        signature.initSign(privateKey);
        signature.update(challenge);
        byte[] proof = signature.sign();
        signature.initVerify(leaf.getPublicKey());
        signature.update(challenge);
        if (!signature.verify(proof)) {
            throw new GeneralSecurityException("Certificate and private key do not match");
        }
        return leaf;
    }

    public static void savePrivateKey(PrivateKey key, Path path) throws Exception {
        PKCS8EncodedKeySpec keySpec = new PKCS8EncodedKeySpec(key.getEncoded());
        String keyPem = "-----BEGIN PRIVATE KEY-----\n"
                + formatBase64(Base64.getEncoder().encodeToString(keySpec.getEncoded()))
                + "-----END PRIVATE KEY-----";
        CustomFileUtils.setupFilePaths(path);
        Files.writeString(path, keyPem);
    }

    private static String formatBase64(String base64) {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < base64.length(); i += 64) {
            sb.append(base64, i, Math.min(i + 64, base64.length()));
            sb.append("\n");
        }
        return sb.toString();
    }
}
