package pl.skidam.automodpack_core.protocol;

import org.apache.hc.client5.http.ssl.DefaultHostnameVerifier;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeAll;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.cert.X509Certificate;
import java.util.Date;
import java.math.BigInteger;

import org.bouncycastle.asn1.x500.X500Name;
import org.bouncycastle.asn1.x509.GeneralName;
import org.bouncycastle.asn1.x509.GeneralNames;
import org.bouncycastle.asn1.x509.Extension;
import org.bouncycastle.cert.X509v3CertificateBuilder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder;
import org.bouncycastle.operator.ContentSigner;
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder;

import javax.net.ssl.SSLException;

import static org.junit.jupiter.api.Assertions.*;

class DefaultHostnameVerifierTest {

    private static final DefaultHostnameVerifier hostnameVerifier = new DefaultHostnameVerifier();
    private static X509Certificate certificateWithSAN;
    private static X509Certificate certificateWithCNOnly;
    private static X509Certificate certificateWithWildcard;

    @BeforeAll
    static void setUp() throws Exception {
        // Initialize BouncyCastle provider
        java.security.Security.addProvider(new org.bouncycastle.jce.provider.BouncyCastleProvider());

        // Generate key pair for certificates
        KeyPairGenerator keyPairGenerator = KeyPairGenerator.getInstance("RSA");
        keyPairGenerator.initialize(2048);
        KeyPair keyPair = keyPairGenerator.generateKeyPair();

        // Create certificate with Subject Alternative Names
        certificateWithSAN = createCertificate(
                keyPair,
                "CN=example.com",
                new String[]{"example.com", "www.example.com", "api.example.com"}
        );

        // Create certificate with Common Name only
        certificateWithCNOnly = createCertificate(
                keyPair,
                "CN=example.org",
                null
        );

        // Create certificate with wildcard domain
        certificateWithWildcard = createCertificate(
                keyPair,
                "CN=*.example.net",
                new String[]{"*.example.net"}
        );
    }

    private static X509Certificate createCertificate(KeyPair keyPair, String subjectDN, String[] subjectAltNames) throws Exception {
        X500Name issuerName = new X500Name(subjectDN);
        X500Name subjectName = new X500Name(subjectDN);

        BigInteger serialNumber = BigInteger.valueOf(System.currentTimeMillis());
        Date notBefore = new Date(System.currentTimeMillis() - 24 * 60 * 60 * 1000); // 1 day ago
        Date notAfter = new Date(System.currentTimeMillis() + 365 * 24 * 60 * 60 * 1000L); // 1 year from now

        X509v3CertificateBuilder certBuilder = new JcaX509v3CertificateBuilder(
                issuerName,
                serialNumber,
                notBefore,
                notAfter,
                subjectName,
                keyPair.getPublic()
        );

        // Add Subject Alternative Names if provided
        if (subjectAltNames != null && subjectAltNames.length > 0) {
            GeneralName[] names = new GeneralName[subjectAltNames.length];
            for (int i = 0; i < subjectAltNames.length; i++) {
                names[i] = new GeneralName(GeneralName.dNSName, subjectAltNames[i]);
            }
            GeneralNames subjectAltName = new GeneralNames(names);
            certBuilder.addExtension(Extension.subjectAlternativeName, false, subjectAltName);
        }

        ContentSigner contentSigner = new JcaContentSignerBuilder("SHA256WithRSA").build(keyPair.getPrivate());
        return new JcaX509CertificateConverter().getCertificate(certBuilder.build(contentSigner));
    }

    public boolean verify(final String host, final X509Certificate x509) {
        try {
            hostnameVerifier.verify(host, x509);
            return true;
        } catch (final SSLException ex) {
            return false;
        }
    }

    @Test
    void testNullOrEmptyInputs() {
//        assertFalse(verify("example.com",null));
//        assertFalse(verify(null, certificateWithSAN));
        assertFalse(verify("", certificateWithSAN));
    }

    @Test
    void testDomainValidationWithSAN() {
        assertTrue(verify("example.com", certificateWithSAN));
        assertTrue(verify("www.example.com", certificateWithSAN));
        assertTrue(verify("api.example.com", certificateWithSAN));
        assertFalse(verify("other.example.com", certificateWithSAN));
        assertFalse(verify("example.org", certificateWithSAN));
        assertFalse(verify("www.example.org", certificateWithSAN));
    }

    @Test
    void testDomainValidationWithCNOnly() {
        assertTrue(verify("example.org", certificateWithCNOnly));
        assertFalse(verify("www.example.org", certificateWithCNOnly));
        assertFalse(verify("example.com", certificateWithCNOnly));
    }

    @Test
    void testDomainValidationWithWildcard() {
        assertFalse(verify("example.net", certificateWithWildcard));
        assertTrue(verify("www.example.net", certificateWithWildcard));
        assertTrue(verify("api.example.net", certificateWithWildcard));
        assertFalse(verify("sub.domain.example.net", certificateWithWildcard));
        assertFalse(verify("example.com", certificateWithWildcard));
    }
}