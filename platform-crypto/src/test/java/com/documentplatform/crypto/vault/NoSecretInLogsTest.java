package com.documentplatform.crypto.vault;

import com.documentplatform.crypto.CryptoException;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.envelope.EnvelopeInfo;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.documentplatform.crypto.envelope.ValueCipher;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.SimpleFormatter;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tokens, secret IDs, data keys, plaintext, KV secrets and fingerprinted values go through every path, good and
 * failing. None may appear in any log record at any level, any exception message or any {@code toString()}.
 */
class NoSecretInLogsTest {

    static final String PLAIN = "Zebediah Marchbanks-Quill";

    @Test
    void secretsNeverLeave() throws Exception {
        List<String> seen = Collections.synchronizedList(new ArrayList<>());
        Handler capture = new Handler() {
            private final SimpleFormatter f = new SimpleFormatter();

            @Override
            public void publish(LogRecord r) {
                seen.add(f.format(r));
            }

            @Override
            public void flush() {
            }

            @Override
            public void close() {
            }
        };
        capture.setLevel(Level.ALL);
        Logger root = Logger.getLogger("");
        Level before = root.getLevel();
        root.addHandler(capture);
        root.setLevel(Level.ALL);
        List<String> secrets = new ArrayList<>(List.of(PLAIN, "Marchbanks", VaultDev.ROOT_TOKEN, "kv-secret-Tq7"));
        List<String> texts = new ArrayList<>();
        try {
            String setupToken = VaultDev.token("platform-crypto-setup");
            String roleId = VaultDev.roleId();
            String secretId = VaultDev.newSecretId();
            secrets.addAll(List.of(setupToken, roleId, secretId));
            CustomerId c = CustomerId.of(UUID.randomUUID());
            new VaultKeyService(new VaultClient(VaultDev.address(), VaultAuth.token(setupToken))).createKeys(c);

            VaultClient client = new VaultClient(VaultDev.address(), null, VaultAuth.appRole("approle", roleId,
                    secretId), Duration.ofSeconds(10));
            VaultKeyService keys = new VaultKeyService(client);
            texts.add(client.toString());
            texts.add(keys.toString());
            texts.add(VaultAuth.appRole("approle", roleId, secretId).toString());
            texts.add(VaultAuth.token(setupToken).toString());

            try (KeyService.DataKey dk = keys.newDataKey(c)) {
                secrets.add(Base64.getEncoder().encodeToString(dk.key()));
                texts.add(dk.toString());
            }
            ValueCipher values = new ValueCipher(keys);
            String sealed = values.seal(c, "case:1:field:name", PLAIN);
            values.openText(c, "case:1:field:name", sealed);
            StreamCipher streams = new StreamCipher(keys);
            ByteArrayOutputStream enc = new ByteArrayOutputStream();
            EnvelopeInfo info = streams.encrypt(c, new ByteArrayInputStream(PLAIN.getBytes(StandardCharsets.UTF_8)),
                    enc);
            texts.add(info.toString());
            secrets.add(info.wrappedKey());
            Fingerprinter.Fingerprint fp = new Fingerprinter(keys).fingerprint(c, "name", PLAIN);
            texts.add(fp.toString());
            Fingerprinter.Input input = new Fingerprinter.Input("name", PLAIN);
            assertTrue(input.toString().contains("name"), input.toString());
            texts.add(input.toString());
            List<Fingerprinter.Input> inputs = List.of(input, new Fingerprinter.Input("other", PLAIN));
            texts.add(inputs.toString());
            new Fingerprinter(keys).fingerprintAll(c, inputs).forEach(f -> texts.add(f.toString()));

            VaultDev.root("POST", "/v1/secret/data/platform/logs", "{\"data\":{\"password\":\"kv-secret-Tq7\"}}");
            texts.add(client.readSecret("secret", "platform/logs").toString());

            // failures
            List<Runnable> failing = new ArrayList<>(List.of(
                    () -> values.open(c, "case:2:field:name", sealed),
                    () -> values.open(CustomerId.of(UUID.randomUUID()), "case:1:field:name", sealed),
                    () -> keys.unwrap(c, info.wrappedKey().substring(0, info.wrappedKey().length() - 4) + "AAA="),
                    () -> keys.createKeys(c),
                    () -> keys.newDataKey(CustomerId.of(UUID.randomUUID())),
                    () -> client.readSecret("secret", "platform/none"),
                    () -> new VaultKeyService(new VaultClient(VaultDev.address(), VaultAuth.appRole("approle", roleId,
                            "wrong-" + secretId))).newDataKey(c)));
            int port;
            try (ServerSocket s = new ServerSocket(0)) {
                port = s.getLocalPort();
            }
            int closed = port;
            failing.add(() -> new VaultKeyService(new VaultClient(URI.create("http://127.0.0.1:" + closed), null,
                    VaultAuth.token(setupToken), Duration.ofSeconds(2))).newDataKey(c));
            for (Runnable r : failing) {
                try {
                    r.run();
                    texts.add("no failure");
                } catch (CryptoException e) {
                    texts.add(e.getMessage());
                    texts.add(e.toString());
                }
            }
            try {
                streams.decrypt(c, info, new ByteArrayInputStream(new byte[]{1, 2, 3}), new ByteArrayOutputStream());
            } catch (CryptoException e) {
                texts.add(e.getMessage());
            }
            client.close();
        } finally {
            root.removeHandler(capture);
            root.setLevel(before);
        }
        assertFalse(texts.contains("no failure"), texts.toString());
        List<String> all = new ArrayList<>(seen);
        all.addAll(texts);
        for (String text : all) {
            for (String secret : secrets) {
                assertFalse(text.contains(secret), "a secret in: " + text);
            }
        }
        assertTrue(texts.size() > 20, "the paths ran");
    }
}
