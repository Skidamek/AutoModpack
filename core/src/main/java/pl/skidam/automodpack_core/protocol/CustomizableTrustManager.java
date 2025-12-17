package pl.skidam.automodpack_core.protocol;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509ExtendedTrustManager;
import java.net.Socket;
import java.security.KeyStore;
import java.security.KeyStoreException;
import java.security.NoSuchAlgorithmException;
import java.security.cert.CertificateException;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;

public class CustomizableTrustManager extends X509ExtendedTrustManager {

    private final X509ExtendedTrustManager defaultTrustManager;
    private final X509ExtendedTrustManager customTrustManager;
    private final Consumer<X509Certificate[]> onValidating;
    private final X509Certificate[] cachedAcceptedIssuers;

    public CustomizableTrustManager(KeyStore customStore, Consumer<X509Certificate[]> onValidating) throws KeyStoreException {
        this.defaultTrustManager = createTrustManager(null);
        this.customTrustManager = (customStore != null && customStore.size() > 0) ? createTrustManager(customStore) : null;
        this.onValidating = onValidating;

        // Pre-calculate merged issuers to avoid overhead on every handshake
        List<X509Certificate> issuers = new ArrayList<>(Arrays.asList(defaultTrustManager.getAcceptedIssuers()));
        if (this.customTrustManager != null) {
            issuers.addAll(Arrays.asList(this.customTrustManager.getAcceptedIssuers()));
        }
        this.cachedAcceptedIssuers = issuers.toArray(new X509Certificate[0]);
    }

    /**
     * Factory helper to reduce code duplication when initializing trust managers.
     */
    private static X509ExtendedTrustManager createTrustManager(KeyStore keyStore) throws KeyStoreException {
        try {
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(keyStore);
            for (TrustManager tm : tmf.getTrustManagers()) {
                if (tm instanceof X509ExtendedTrustManager) {
                    return (X509ExtendedTrustManager) tm;
                }
            }
            throw new IllegalStateException("No X509ExtendedTrustManager found");
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Default algorithm unavailable", e);
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return cachedAcceptedIssuers;
    }

    // --- Server Trust Checks (Client-Side Logic) ---

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (onValidating != null) onValidating.accept(chain);
        try {
            defaultTrustManager.checkServerTrusted(chain, authType);
        } catch (CertificateException e) {
            if (customTrustManager != null) {
                customTrustManager.checkServerTrusted(chain, authType);
            } else {
                throw e;
            }
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        if (onValidating != null) onValidating.accept(chain);
        try {
            // Primary check: Includes standard PKIX path validation AND Hostname Verification (if Endpoint ID is HTTPS)
            defaultTrustManager.checkServerTrusted(chain, authType, socket);
        } catch (CertificateException e) {
            // Fallback: If user explicitly trusted this cert, verify the signature but BYPASS Hostname Verification.
            // We intentionally drop the 'socket' parameter here to allow connecting to IPs that don't match the Cert CN.
            if (customTrustManager != null) {
                customTrustManager.checkServerTrusted(chain, authType);
            } else {
                throw e;
            }
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        if (onValidating != null) onValidating.accept(chain);
        try {
            defaultTrustManager.checkServerTrusted(chain, authType, engine);
        } catch (CertificateException e) {
            // Fallback: Bypass Hostname Verification for custom store (see Socket overload)
            if (customTrustManager != null) {
                customTrustManager.checkServerTrusted(chain, authType);
            } else {
                throw e;
            }
        }
    }

    // --- Client Trust Checks (Server-Side Logic - unused in this context) ---

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        if (onValidating != null) onValidating.accept(chain);
        try {
            defaultTrustManager.checkClientTrusted(chain, authType);
        } catch (CertificateException e) {
            if (customTrustManager != null) {
                customTrustManager.checkClientTrusted(chain, authType);
            } else {
                throw e;
            }
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket socket) throws CertificateException {
        if (onValidating != null) onValidating.accept(chain);
        try {
            defaultTrustManager.checkClientTrusted(chain, authType, socket);
        } catch (CertificateException e) {
            if (customTrustManager != null) {
                customTrustManager.checkClientTrusted(chain, authType);
            } else {
                throw e;
            }
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine engine) throws CertificateException {
        if (onValidating != null) onValidating.accept(chain);
        try {
            defaultTrustManager.checkClientTrusted(chain, authType, engine);
        } catch (CertificateException e) {
            if (customTrustManager != null) {
                customTrustManager.checkClientTrusted(chain, authType);
            } else {
                throw e;
            }
        }
    }
}