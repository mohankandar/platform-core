package com.documentplatform.crypto.vault;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Redirects are never followed: the configured Vault address answers 307 to another local server, and the token
 * never reaches that server.
 */
class VaultRedirectTest {

    static final String TOKEN = "redirect-test-token-5v1q";

    HttpServer vault;
    HttpServer elsewhere;
    final List<String> elsewhereGot = new CopyOnWriteArrayList<>();

    @BeforeEach
    void start() throws IOException {
        elsewhere = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        elsewhere.createContext("/", ex -> {
            String token = ex.getRequestHeaders().getFirst("X-Vault-Token");
            elsewhereGot.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " token=" + token);
            byte[] body = "{\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        elsewhere.start();
        vault = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        vault.createContext("/", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:" + elsewhere.getAddress().getPort()
                    + ex.getRequestURI());
            ex.sendResponseHeaders(307, -1);
            ex.close();
        });
        vault.start();
    }

    @AfterEach
    void stop() {
        vault.stop(0);
        elsewhere.stop(0);
    }

    @Test
    void aRedirectIsAnErrorAndTheTokenNeverGoesElsewhere() {
        VaultClient client = new VaultClient(URI.create("http://127.0.0.1:" + vault.getAddress().getPort()), null,
                VaultAuth.token(TOKEN), Duration.ofSeconds(5));
        VaultKeyService keys = new VaultKeyService(client);
        CustomerId c = CustomerId.of(UUID.randomUUID());

        refusedWithRedirectNote(() -> keys.newDataKey(c));
        refusedWithRedirectNote(() -> keys.wrappingKey(c));
        refusedWithRedirectNote(() -> client.readSecret("secret", "platform/smtp"));
        KeyService.Health h = keys.health();
        assertFalse(h.up());
        assertEquals(CryptoError.CRYPTO_KEY_SERVICE_ERROR, h.code());

        assertEquals(List.of(), elsewhereGot, "the other server got no request, so no token");
    }

    static void refusedWithRedirectNote(Executable call) {
        CryptoException e = assertThrows(CryptoException.class, call);
        assertEquals(CryptoError.CRYPTO_KEY_SERVICE_ERROR, e.code(), e.getMessage());
        assertTrue(e.getMessage().contains("HTTP 307") && e.getMessage().contains("redirects are not followed"),
                e.getMessage());
        assertFalse(e.getMessage().contains(TOKEN));
    }
}
