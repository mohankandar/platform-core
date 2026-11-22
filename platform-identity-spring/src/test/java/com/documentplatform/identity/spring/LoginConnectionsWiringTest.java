package com.documentplatform.identity.spring;

import com.documentplatform.identity.IdentityProviderType;
import com.documentplatform.identity.inmemory.InMemoryIdentityStore;
import com.documentplatform.identity.jdbc.IdentitySchemaException;
import com.documentplatform.identity.signin.DevelopmentLicence;
import com.documentplatform.identity.signin.IssuerFetch;
import com.documentplatform.identity.signin.TestSignIn;
import com.documentplatform.identity.store.ConnectionOutcome;
import com.documentplatform.identity.store.ConnectionValues;
import com.documentplatform.identity.store.LoginConnectionStore;
import com.documentplatform.identity.store.LoginConnections;
import com.documentplatform.identity.testkit.MockIssuer;
import com.documentplatform.integration.http.OutboundIssuerFetch;
import com.documentplatform.integration.spring.IntegrationAutoConfiguration;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.IntegrationStore;
import com.documentplatform.integration.testkit.InMemoryIntegrationStore;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretResolver;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.FilteredClassLoader;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.testcontainers.postgresql.PostgreSQLContainer;

import javax.sql.DataSource;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** B1 (identity, while here): the LoginConnections bean on the identity stores, with the issuer fetch over the outbound rules. */
class LoginConnectionsWiringTest {

    static final String TENANT = "TEN-TEST-0001";

    private static ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IntegrationAutoConfiguration.class,
                        IdentityIssuerFetchAutoConfiguration.class, IdentityLoginConnectionStoreAutoConfiguration.class,
                        IdentityLoginConnectionsAutoConfiguration.class))
                .withPropertyValues("platform.integration.installation=ON_PREMISES")
                .withBean(SecretResolver.class, InMemorySecretResolver::new)
                .withBean(IntegrationAudit.class, () -> IntegrationAudit.NONE)
                .withBean(IntegrationStore.class, InMemoryIntegrationStore::new);
    }

    @Test
    void withTheIntegrationModuleTheTestSignInGoesThroughTheOutboundRules() {
        InMemoryIdentityStore store = new InMemoryIdentityStore();
        store.saveCustomer(TENANT, "Test Customer");
        try (MockIssuer issuer = MockIssuer.start()) {
            runner().withBean(LoginConnectionStore.class, () -> store)
                    .withBean(DevelopmentLicence.class, () -> () -> true)
                    .run(ctx -> {
                        assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                        assertInstanceOf(OutboundIssuerFetch.class, ctx.getBean(IssuerFetch.class));
                        LoginConnections lc = ctx.getBean(LoginConnections.class);
                        ConnectionOutcome d = lc.createDraft(TENANT, "PRODUCT_A", 0, new ConnectionValues("test-idp",
                                IdentityProviderType.OIDC, issuer.issuer(), "web-client", "api://product-a", null),
                                "it-admin");
                        assertEquals(ConnectionOutcome.Result.DRAFT_CREATED, d.result(), d.message());
                        String token = issuer.token("api://product-a", List.of("readers"), Duration.ofMinutes(5));
                        ConnectionOutcome v = lc.validate(d.connection().connectionId(), 1, TestSignIn.withToken(token),
                                "it-admin");
                        assertEquals(ConnectionOutcome.Result.VALIDATED, v.result(), v.message());
                    });
        }
    }

    @Test
    void withoutAStoreOrAFetchThereIsNoLoginConnectionsBean() {
        // No DataSource and no store: nothing.
        runner().run(ctx -> {
            assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
            assertFalse(ctx.containsBean("identityLoginConnections"));
            assertTrue(ctx.containsBean("identityIssuerFetch"));
            assertSame(DevelopmentLicence.NONE, ctx.getBean(DevelopmentLicence.class));
        });
        // A store, but platform-integration is not on the classpath and the app gives no fetch: nothing.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdentityIssuerFetchAutoConfiguration.class,
                        IdentityLoginConnectionStoreAutoConfiguration.class,
                        IdentityLoginConnectionsAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("com.documentplatform.integration"))
                .withBean(LoginConnectionStore.class, InMemoryIdentityStore::new)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure(), () -> String.valueOf(ctx.getStartupFailure()));
                    assertFalse(ctx.containsBean("identityLoginConnections"));
                });
        // The app's own fetch is used.
        IssuerFetch own = MockIssuer.fetch();
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(IdentityIssuerFetchAutoConfiguration.class,
                        IdentityLoginConnectionStoreAutoConfiguration.class,
                        IdentityLoginConnectionsAutoConfiguration.class))
                .withClassLoader(new FilteredClassLoader("com.documentplatform.integration"))
                .withBean(LoginConnectionStore.class, InMemoryIdentityStore::new)
                .withBean(IssuerFetch.class, () -> own)
                .run(ctx -> assertNotNull(ctx.getBean(LoginConnections.class)));
    }

    @Test
    void onTheAppsDataSourceTheIdentityTablesAreCheckedFirst() {
        try (PostgreSQLContainer pg = new PostgreSQLContainer("postgres:16-alpine")) {
            pg.start();
            PGSimpleDataSource ds = new PGSimpleDataSource();
            ds.setURL(pg.getJdbcUrl());
            ds.setUser(pg.getUsername());
            ds.setPassword(pg.getPassword());
            runner().withBean(DataSource.class, () -> ds).run(ctx -> {
                Throwable t = ctx.getStartupFailure();
                while (t != null && !(t instanceof IdentitySchemaException)) t = t.getCause();
                assertInstanceOf(IdentitySchemaException.class, t, "the schema step has not run: the app does not start");
            });
        }
    }
}
