package com.documentplatform.outbound;

import javax.net.ssl.SSLEngine;
import javax.net.ssl.X509ExtendedKeyManager;
import javax.net.ssl.X509KeyManager;
import java.net.Socket;
import java.security.GeneralSecurityException;
import java.security.Principal;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.SecureRandom;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.util.List;
import java.util.Objects;

/**
 * A client certificate with its private key, ready for a TLS connection (mutual TLS). Made only from a checked
 * credential: the key matches the certificate. Held in memory for the calls of one attempt (and in the HTTP client
 * of that credential); never logged, never put in a result. {@link #toString()} shows only the fingerprint.
 */
public final class ClientCertificate {

    static final String ALIAS = "client";

    private final PrivateKey key;
    private final X509Certificate[] chain;
    private final CertificateInfo info;

    private ClientCertificate(PrivateKey key, X509Certificate[] chain) {
        this.key = key;
        this.chain = chain;
        this.info = CertificateInfo.of(chain[0]);
    }

    /**
     * @param chain the client's certificate first, then any intermediates
     * @throws IllegalArgumentException when the key does not belong to the first certificate
     */
    public static ClientCertificate of(PrivateKey key, List<X509Certificate> chain) {
        Objects.requireNonNull(key, "key");
        if (chain == null || chain.isEmpty()) throw new IllegalArgumentException("A certificate is needed");
        if (!matches(key, chain.get(0).getPublicKey())) {
            throw new IllegalArgumentException("The private key does not belong to the certificate");
        }
        return new ClientCertificate(key, chain.toArray(X509Certificate[]::new));
    }

    /** Whether the private key belongs to the public key: a random challenge signed and verified. */
    public static boolean matches(PrivateKey key, PublicKey pub) {
        String alg = switch (key.getAlgorithm()) {
            case "RSA" -> "SHA256withRSA";
            case "EC" -> "SHA256withECDSA";
            case "Ed25519", "Ed448", "EdDSA" -> key.getAlgorithm();
            default -> null;
        };
        if (alg == null || !key.getAlgorithm().equals(pub.getAlgorithm()) && !(alg.startsWith("Ed")
                && pub.getAlgorithm().startsWith("Ed"))) {
            return false;
        }
        try {
            byte[] challenge = new byte[32];
            new SecureRandom().nextBytes(challenge);
            Signature s = Signature.getInstance(alg);
            s.initSign(key);
            s.update(challenge);
            byte[] signature = s.sign();
            Signature v = Signature.getInstance(alg);
            v.initVerify(pub);
            v.update(challenge);
            return v.verify(signature);
        } catch (GeneralSecurityException | RuntimeException e) {
            return false;
        }
    }

    public CertificateInfo info() {
        return info;
    }

    /**
     * For the stored form only: the integrations seal it under the customer's keys before it is stored
     * ({@code credential.CredentialFiles}). Never logged, never put in a result.
     */
    public PrivateKey key() {
        return key;
    }

    /** The certificate chain, the client's certificate first. */
    public X509Certificate[] chain() {
        return chain.clone();
    }

    public String fingerprint() {
        return info.sha256();
    }

    /**
     * A key manager that always offers this certificate, whatever authorities the server names: a server that does
     * not trust it then refuses it (a TLS alert, {@code OUT_CREDENTIAL_REFUSED}) instead of the client sending none.
     */
    public X509KeyManager keyManager() {
        return new X509ExtendedKeyManager() {
            @Override
            public String[] getClientAliases(String keyType, Principal[] issuers) {
                return new String[]{ALIAS};
            }

            @Override
            public String chooseClientAlias(String[] keyType, Principal[] issuers, Socket socket) {
                return ALIAS;
            }

            @Override
            public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, SSLEngine engine) {
                return ALIAS;
            }

            @Override
            public String[] getServerAliases(String keyType, Principal[] issuers) {
                return null;
            }

            @Override
            public String chooseServerAlias(String keyType, Principal[] issuers, Socket socket) {
                return null;
            }

            @Override
            public X509Certificate[] getCertificateChain(String alias) {
                return ALIAS.equals(alias) ? chain.clone() : null;
            }

            @Override
            public PrivateKey getPrivateKey(String alias) {
                return ALIAS.equals(alias) ? key : null;
            }
        };
    }

    @Override
    public String toString() {
        return "ClientCertificate[" + info.sha256() + "]";
    }
}
