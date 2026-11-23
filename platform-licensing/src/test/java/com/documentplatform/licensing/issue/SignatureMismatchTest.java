package com.documentplatform.licensing.issue;

import com.documentplatform.licensing.Ed25519;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * licensing-file-format-50: a Vault double publishes one key's public key but signs with another key. The signature
 * is checked against the published key before anything is written: {@code ISSUE_SIGNATURE_MISMATCH}, no licence file,
 * no issued record.
 */
class SignatureMismatchTest {

    @TempDir
    Path dir;

    private final KeyPair published = Ed25519.generate();
    private final KeyPair signing = Ed25519.generate();
    private HttpServer vault;

    @BeforeEach
    void start() throws IOException {
        byte[] x509 = published.getPublic().getEncoded();
        String raw = Base64.getEncoder().encodeToString(Arrays.copyOfRange(x509, x509.length - 32, x509.length));
        vault = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        vault.createContext("/v1/transit/keys/lic-2026", ex -> answer(ex, "{\"data\":{\"type\":\"ed25519\","
                + "\"exportable\":false,\"allow_plaintext_backup\":false,\"latest_version\":1,"
                + "\"keys\":{\"1\":{\"public_key\":\"" + raw + "\"}}}}"));
        vault.createContext("/v1/transit/sign/lic-2026", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String input = body.replaceAll("(?s).*\"input\"\\s*:\\s*\"([^\"]*)\".*", "$1");
            byte[] signature;
            try {
                signature = Ed25519.sign(signing.getPrivate(), Base64.getDecoder().decode(input));
            } catch (java.security.InvalidKeyException e) {
                throw new IllegalStateException(e);
            }
            answer(ex, "{\"request_id\":\"req-1\",\"data\":{\"signature\":\"vault:v1:"
                    + Base64.getEncoder().encodeToString(signature) + "\"}}");
        });
        vault.start();
    }

    @AfterEach
    void stop() {
        vault.stop(0);
    }

    private static void answer(HttpExchange ex, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @Test
    void anotherKeysSignatureIsRefusedAndNothingIsWritten() throws IOException {
        Path out = dir.resolve("licence.json");
        Path records = dir.resolve("issued.jsonl");
        URI address = URI.create("http://127.0.0.1:" + vault.getAddress().getPort() + "/");
        IssueLicenceCommand command = new IssueLicenceCommand(o -> new VaultTransitSigner(address, "transit", null,
                new VaultTransitSigner.Token("double-token-not-a-secret"), Duration.ofSeconds(5)),
                Clock.fixed(Instant.parse("2026-11-21T10:00:00Z"), ZoneOffset.UTC), new SecureRandom());
        ByteArrayOutputStream outBytes = new ByteArrayOutputStream();
        ByteArrayOutputStream errBytes = new ByteArrayOutputStream();
        int exit = command.run(List.of("--customer", "Example Customer Ltd", "--products", "PRODUCT_A",
                        "--kind", "PRODUCTION", "--starts", "2026-12-01", "--expires", "2027-11-30",
                        "--deployment", "ON_PREM", "--slot", "PRODUCT_A:PAGES_PER_MONTH=10000", "--key-id", "lic-2026:1",
                        "--records", records.toString(), "--out", out.toString()),
                new PrintStream(outBytes, true, StandardCharsets.UTF_8),
                new PrintStream(errBytes, true, StandardCharsets.UTF_8));
        String err = errBytes.toString(StandardCharsets.UTF_8);
        assertEquals(1, exit, err);
        assertTrue(err.startsWith("ISSUE_SIGNATURE_MISMATCH: "), err);
        assertFalse(Files.exists(out), "no licence file");
        assertFalse(Files.exists(records) && Files.size(records) > 0, "nothing recorded");
        Path runLog = records.resolveSibling(records.getFileName() + ".runs.log");
        assertTrue(Files.exists(runLog), "the run log records the refusal");
        {
            Map<String, Object> last = com.documentplatform.licensing.json.Json.parseObject(
                    Files.readAllLines(runLog).getLast());
            assertEquals("ISSUE_SIGNATURE_MISMATCH", last.get("code"));
        }
    }
}
