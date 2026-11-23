package com.documentplatform.crypto.vault;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CryptoException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P044: plain http to Vault only on this machine (SEC-30), so a token, an AppRole secret or an unwrapped data key never
 * crosses a network in clear; and a reply larger than any Vault answer is refused, not read into memory (SEC-48).
 */
class VaultAddressTest {

    @Test
    void plainHttpIsRefusedUnlessItStaysOnThisMachine() {
        for (String a : new String[]{"http://vault.internal:8200", "http://10.0.0.5:8200", "http://127.0.0.2:8200"}) {
            CryptoException e = assertThrows(CryptoException.class,
                    () -> new VaultClient(URI.create(a), VaultAuth.token("t")), a);
            assertEquals(CryptoError.CRYPTO_SETTINGS_INVALID, e.code());
            assertTrue(e.getMessage().contains("must be https"), e.getMessage());
        }
        for (String a : new String[]{"https://vault.internal:8200", "http://localhost:8200", "http://127.0.0.1:8200",
                "http://[::1]:8200"}) {
            assertDoesNotThrow(() -> new VaultClient(URI.create(a), VaultAuth.token("t")).close(), a);
        }
    }

    @Test
    void aReplyLargerThanAnyVaultAnswerIsRefused() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/", x -> {
            x.sendResponseHeaders(200, 0);
            try (OutputStream out = x.getResponseBody()) {
                byte[] chunk = new byte[64 * 1024];
                java.util.Arrays.fill(chunk, (byte) ' ');
                for (int i = 0; i < 40; i++) out.write(chunk); // 2.5 MiB
            } catch (java.io.IOException closed) {
                // the client stopped reading: as it should
            }
        });
        server.start();
        try (VaultClient c = new VaultClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                VaultAuth.token("t"))) {
            CryptoException e = assertThrows(CryptoException.class, () -> c.call("GET", "/v1/sys/health", null));
            assertEquals(CryptoError.CRYPTO_KEY_SERVICE_ERROR, e.code());
            assertTrue(e.getMessage().contains("larger than"), e.getMessage());
        } finally {
            server.stop(0);
        }
    }
}
