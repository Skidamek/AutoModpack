package pl.skidam.automodpack_core.protocol;

import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;
import javax.naming.NamingException;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.ldap.LdapName;
import javax.security.auth.x500.X500Principal;

/**
 * Utility class for validating domains against X.509 certificates.
 * This class provides functionality to check if a domain is validated by a certificate,
 * handling both Subject Alternative Names (SANs) and Common Name (CN) validation.
 */
public class CertificateDomainValidator {

    /**
     * Checks if the given domain is validated by the certificate.
     * This method verifies if the domain matches any of the Subject Alternative Names (SANs)
     * in the certificate, or falls back to the Common Name (CN) if no SANs are present.
     * It handles both exact matches and wildcard certificates (e.g., *.example.com).
     *
     * @param certificate the X.509 certificate to check
     * @param domain the domain to validate
     * @return true if the domain is validated by the certificate, false otherwise
     */
    public static boolean isDomainValidatedByCertificate(X509Certificate certificate, String domain) {
        if (certificate == null || domain == null || domain.isEmpty()) {
            return false;
        }

        // First check Subject Alternative Names (SANs)
        Collection<List<?>> subjectAltNames = getSubjectAltNames(certificate);
        if (subjectAltNames != null && !subjectAltNames.isEmpty()) {
            for (List<?> san : subjectAltNames) {
                if (san != null && san.size() >= 2) {
                    // Type 2 is DNS name
                    Integer type = (Integer) san.get(0);
                    if (type != null && type == 2) {
                        String dnsName = (String) san.get(1);
                        if (matchDomain(domain, dnsName)) {
                            return true;
                        }
                    }
                }
            }
        }

        // Fall back to Common Name (CN) in the Subject DN
        String cn = getCommonName(certificate);
        return cn != null && matchDomain(domain, cn);
    }

    /**
     * Extracts the Subject Alternative Names (SANs) from the certificate.
     *
     * @param certificate the X.509 certificate
     * @return a collection of SANs, or null if none are present
     */
    private static Collection<List<?>> getSubjectAltNames(X509Certificate certificate) {
        try {
            return certificate.getSubjectAlternativeNames();
        } catch (CertificateException e) {
            return null;
        }
    }

    /**
     * Extracts the Common Name (CN) from the certificate's subject.
     *
     * @param certificate the X.509 certificate
     * @return the Common Name, or null if not found
     */
    private static String getCommonName(X509Certificate certificate) {
        try {
            X500Principal principal = certificate.getSubjectX500Principal();
            LdapName ldapName = new LdapName(principal.getName());

            for (javax.naming.ldap.Rdn rdn : ldapName.getRdns()) {
                Attributes attributes = rdn.toAttributes();
                Attribute cn = attributes.get("CN");
                if (cn != null) {
                    return (String) cn.get();
                }
            }
        } catch (NamingException e) {
            // Ignore and return null
        }
        return null;
    }

    /**
     * Checks if the domain matches the pattern, handling wildcard certificates.
     *
     * @param domain the domain to check
     * @param pattern the pattern from the certificate (can be a wildcard)
     * @return true if the domain matches the pattern, false otherwise
     */
    private static boolean matchDomain(String domain, String pattern) {
        if (domain.equalsIgnoreCase(pattern)) {
            return true;
        }

        // Handle wildcard certificates
        if (pattern.startsWith("*.")) {
            // Convert wildcard to regex pattern
            String regexPattern = Pattern.quote(pattern.substring(2));
            return Pattern.compile("^[^.]+\\." + regexPattern + "$", Pattern.CASE_INSENSITIVE)
                    .matcher(domain)
                    .matches();
        }

        return false;
    }
}