package com.documentplatform.integration.testkit;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Throwaway certificates for tests, made with the JDK's keytool (the JDK has no public API to make an X.509
 * certificate): a private certificate authority, a server certificate it signs (for the https mock destination), and
 * a self-signed client credential (certificate + private key PEM, for the client assertion). Nothing here is a real key.
 */
public final class TestCertificates {

    private static final String PASS = "changeit";

    /** A certificate authority: its certificate (and PEM) and its keystore. */
    public record Ca(X509Certificate certificate, String pem, Path keystore, String alias) {
    }

    /** A server certificate signed by a CA, ready for {@link MockDestination#startHttps}. */
    public record Server(SSLContext context, X509Certificate certificate) {
    }

    /** A certificate and its private key, and the PEM text a secret store would hold. */
    public record Credential(X509Certificate certificate, PrivateKey key, String pem) {
        /** The base64 of the private key, to check it appears in no output. */
        public String keyBase64() {
            return Base64.getEncoder().encodeToString(key.getEncoded());
        }
    }

    private TestCertificates() {
    }

    public static Ca ca(Path dir, String name) throws Exception {
        Path ks = dir.resolve(name + ".p12");
        keytool("-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + name,
                "-validity", "30", "-ext", "bc:c", "-storetype", "PKCS12", "-keystore", ks.toString(),
                "-storepass", PASS, "-keypass", PASS);
        KeyStore store = load(ks);
        X509Certificate cert = (X509Certificate) store.getCertificate(name);
        return new Ca(cert, pem("CERTIFICATE", cert.getEncoded()), ks, name);
    }

    /**
     * A server certificate signed by {@code ca}.
     *
     * @param san the subject alternative names, keytool style, for example {@code dns:localhost} or
     *            {@code dns:localhost,ip:127.0.0.1}
     */
    public static Server server(Path dir, Ca ca, String name, String san) throws Exception {
        Path ks = dir.resolve(name + ".p12");
        Path csr = dir.resolve(name + ".csr");
        Path crt = dir.resolve(name + ".crt");
        Path caCrt = dir.resolve(ca.alias() + ".crt");
        keytool("-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + name,
                "-validity", "30", "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", PASS,
                "-keypass", PASS);
        keytool("-certreq", "-alias", name, "-keystore", ks.toString(), "-storepass", PASS, "-file", csr.toString());
        keytool("-gencert", "-alias", ca.alias(), "-keystore", ca.keystore().toString(), "-storepass", PASS,
                "-infile", csr.toString(), "-outfile", crt.toString(), "-validity", "30", "-ext", "san=" + san,
                "-ext", "eku=serverAuth", "-rfc");
        Files.writeString(caCrt, ca.pem());
        keytool("-importcert", "-noprompt", "-alias", ca.alias(), "-file", caCrt.toString(), "-keystore",
                ks.toString(), "-storepass", PASS);
        keytool("-importcert", "-noprompt", "-alias", name, "-file", crt.toString(), "-keystore", ks.toString(),
                "-storepass", PASS);
        KeyStore store = load(ks);
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, PASS.toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, null);
        return new Server(ctx, (X509Certificate) store.getCertificate(name));
    }

    /** A self-signed client credential (RSA), valid from {@code startDays} for {@code validDays}. */
    public static Credential credential(Path dir, String name, int startDays, int validDays) throws Exception {
        Path ks = dir.resolve(name + ".p12");
        keytool("-genkeypair", "-alias", name, "-keyalg", "RSA", "-keysize", "2048", "-dname", "CN=" + name,
                "-startdate", (startDays >= 0 ? "+" : "") + startDays + "d", "-validity", Integer.toString(validDays),
                "-storetype", "PKCS12", "-keystore", ks.toString(), "-storepass", PASS, "-keypass", PASS);
        KeyStore store = load(ks);
        X509Certificate cert = (X509Certificate) store.getCertificate(name);
        PrivateKey key = (PrivateKey) store.getKey(name, PASS.toCharArray());
        return new Credential(cert, key, pem("CERTIFICATE", cert.getEncoded()) + pem("PRIVATE KEY", key.getEncoded()));
    }

    /**
     * A server certificate signed by {@code ca} whose server <b>requires a client certificate</b> and trusts only
     * {@code trustedClients} (self-signed client credentials, or CAs): for {@link MockDestination#startMutualTls}.
     */
    public static Server serverTrusting(Path dir, Ca ca, String name, String san, X509Certificate... trustedClients)
            throws Exception {
        Server plain = server(dir, ca, name, san);
        KeyStore store = load(dir.resolve(name + ".p12"));
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, PASS.toCharArray());
        KeyStore trust = KeyStore.getInstance("PKCS12");
        trust.load(null, null);
        int n = 0;
        for (X509Certificate c : trustedClients) trust.setCertificateEntry("client-" + n++, c);
        javax.net.ssl.TrustManagerFactory tmf = javax.net.ssl.TrustManagerFactory.getInstance(
                javax.net.ssl.TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(trust);
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), tmf.getTrustManagers(), null);
        return new Server(ctx, plain.certificate());
    }

    /** The credential as a PKCS#12 file (an upload), protected by {@code password}. */
    public static byte[] pkcs12(Credential c, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry("client", c.key(), password.toCharArray(), new java.security.cert.Certificate[]{c.certificate()});
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        ks.store(out, password.toCharArray());
        return out.toByteArray();
    }

    /** The credential as a PKCS#12 keystore file (a customer-managed keystore) at {@code file}, entry {@code alias}. */
    public static Path keystore(Credential c, Path file, String alias, String password) throws Exception {
        KeyStore ks = KeyStore.getInstance("PKCS12");
        ks.load(null, null);
        ks.setKeyEntry(alias, c.key(), password.toCharArray(), new java.security.cert.Certificate[]{c.certificate()});
        try (java.io.OutputStream out = Files.newOutputStream(file)) {
            ks.store(out, password.toCharArray());
        }
        return file;
    }

    public static String pem(String type, byte[] der) {
        return "-----BEGIN " + type + "-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(der)
                + "\n-----END " + type + "-----\n";
    }

    private static KeyStore load(Path ks) throws Exception {
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(ks)) {
            store.load(in, PASS.toCharArray());
        }
        return store;
    }

    private static void keytool(String... args) throws Exception {
        List<String> cmd = new ArrayList<>();
        cmd.add(Path.of(System.getProperty("java.home"), "bin", "keytool").toString());
        cmd.addAll(List.of(args));
        Process p = new ProcessBuilder(cmd).redirectErrorStream(true).start();
        byte[] output = p.getInputStream().readAllBytes();
        if (!p.waitFor(60, TimeUnit.SECONDS) || p.exitValue() != 0) {
            throw new IOException("keytool failed: " + new String(output));
        }
    }
}
