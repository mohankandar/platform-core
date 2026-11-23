package com.documentplatform.licensing.issue;

import com.documentplatform.licensing.json.Json;
import com.sun.net.httpserver.HttpServer;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The issuing command's Vault signer against a stub Vault (install-licence-signing-3): an https Vault whose
 * certificate this Java runtime does not trust is {@code ISSUE_VAULT_TLS}, HTTP 503 (sealed, not ready) is
 * {@code ISSUE_VAULT_SEALED}, HTTP 500 is {@code ISSUE_VAULT_ERROR}. Nothing is signed and no licence is written.
 */
class VaultTransitSignerStubTest {

    private static final String TOKEN = "hvs.STUB-TOKEN-NEVER-SHOWN-5c1a";

    @TempDir
    Path dir;
    private HttpServer server;
    private final List<String> calls = Collections.synchronizedList(new ArrayList<>());
    private final ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
    private final ByteArrayOutputStream errBytes = new ByteArrayOutputStream();

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Answers every request with {@code status} and a Vault-style error body. */
    private void answerAll(HttpServer s, int status) {
        s.createContext("/", exchange -> {
            exchange.getRequestBody().readAllBytes();
            calls.add(exchange.getRequestMethod() + " " + exchange.getRequestURI().getPath());
            byte[] body = ("{\"errors\":[\"stub answer " + status + "\"]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        s.start();
        server = s;
    }

    private HttpServer plain() throws IOException {
        return HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
    }

    private int run(String address) {
        Path out = dir.resolve("licence.json");
        List<String> args = List.of("--customer", "Example Customer Ltd", "--products", "PRODUCT_A",
                "--kind", "PRODUCTION", "--starts", "2026-12-01", "--expires", "2027-11-30", "--deployment", "ON_PREM",
                "--slot", "PRODUCT_A:PAGES_PER_MONTH=10000", "--key-id", "lic-2026:1",
                "--records", dir.resolve("issued.jsonl").toString(), "--out", out.toString());
        Map<String, String> env = Map.of("VAULT_ADDR", address, "VAULT_TOKEN", TOKEN);
        int exit = new IssueLicenceCommand(o -> VaultTransitSigner.from(o, env, user -> null),
                Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC), new SecureRandom())
                .run(args, new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                        new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        assertFalse(Files.exists(out), "no licence is written");
        return exit;
    }

    private String err() {
        return errBytes.toString(StandardCharsets.UTF_8);
    }

    private String lastRunLogCode() throws IOException {
        List<String> lines = Files.readAllLines(dir.resolve("issued.jsonl.runs.log"));
        return (String) Json.parseObject(lines.get(lines.size() - 1)).get("code");
    }

    @Test
    void anUntrustedCertificateIsTheTlsCode() throws Exception {
        HttpsServer https = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        https.setHttpsConfigurator(new HttpsConfigurator(selfSigned()));
        answerAll(https, 200);
        assertEquals(1, run("https://localhost:" + https.getAddress().getPort()));
        assertTrue(err().startsWith("ISSUE_VAULT_TLS: "), err());
        assertEquals("ISSUE_VAULT_TLS", lastRunLogCode());
        assertEquals(List.of(), calls, "no request got through the handshake");
        assertFalse(err().contains(TOKEN), err());
    }

    @Test
    void http503IsSealed() throws Exception {
        answerAll(plain(), 503);
        assertEquals(1, run("http://127.0.0.1:" + server.getAddress().getPort()));
        assertTrue(err().startsWith("ISSUE_VAULT_SEALED: "), err());
        assertTrue(err().contains("HTTP 503"), err());
        assertEquals("ISSUE_VAULT_SEALED", lastRunLogCode());
        assertFalse(calls.isEmpty());
        assertFalse(calls.stream().anyMatch(c -> c.contains("/sign/")), "nothing signed: " + calls);
    }

    @Test
    void http500IsAVaultError() throws Exception {
        answerAll(plain(), 500);
        assertEquals(1, run("http://127.0.0.1:" + server.getAddress().getPort()));
        assertTrue(err().startsWith("ISSUE_VAULT_ERROR: "), err());
        assertTrue(err().contains("HTTP 500"), err());
        assertEquals("ISSUE_VAULT_ERROR", lastRunLogCode());
        assertFalse(calls.stream().anyMatch(c -> c.contains("/sign/")), "nothing signed: " + calls);
    }

    /** A throwaway self-signed server certificate for localhost, made with the JDK's keytool. */
    private SSLContext selfSigned() throws Exception {
        Path store = dir.resolve("stub-vault.p12");
        String keytool = Path.of(System.getProperty("java.home"), "bin", "keytool").toString();
        Process p = new ProcessBuilder(keytool, "-genkeypair", "-alias", "vault", "-keyalg", "EC", "-groupname",
                "secp256r1", "-dname", "CN=localhost", "-ext", "SAN=dns:localhost,ip:127.0.0.1", "-validity", "2",
                "-storetype", "PKCS12", "-keystore", store.toString(), "-storepass", "changeit", "-keypass",
                "changeit").redirectErrorStream(true).start();
        byte[] output;
        try (InputStream in = p.getInputStream()) {
            output = in.readAllBytes();
        }
        if (p.waitFor() != 0) {
            throw new IOException("keytool failed: " + new String(output, StandardCharsets.UTF_8));
        }
        KeyStore ks = KeyStore.getInstance("PKCS12");
        try (InputStream in = Files.newInputStream(store)) {
            ks.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(ks, "changeit".toCharArray());
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(kmf.getKeyManagers(), null, new SecureRandom());
        return ctx;
    }
}
