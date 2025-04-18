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
import java.util.function.Consumer;

/**
 * An implementation of {@link X509ExtendedTrustManager} that uses a trusts all certificates in a provided
 * {@link KeyStore}, and verifies other certificates using the default key store. In order to enable recovering the
 * peer certificate chain from a failed handshake, a callback is called before each verification process.
 */
class CustomizableTrustManager extends X509ExtendedTrustManager {
    private final X509ExtendedTrustManager defaultTrustManager;
    private final X509ExtendedTrustManager trustedCertificatesTrustManager;
    private final Consumer<X509Certificate[]> onValidating;

    /**
     * Creates a new {@link CustomizableTrustManager} that trusts all certificates in the provided {@link KeyStore},
     * and verifies other certificates using the default key store.
     *
     * @param trustedCertificates a {@link KeyStore} containing certificates to be trusted
     * @param onValidating        a callback that is run for every certificate chain before verification
     * @throws KeyStoreException if the provided {@link KeyStore} could not be used to initialize a {@link TrustManager}
     */
    public CustomizableTrustManager(KeyStore trustedCertificates, Consumer<X509Certificate[]> onValidating) throws KeyStoreException {
        TrustManagerFactory trustManagerFactory;
        try {
            trustManagerFactory = TrustManagerFactory
                    .getInstance(TrustManagerFactory.getDefaultAlgorithm());
        } catch (NoSuchAlgorithmException e) {
            throw new RuntimeException("Failed to create a TrustManagerFactory with the default algorithm.", e);
        }
        try {
            trustManagerFactory.init((KeyStore) null);
        } catch (KeyStoreException e) {
            throw new RuntimeException("Failed to initialize a TrustManagerFactory.", e);
        }

        this.defaultTrustManager = getX509ExtendedTrustManager(trustManagerFactory);
        X509ExtendedTrustManager trustedCertificatesTrustManager = null;
        if (trustedCertificates.size() > 0) {
            try {
                trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            } catch (NoSuchAlgorithmException e) {
                throw new RuntimeException("Failed to create a TrustManagerFactory with the default algorithm.", e);
            }
            trustManagerFactory.init(trustedCertificates);

            trustedCertificatesTrustManager = getX509ExtendedTrustManager(trustManagerFactory);
        }
        this.trustedCertificatesTrustManager = trustedCertificatesTrustManager;
        this.onValidating = onValidating;
    }

    private static X509ExtendedTrustManager getX509ExtendedTrustManager(TrustManagerFactory trustManagerFactory) {
        X509ExtendedTrustManager trustedCertificatesTrustManager = null;
        for (TrustManager trustManager : trustManagerFactory.getTrustManagers()) {
            if (trustManager instanceof X509ExtendedTrustManager) {
                trustedCertificatesTrustManager = (X509ExtendedTrustManager) trustManager;
                break;
            }
        }
        if (trustedCertificatesTrustManager == null) {
            throw new RuntimeException("Failed to create an extended trust manager.");
        }
        return trustedCertificatesTrustManager;
    }

    private X509Certificate[] mergeCertificates() {
        ArrayList<X509Certificate> resultingCerts
                = new ArrayList<>(Arrays.asList(defaultTrustManager.getAcceptedIssuers()));
        if (trustedCertificatesTrustManager != null) {
            resultingCerts.addAll(Arrays.asList(trustedCertificatesTrustManager.getAcceptedIssuers()));
        }
        return resultingCerts.toArray(new X509Certificate[0]);
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        onValidating.accept(chain);
        try {
            defaultTrustManager.checkClientTrusted(chain, authType);
        } catch (CertificateException e) {
            if (trustedCertificatesTrustManager != null) {
                trustedCertificatesTrustManager.checkClientTrusted(chain, authType);
            } else {
                throw e;
            }
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType) throws CertificateException {
        onValidating.accept(chain);
        try {
            defaultTrustManager.checkServerTrusted(chain, authType);
        } catch (CertificateException e) {
            if (trustedCertificatesTrustManager != null) {
                trustedCertificatesTrustManager.checkServerTrusted(chain, authType);
            } else {
                throw e;
            }
        }
    }

    @Override
    public X509Certificate[] getAcceptedIssuers() {
        return mergeCertificates();
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, Socket checkedSocket)
            throws CertificateException {
        try {
            defaultTrustManager.checkClientTrusted(chain, authType, checkedSocket);
        } catch (CertificateException e) {
            if (trustedCertificatesTrustManager != null) {
                // Skip address check if certificate is trusted
                trustedCertificatesTrustManager.checkClientTrusted(chain, authType);
            } else {
                throw e;
            }
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, Socket checkedSocket)
            throws CertificateException {
        onValidating.accept(chain);
        try {
            defaultTrustManager.checkServerTrusted(chain, authType, checkedSocket);
        } catch (CertificateException e) {
            if (trustedCertificatesTrustManager != null) {
                // Skip address check if certificate is trusted
                trustedCertificatesTrustManager.checkServerTrusted(chain, authType);
            } else {
                throw e;
            }
        }
    }

    @Override
    public void checkClientTrusted(X509Certificate[] chain, String authType, SSLEngine sslEngine)
            throws CertificateException {
        try {
            defaultTrustManager.checkClientTrusted(chain, authType, sslEngine);
        } catch (CertificateException e) {
            if (trustedCertificatesTrustManager != null) {
                // Skip address check if certificate is trusted
                trustedCertificatesTrustManager.checkClientTrusted(chain, authType);
            } else {
                throw e;
            }
        }
    }

    @Override
    public void checkServerTrusted(X509Certificate[] chain, String authType, SSLEngine sslEngine)
            throws CertificateException {
        onValidating.accept(chain);
        try {
            defaultTrustManager.checkServerTrusted(chain, authType, sslEngine);
        } catch (CertificateException e) {
            if (trustedCertificatesTrustManager != null) {
                // Skip address check if certificate is trusted
                trustedCertificatesTrustManager.checkServerTrusted(chain, authType);
            } else {
                throw e;
            }
        }
    }
}
