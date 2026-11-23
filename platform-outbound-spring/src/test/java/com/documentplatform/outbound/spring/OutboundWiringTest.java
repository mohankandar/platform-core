package com.documentplatform.outbound.spring;

import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.junit.jupiter.api.Assertions.*;

/** P028 B4: platform.outbound.*, shared by identity and integration. */
class OutboundWiringTest {

    private static ApplicationContextRunner runner(String... properties) {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboundAutoConfiguration.class))
                .withPropertyValues(properties);
    }

    private static OutboundConfigException refusal(Throwable t) {
        while (t != null && !(t instanceof OutboundConfigException)) t = t.getCause();
        return assertInstanceOf(OutboundConfigException.class, t);
    }

    @Test
    void withoutAnyOutboundSettingThereAreNoBeans() {
        runner().run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertTrue(ctx.getBeansOfType(Outbound.class).isEmpty());
            assertTrue(ctx.getBeansOfType(Installation.class).isEmpty());
        });
    }

    @Test
    void theInstallationAndTheOutboundBean() {
        runner("platform.outbound.installation=ON_PREMISES", "platform.outbound.allow-internal-addresses=true",
                "platform.outbound.allow-sign-in-none=true", "platform.outbound.proxy.host=proxy.corp.example",
                "platform.outbound.proxy.port=3128", "platform.outbound.proxy.bypass=.corp.example,files01",
                "platform.outbound.proxy.user=svc", "platform.outbound.proxy.password-secret=outbound/proxy")
                .withBean(SecretResolver.class, InMemorySecretResolver::new)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                    Installation i = ctx.getBean(Installation.class);
                    assertFalse(i.hosted());
                    assertTrue(i.allowInternalAddresses());
                    assertTrue(i.allowSignInNone());
                    assertTrue(i.proxy().signIn());
                    assertTrue(i.proxy().bypasses("files01"));
                    assertSame(i, ctx.getBean(Outbound.class).installation());
                });
        runner("platform.outbound.installation=HOSTED").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertTrue(ctx.getBean(Installation.class).hosted());
        });
    }

    @Test
    void everyProblemIsListedAndNoValueRepeated() {
        runner("platform.outbound.installation=HOSTED", "platform.outbound.allow-internal-addresses=true",
                "platform.outbound.allow-sign-in-none=true", "platform.outbound.private-ca-file=relative.pem",
                "platform.outbound.proxy.host=proxy.corp.example", "platform.outbound.proxy.port=3128",
                "platform.outbound.proxy.user=svc",
                "platform.outbound.proxy.password-secret=https://pasted-password-instead")
                .run(ctx -> {
                    OutboundConfigException e = refusal(ctx.getStartupFailure());
                    String m = e.getMessage();
                    assertTrue(m.startsWith("OUTBOUND_SETTINGS_INVALID"), m);
                    assertTrue(m.contains("allow-internal-addresses is never allowed on the hosted service"), m);
                    assertTrue(m.contains("allow-sign-in-none is never allowed on the hosted service"), m);
                    assertTrue(m.contains("private-ca-file must be an absolute path"), m);
                    assertTrue(m.contains("password-secret is not a secret reference"), m);
                    assertFalse(m.contains("pasted-password-instead"), m);
                    assertEquals(4, e.problems().size(), m);
                    assertNotNull(new OutboundFailureAnalyzer().analyze(ctx.getStartupFailure()));
                });
        // A proxy without the installation is never silently ignored.
        runner("platform.outbound.proxy.host=proxy.corp.example").run(ctx -> {
            OutboundConfigException e = refusal(ctx.getStartupFailure());
            assertTrue(e.getMessage().contains("platform.outbound.installation is required"), e.getMessage());
            assertTrue(e.getMessage().contains("proxy.port is required"), e.getMessage());
        });
        // A proxy with sign-in needs the app's SecretResolver.
        runner("platform.outbound.installation=ON_PREMISES", "platform.outbound.proxy.host=proxy.corp.example",
                "platform.outbound.proxy.port=3128", "platform.outbound.proxy.user=svc",
                "platform.outbound.proxy.password-secret=outbound/proxy")
                .run(ctx -> assertTrue(refusal(ctx.getStartupFailure()).getMessage()
                        .contains("SecretResolver bean is missing")));
    }

    @Test
    void theAppsOwnBeansWin() {
        Installation own = Installation.onPremises().withInternalAddresses(true);
        runner("platform.outbound.installation=HOSTED")
                .withBean(Installation.class, () -> own)
                .run(ctx -> {
                    assertSame(own, ctx.getBean(Installation.class));
                    assertSame(own, ctx.getBean(Outbound.class).installation());
                });
    }
}
