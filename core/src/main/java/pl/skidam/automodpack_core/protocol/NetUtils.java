package pl.skidam.automodpack_core.protocol;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import pl.skidam.automodpack_core.utils.SmartFileUtils;
import pl.skidam.automodpack_core.utils.LockFreeInputStream;

import java.io.InputStream;
import java.math.BigInteger;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.*;
import java.security.cert.CertificateEncodingException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;
import java.util.Calendar;
import java.util.Date;
import java.util.HexFormat;
import java.util.Locale;

public class NetUtils {

    // Magic numbers
    public static final int MAGIC_AMMH = 0x414D4D48;
    public static final int MAGIC_AMOK = 0x414D4F4B;

    // Protocol versions
    public static final byte LATEST_SUPPORTED_PROTOCOL_VERSION = 0x01;

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

	private static final Provider BC_PROVIDER = new BouncyCastleProvider();

	static {
		if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
			Security.addProvider(BC_PROVIDER);
		}
	}

    public static String getFingerprint(X509Certificate cert) throws CertificateEncodingException {
        byte[] certificate = cert.getEncoded();

        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] fingerprint = digest.digest(certificate);
            return HexFormat.of().formatHex(fingerprint).toLowerCase(Locale.ROOT);
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
        long now = System.currentTimeMillis();
        Date startDate = new Date(now);

        X500Name dnName = new X500Name("CN=AutoModpack Self Signed Certificate");
	    BigInteger certSerialNumber = new BigInteger(159, new SecureRandom());

        Calendar calendar = Calendar.getInstance();
        calendar.setTime(startDate);
        calendar.add(Calendar.YEAR, 1);
        Date endDate = calendar.getTime();

        String signatureAlgorithm = "SHA256WithRSA";
        ContentSigner contentSigner = new JcaContentSignerBuilder(signatureAlgorithm).build(keyPair.getPrivate());
        JcaX509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(dnName, certSerialNumber, startDate, endDate, dnName, keyPair.getPublic());

        return new JcaX509CertificateConverter().setProvider(BC_PROVIDER).getCertificate(certBuilder.build(contentSigner));
    }

    public static void saveCertificate(X509Certificate cert, Path path) throws Exception {
        String certPem = "-----BEGIN CERTIFICATE-----\n"
                + formatBase64(cert.getEncoded())
                + "-----END CERTIFICATE-----\n";
        SmartFileUtils.createParentDirs(path);
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
                + formatBase64(keySpec.getEncoded())
                + "-----END PRIVATE KEY-----\n";
        SmartFileUtils.createParentDirs(path);
        Files.writeString(path, keyPem);
    }

	private static String formatBase64(byte[] derEncodedBytes) {
		Base64.Encoder encoder = Base64.getMimeEncoder(64, new byte[]{'\n'});
		return encoder.encodeToString(derEncodedBytes) + "\n";
	}
}
