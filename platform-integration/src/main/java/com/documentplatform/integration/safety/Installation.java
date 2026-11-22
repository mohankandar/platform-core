package com.documentplatform.integration.safety;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.security.cert.CertificateException;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.List;
import java.util.Base64;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Settings of the whole installation that every integration runs under (from the app's configuration; Spring
 * properties come in a later task).
 *
 * @param hosted                 our hosted service: internal addresses and sign-in NONE are never allowed
 * @param allowInternalAddresses on premises only: loopback and private addresses (and plain http) may be called.
 *                               Cloud metadata and link-local addresses are always refused.
 * @param allowSignInNone        on premises only: sign-in NONE may be configured (warned and audited); off by default
 * @param proxy                  the outbound proxy, or null for direct connections
 * @param privateCaPem           the customer's private certificate authorities (PEM, one or more certificates),
 *                               trusted in addition to the system's; or null. There is no "trust all" switch.
 */
public record Installation(boolean hosted, boolean allowInternalAddresses, boolean allowSignInNone,
                           ProxySettings proxy, String privateCaPem) {

    private static final Pattern CERT = Pattern.compile(
            "-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----");

    public Installation {
        if (hosted && allowInternalAddresses) {
            throw new IllegalArgumentException("Internal addresses are never allowed on the hosted service");
        }
        if (hosted && allowSignInNone) {
            throw new IllegalArgumentException("Sign-in NONE is never allowed on the hosted service");
        }
        if (privateCaPem != null) {
            privateCaCertificates(privateCaPem); // refuses a bundle that cannot be read
        }
    }

    /** Hosted: strict. */
    public static Installation hostedService() {
        return new Installation(true, false, false, null, null);
    }

    /** On premises with the defaults: internal addresses and sign-in NONE off. */
    public static Installation onPremises() {
        return new Installation(false, false, false, null, null);
    }

    public Installation withInternalAddresses(boolean allow) {
        return new Installation(hosted, allow, allowSignInNone, proxy, privateCaPem);
    }

    public Installation withSignInNone(boolean allow) {
        return new Installation(hosted, allowInternalAddresses, allow, proxy, privateCaPem);
    }

    public Installation withProxy(ProxySettings p) {
        return new Installation(hosted, allowInternalAddresses, allowSignInNone, p, privateCaPem);
    }

    public Installation withPrivateCa(String pem) {
        return new Installation(hosted, allowInternalAddresses, allowSignInNone, proxy, pem);
    }

    /** Plain http is allowed only where internal addresses are. */
    public boolean allowPlainHttp() {
        return allowInternalAddresses;
    }

    public List<X509Certificate> privateCaCertificates() {
        return privateCaPem == null ? List.of() : privateCaCertificates(privateCaPem);
    }

    private static List<X509Certificate> privateCaCertificates(String pem) {
        List<X509Certificate> certs = new ArrayList<>();
        try {
            CertificateFactory f = CertificateFactory.getInstance("X.509");
            Matcher m = CERT.matcher(pem);
            while (m.find()) {
                certs.add((X509Certificate) f.generateCertificate(new ByteArrayInputStream(
                        Base64.getMimeDecoder().decode(m.group(1).getBytes(StandardCharsets.US_ASCII)))));
            }
        } catch (CertificateException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Private certificate authority bundle is not valid");
        }
        if (certs.isEmpty()) {
            throw new IllegalArgumentException("Private certificate authority bundle holds no certificate");
        }
        return List.copyOf(certs);
    }

    @Override
    public String toString() {
        return "Installation[hosted=" + hosted + ", internal=" + allowInternalAddresses + ", none=" + allowSignInNone
                + ", proxy=" + (proxy == null ? "none" : proxy.host() + ":" + proxy.port()) + ", privateCa="
                + (privateCaPem != null) + "]";
    }
}
