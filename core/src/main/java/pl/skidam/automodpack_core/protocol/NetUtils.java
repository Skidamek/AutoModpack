package pl.skidam.automodpack_core.protocol;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;
import pl.skidam.automodpack_core.utils.CustomFileUtils;

import java.io.InputStream;
import java.io.ByteArrayInputStream;
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

public class NetUtils {

    public static final int CHUNK_SIZE = 131072; // 128 KB - good for zstd
    public static final int MAGIC_AMMC = 0x414D4D43;
    public static final int MAGIC_AMOK = 0x414D4F4B;

    public static final byte ECHO_TYPE = 0x00;
    public static final byte FILE_REQUEST_TYPE = 0x01;
    public static final byte FILE_RESPONSE_TYPE = 0x02;
    public static final byte REFRESH_REQUEST_TYPE = 0x03;
    public static final byte END_OF_TRANSMISSION = 0x04;
    public static final byte ERROR = 0x05;

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
	try (InputStream in = Files.newInputStream(path)) {
		CertificateFactory cf = CertificateFactory.getInstance("X.509");
		return (X509Certificate) cf.generateCertificate(in);
	}
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
