package com.documentplatform.notifications.support;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Throwaway RSA certificates for the Microsoft 365 certificate credential, made with the JDK's keytool (the JDK has no
 * public API to make an X.509 certificate). Nothing here is a real key.
 */
public final class TestCertificates {

    /** A certificate and its private key, and the PEM text the secret store would hold. */
    public record Credential(X509Certificate certificate, PrivateKey key, String pem) {
        /** The base64 of the private key, to check it appears in no output. */
        public String keyBase64() {
            return Base64.getEncoder().encodeToString(key.getEncoded());
        }
    }

    private TestCertificates() {
    }

    /**
     * @param startDays when the certificate starts, in days from today (negative for the past)
     * @param validDays how many days it is valid
     */
    public static Credential make(Path dir, String name, int startDays, int validDays) throws Exception {
        Path store = dir.resolve(name + ".p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process p = new ProcessBuilder(List.of(keytool, "-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize",
                "2048", "-dname", "CN=" + name, "-startdate", (startDays >= 0 ? "+" : "") + startDays + "d",
                "-validity", Integer.toString(validDays), "-storetype", "PKCS12", "-keystore", store.toString(),
                "-storepass", "changeit", "-keypass", "changeit")).redirectErrorStream(true).start();
        byte[] output = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("keytool failed: " + new String(output));
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, "changeit".toCharArray());
        }
        X509Certificate cert = (X509Certificate) ks.getCertificate(name);
        PrivateKey key = (PrivateKey) ks.getKey(name, "changeit".toCharArray());
        return new Credential(cert, key, pem("CERTIFICATE", cert.getEncoded()) + pem("PRIVATE KEY", key.getEncoded()));
    }

    public static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }
}
