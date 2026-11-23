package com.documentplatform.integration.credential;

import com.documentplatform.outbound.ClientCertificate;
import com.documentplatform.integration.IntegrationCode;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.Key;
import java.security.KeyFactory;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.UnrecoverableKeyException;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads a client certificate with its private key: an uploaded PKCS#12 ({@code .p12}, {@code .pfx}) or PEM file
 * (certificates and an unencrypted PKCS#8 {@code PRIVATE KEY}), or an entry of a keystore file (PKCS#12 or JKS). The
 * key must belong to the first certificate. Failures are codes ({@code INT_CREDENTIAL_*}); no exception text ever
 * holds the file, the key or the password.
 */
public final class CredentialFiles {

    /** The largest uploaded file. */
    public static final int MAX_BYTES = 32 * 1024;

    /** Why a file could not be read. */
    public static final class Unusable extends Exception implements com.documentplatform.errors.HasPlatformCode {
        private final IntegrationCode code;

        Unusable(IntegrationCode code) {
            super(code.name(), null, false, false);
            this.code = code;
        }

        public IntegrationCode code() {
            return code;
        }

        /** The code, for monitoring and the app's error handler ({@code HasPlatformCode}). */
        @Override
        public com.documentplatform.errors.PlatformCode platformCode() {
            return code;
        }
    }

    private static final Pattern CERT = Pattern.compile(
            "-----BEGIN CERTIFICATE-----([A-Za-z0-9+/=\\s]+)-----END CERTIFICATE-----");
    private static final Pattern KEY = Pattern.compile(
            "-----BEGIN PRIVATE KEY-----([A-Za-z0-9+/=\\s]+)-----END PRIVATE KEY-----");

    private CredentialFiles() {
    }

    /**
     * An uploaded file.
     *
     * @param password the PKCS#12 file's password (empty or null for none; not used for PEM)
     */
    public static ClientCertificate read(byte[] file, char[] password) throws Unusable {
        if (file == null || file.length == 0 || file.length > MAX_BYTES) {
            throw new Unusable(IntegrationCode.INT_CREDENTIAL_FILE_INVALID);
        }
        String start = new String(file, 0, Math.min(file.length, 64), StandardCharsets.US_ASCII).strip();
        return start.startsWith("-----BEGIN") ? pem(new String(file, StandardCharsets.US_ASCII))
                : pkcs12(file, password);
    }

    /** An entry of a keystore file the customer manages. */
    public static ClientCertificate keystore(Path file, char[] password, String alias) throws Unusable {
        KeyStore ks;
        try {
            if (!Files.isRegularFile(file) || !Files.isReadable(file)) {
                throw new Unusable(IntegrationCode.INT_CREDENTIAL_KEYSTORE_UNREADABLE);
            }
            ks = KeyStore.getInstance(file.toFile(), password);
        } catch (Unusable u) {
            throw u;
        } catch (IOException e) {
            throw new Unusable(passwordProblem(e) ? IntegrationCode.INT_CREDENTIAL_PASSWORD_WRONG
                    : IntegrationCode.INT_CREDENTIAL_KEYSTORE_UNREADABLE);
        } catch (GeneralSecurityException | IllegalArgumentException | SecurityException e) {
            throw new Unusable(IntegrationCode.INT_CREDENTIAL_KEYSTORE_UNREADABLE);
        }
        return entry(ks, alias, password, IntegrationCode.INT_CREDENTIAL_ALIAS_NOT_FOUND);
    }

    private static ClientCertificate pkcs12(byte[] file, char[] password) throws Unusable {
        KeyStore ks;
        char[] pw = password == null ? new char[0] : password;
        try (InputStream in = new ByteArrayInputStream(file)) {
            ks = KeyStore.getInstance("PKCS12");
            ks.load(in, pw);
        } catch (IOException e) {
            throw new Unusable(passwordProblem(e) ? IntegrationCode.INT_CREDENTIAL_PASSWORD_WRONG
                    : IntegrationCode.INT_CREDENTIAL_FILE_INVALID);
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new Unusable(IntegrationCode.INT_CREDENTIAL_FILE_INVALID);
        }
        String alias = null;
        try {
            for (String a : Collections.list(ks.aliases())) {
                if (ks.isKeyEntry(a)) {
                    if (alias != null) throw new Unusable(IntegrationCode.INT_CREDENTIAL_FILE_INVALID); // more than one
                    alias = a;
                }
            }
        } catch (java.security.KeyStoreException e) {
            throw new Unusable(IntegrationCode.INT_CREDENTIAL_FILE_INVALID);
        }
        if (alias == null) throw new Unusable(IntegrationCode.INT_CREDENTIAL_FILE_INVALID);
        return entry(ks, alias, pw, IntegrationCode.INT_CREDENTIAL_FILE_INVALID);
    }

    private static ClientCertificate entry(KeyStore ks, String alias, char[] password, IntegrationCode noEntry)
            throws Unusable {
        Key key;
        Certificate[] chain;
        try {
            if (!ks.isKeyEntry(alias)) throw new Unusable(noEntry);
            key = ks.getKey(alias, password);
            chain = ks.getCertificateChain(alias);
        } catch (UnrecoverableKeyException e) {
            throw new Unusable(IntegrationCode.INT_CREDENTIAL_PASSWORD_WRONG);
        } catch (GeneralSecurityException e) {
            throw new Unusable(IntegrationCode.INT_CREDENTIAL_FILE_INVALID);
        }
        if (!(key instanceof PrivateKey pk) || chain == null || chain.length == 0) throw new Unusable(noEntry);
        List<X509Certificate> certs = new ArrayList<>();
        for (Certificate c : chain) {
            if (!(c instanceof X509Certificate x)) throw new Unusable(IntegrationCode.INT_CREDENTIAL_FILE_INVALID);
            certs.add(x);
        }
        return of(pk, certs);
    }

    private static ClientCertificate pem(String text) throws Unusable {
        if (text.contains("ENCRYPTED PRIVATE KEY")) throw new Unusable(IntegrationCode.INT_CREDENTIAL_PASSWORD_WRONG);
        List<X509Certificate> certs = new ArrayList<>();
        try {
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Matcher c = CERT.matcher(text);
            while (c.find()) {
                certs.add((X509Certificate) cf.generateCertificate(
                        new ByteArrayInputStream(Base64.getMimeDecoder().decode(c.group(1)))));
            }
        } catch (GeneralSecurityException | IllegalArgumentException | ClassCastException e) {
            throw new Unusable(IntegrationCode.INT_CREDENTIAL_FILE_INVALID);
        }
        Matcher k = KEY.matcher(text);
        if (certs.isEmpty() || !k.find()) throw new Unusable(IntegrationCode.INT_CREDENTIAL_FILE_INVALID);
        byte[] der;
        try {
            der = Base64.getMimeDecoder().decode(k.group(1));
        } catch (IllegalArgumentException e) {
            throw new Unusable(IntegrationCode.INT_CREDENTIAL_FILE_INVALID);
        }
        PrivateKey key = null;
        for (String alg : List.of("RSA", "EC", "Ed25519", "Ed448")) {
            try {
                key = KeyFactory.getInstance(alg).generatePrivate(new PKCS8EncodedKeySpec(der));
                break;
            } catch (GeneralSecurityException | RuntimeException e) {
                // not this algorithm
            }
        }
        if (key == null) throw new Unusable(IntegrationCode.INT_CREDENTIAL_FILE_INVALID);
        // The client's own certificate is the one the key belongs to; it goes first.
        for (int i = 0; i < certs.size(); i++) {
            if (ClientCertificate.matches(key, certs.get(i).getPublicKey())) {
                if (i > 0) certs.add(0, certs.remove(i));
                return ClientCertificate.of(key, certs);
            }
        }
        throw new Unusable(IntegrationCode.INT_CREDENTIAL_KEY_MISMATCH);
    }

    private static ClientCertificate of(PrivateKey key, List<X509Certificate> chain) throws Unusable {
        try {
            return ClientCertificate.of(key, chain);
        } catch (IllegalArgumentException e) {
            throw new Unusable(IntegrationCode.INT_CREDENTIAL_KEY_MISMATCH);
        }
    }

    /** The stored form of a certificate: the chain, then the private key, as PEM (sealed before it is stored). */
    static String toPem(ClientCertificate c) {
        StringBuilder sb = new StringBuilder();
        try {
            for (X509Certificate x : c.chain()) sb.append(block("CERTIFICATE", x.getEncoded()));
        } catch (java.security.cert.CertificateEncodingException e) {
            throw new IllegalStateException("certificate could not be encoded");
        }
        sb.append(block("PRIVATE KEY", c.key().getEncoded()));
        return sb.toString();
    }

    /** Reads {@link #toPem}. */
    static ClientCertificate fromPem(String pem) throws Unusable {
        return pem(pem);
    }

    private static String block(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, new byte[]{'\n'}).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static boolean passwordProblem(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof UnrecoverableKeyException) return true;
            if (t instanceof javax.crypto.BadPaddingException) return true;
        }
        String m = e.getMessage();
        return m != null && (m.contains("password") || m.contains("Integrity check failed"));
    }
}
