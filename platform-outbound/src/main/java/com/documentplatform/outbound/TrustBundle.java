package com.documentplatform.outbound;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import java.io.IOException;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.List;

/**
 * The TLS trust for outbound calls: the system's certificate authorities plus, when set, the customer's private ones
 * (added, never replacing the system's). Host names are always checked by the HTTP client; there is no "trust all".
 */
public final class TrustBundle {

    private TrustBundle() {
    }

    /** An SSL context trusting the system's CAs and {@code extra}. With no extra CAs, the system default. */
    public static SSLContext context(List<X509Certificate> extra) {
        try {
            if (extra.isEmpty()) {
                return SSLContext.getDefault();
            }
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, new javax.net.ssl.TrustManager[]{trustManager(extra)}, null);
            return ctx;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("TLS trust could not be set up", e);
        }
    }

    /** The trust manager behind {@link #context}: the system's CAs and {@code extra}. */
    public static X509TrustManager trustManager(List<X509Certificate> extra) {
        try {
            TrustManagerFactory system = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            system.init((KeyStore) null);
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int n = 0;
            for (var tm : system.getTrustManagers()) {
                if (tm instanceof X509TrustManager x) {
                    for (X509Certificate c : x.getAcceptedIssuers()) {
                        ks.setCertificateEntry("system-" + n++, c);
                    }
                }
            }
            for (X509Certificate c : extra) {
                ks.setCertificateEntry("private-" + n++, c);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            for (var tm : tmf.getTrustManagers()) {
                if (tm instanceof X509TrustManager x) return x;
            }
            throw new IllegalStateException("No X.509 trust manager");
        } catch (GeneralSecurityException | IOException e) {
            throw new IllegalStateException("TLS trust could not be set up", e);
        }
    }
}
