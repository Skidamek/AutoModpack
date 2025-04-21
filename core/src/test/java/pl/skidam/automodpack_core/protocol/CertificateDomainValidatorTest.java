package pl.skidam.automodpack_core.protocol;

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

import static org.junit.jupiter.api.Assertions.*;

class CertificateDomainValidatorTest {

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

    @Test
    void testNullOrEmptyInputs() {
        assertFalse(CertificateDomainValidator.isDomainValidatedByCertificate(null, "example.com"));
        assertFalse(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithSAN, null));
        assertFalse(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithSAN, ""));
    }

    @Test
    void testDomainValidationWithSAN() {
        assertTrue(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithSAN, "example.com"));
        assertTrue(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithSAN, "www.example.com"));
        assertTrue(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithSAN, "api.example.com"));
        assertFalse(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithSAN, "other.example.com"));
        assertFalse(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithSAN, "example.org"));
        assertFalse(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithSAN, "www.example.org"));
    }

    @Test
    void testDomainValidationWithCNOnly() {
        assertTrue(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithCNOnly, "example.org"));
        assertFalse(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithCNOnly, "www.example.org"));
        assertFalse(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithCNOnly, "example.com"));
    }

    @Test
    void testDomainValidationWithWildcard() {
        assertFalse(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithWildcard, "example.net"));
        assertTrue(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithWildcard, "www.example.net"));
        assertTrue(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithWildcard, "api.example.net"));
        assertFalse(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithWildcard, "sub.domain.example.net"));
        assertFalse(CertificateDomainValidator.isDomainValidatedByCertificate(certificateWithWildcard, "example.com"));
    }
}