package com.documentplatform.crypto.spring;

import com.documentplatform.crypto.CryptoError;
import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.envelope.RecordCipher;
import com.documentplatform.crypto.envelope.StreamCipher;
import com.documentplatform.crypto.envelope.ValueCipher;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.crypto.vault.VaultClient;
import com.documentplatform.crypto.vault.VaultKeyService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.diagnostics.FailureAnalysis;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

/** The crypto auto-configuration: properties, Vault wiring, the app's own key service, health without secrets. */
class CryptoWiringTest {

    static final String TOKEN = "hvs.APP-TOKEN-NEVER-SHOWN-3b7e";
    static final String SECRET_ID = "secret-id-NEVER-SHOWN-91d0";

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(CryptoAutoConfiguration.class));

    private static <T extends Throwable> T rootOf(Throwable t, Class<T> type) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (type.isInstance(c)) {
                return type.cast(c);
            }
        }
        throw new AssertionError("no " + type.getSimpleName() + " in " + t);
    }

    @Test
    void vaultIsWiredFromThePropertiesWithoutCallingItAtStartup() {
        // Nothing listens on this port: startup still succeeds; health shows the outage with its code.
        runner.withPropertyValues("platform.crypto.vault.address=http://127.0.0.1:9",
                        "platform.crypto.vault.login=TOKEN", "platform.crypto.vault.token=" + TOKEN,
                        "platform.crypto.vault.timeout=2s", "platform.licensing.tenant-id=TEN-TEST-0001")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertInstanceOf(VaultKeyService.class, ctx.getBean(KeyService.class));
                    assertNotNull(ctx.getBean(VaultClient.class));
                    assertNotNull(ctx.getBean(ValueCipher.class));
                    assertNotNull(ctx.getBean(RecordCipher.class));
                    assertNotNull(ctx.getBean(StreamCipher.class));
                    assertNotNull(ctx.getBean(Fingerprinter.class));
                    assertEquals(CustomerId.fromTenantId("TEN-TEST-0001"), ctx.getBean(CustomerId.class));
                    Health h = ctx.getBean(KeyServiceHealthIndicator.class).health();
                    assertEquals(Status.DOWN, h.getStatus());
                    assertEquals(CryptoError.CRYPTO_KEY_SERVICE_UNREACHABLE.name(), h.getDetails().get("code"));
                    assertEquals(VaultKeyService.ID, h.getDetails().get("keyService"));
                    assertFalse(h.toString().contains(TOKEN), h.toString());
                    assertFalse(ctx.getBean(CryptoConfig.class).toString().contains(TOKEN));
                });
    }

    @Test
    void appRoleWithASecretIdFile(@TempDir Path dir) throws Exception {
        Path secret = dir.resolve("secret-id");
        Files.writeString(secret, SECRET_ID + "\n");
        runner.withPropertyValues("platform.crypto.vault.address=https://vault.internal:8200",
                        "platform.crypto.vault.role-id=role-1", "platform.crypto.vault.secret-id-file=" + secret,
                        "platform.crypto.vault.namespace=team-a", "platform.crypto.vault.key-prefix=install2-")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertTrue(ctx.getBean(KeyService.class).toString().contains("prefix install2-"));
                    assertFalse(ctx.containsBean("cryptoCustomer"), "no tenant ID configured: no customer bean");
                    assertFalse(ctx.getBean(CryptoConfig.class).toString().contains(SECRET_ID));
                });
    }

    @Test
    void theAppsOwnKeyServiceWinsAndHealthFollowsIt() {
        InMemoryKeyService keys = new InMemoryKeyService();
        runner.withBean(KeyService.class, () -> keys).withPropertyValues("platform.crypto.tenant-id=TEN-TEST-0002")
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    assertSame(keys, ctx.getBean(KeyService.class));
                    assertFalse(ctx.containsBean("cryptoVaultClient"), "no Vault without Vault settings");
                    KeyServiceHealthIndicator health = ctx.getBean(KeyServiceHealthIndicator.class);
                    assertEquals(Status.UP, health.health().getStatus());
                    keys.down(true);
                    Health down = health.health();
                    assertEquals(Status.DOWN, down.getStatus());
                    assertEquals("CRYPTO_KEY_SERVICE_UNREACHABLE", down.getDetails().get("code"));
                    CustomerId c = ctx.getBean(CustomerId.class);
                    assertEquals(CustomerId.fromTenantId("TEN-TEST-0002"), c);
                });
    }

    @Test
    void aWrongConfigStopsStartupListingEveryProblemAndNoSecret() {
        runner.withPropertyValues("platform.crypto.vault.login=APPROLE", "platform.crypto.vault.secret-id=" + SECRET_ID,
                        "platform.crypto.vault.token=" + TOKEN, "platform.crypto.vault.timeout=0s",
                        "platform.crypto.vault.transit-mount=/bad", "platform.crypto.tenant-id=tenant-1")
                .run(ctx -> {
                    CryptoConfigException e = rootOf(ctx.getStartupFailure(), CryptoConfigException.class);
                    String m = e.getMessage();
                    for (String expected : new String[]{"platform.crypto.vault.address is missing",
                            "platform.crypto.vault.role-id is missing", "platform.crypto.vault.token is set but login",
                            "platform.crypto.vault.timeout", "platform.crypto.vault.transit-mount",
                            "platform.crypto.tenant-id"}) {
                        assertTrue(m.contains(expected), expected + " in " + m);
                    }
                    assertFalse(m.contains(TOKEN) || m.contains(SECRET_ID), m);
                    FailureAnalysis a = new CryptoFailureAnalyzer().analyze(ctx.getStartupFailure());
                    assertNotNull(a);
                    assertTrue(a.getAction().contains("platform-crypto-app"));
                    assertFalse(a.getDescription().contains(SECRET_ID));
                });
    }

    @Test
    void switchedOffMeansNoBeans() {
        runner.withPropertyValues("platform.crypto.enabled=false").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertFalse(ctx.containsBean("keyService"));
            assertTrue(ctx.getBeansOfType(ValueCipher.class).isEmpty());
        });
    }
}
