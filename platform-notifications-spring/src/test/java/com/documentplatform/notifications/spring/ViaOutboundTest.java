package com.documentplatform.notifications.spring;

import com.documentplatform.integration.testkit.MockProxy;
import com.documentplatform.integration.testkit.TestCertificates;
import com.documentplatform.notifications.Channel;
import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.EventKind;
import com.documentplatform.notifications.EventType;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.Message;
import com.documentplatform.notifications.MessageKind;
import com.documentplatform.notifications.OutgoingMessage;
import com.documentplatform.notifications.RecipientResolver;
import com.documentplatform.notifications.Schedule;
import com.documentplatform.notifications.SendResult;
import com.documentplatform.notifications.email.MicrosoftOAuth;
import com.documentplatform.notifications.email.MicrosoftTokens;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.notifications.secret.SecretResolver;
import com.documentplatform.notifications.spring.internal.NotificationsConfig;
import com.documentplatform.notifications.transport.HttpTransport;
import com.documentplatform.outbound.spring.OutboundAutoConfiguration;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpsConfigurator;
import com.sun.net.httpserver.HttpsServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import javax.sql.DataSource;
import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/**
 * P059 part 2 (LV-24.3 step 1): with {@code via-outbound} on, the Microsoft sign-in token request and the Teams and
 * Slack posts go through platform-outbound (proxy, address guard, private CA, time-outs); off by default, nothing
 * changes. Against a local https server whose certificate a private CA signed.
 */
class ViaOutboundTest {

    static final String DIRECTORY = "11111111-2222-3333-4444-555555555555";
    static final String CLIENT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    static final String N = "platform.notifications.";
    static final DestinationId TEAMS = DestinationId.of("ops-teams");
    static final DestinationId SLACK = DestinationId.of("ops-slack");

    @TempDir
    Path dir;

    private HttpsServer server;
    private Path caFile;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile Duration hookDelay = Duration.ZERO;
    private final Map<SecretRef, String> secrets = new ConcurrentHashMap<>();
    private final Db db = Db.migrated();

    @BeforeEach
    void start() throws Exception {
        TestCertificates.Ca ca = TestCertificates.ca(dir, "private-ca");
        TestCertificates.Server cert = TestCertificates.server(dir, ca, "hooks", "dns:localhost");
        caFile = Files.writeString(dir.resolve("private-ca.pem"), ca.pem());
        server = HttpsServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.setHttpsConfigurator(new HttpsConfigurator(cert.context()));
        server.createContext("/" + DIRECTORY + "/oauth2/v2.0/token", x -> answer(x, 200,
                "{\"access_token\":\"tok-SECRET\",\"token_type\":\"Bearer\",\"expires_in\":3600}"));
        server.createContext("/hook", x -> {
            try {
                Thread.sleep(hookDelay.toMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            answer(x, 200, "1");
        });
        server.start();
        String base = "https://localhost:" + server.getAddress().getPort();
        secrets.put(SecretRef.of("notifications/m365-app-secret"), "app-SECRET-value");
        secrets.put(SecretRef.of("notifications/ops-teams"), base + "/hook/teams");
        secrets.put(SecretRef.of("notifications/ops-slack"), base + "/hook/slack");
        intercepted = !cert.certificate().equals(peerCertificate(server.getAddress().getPort()));
    }

    /**
     * Whether something on this machine intercepts TLS to 127.0.0.1 (a security product re-signing it): then the
     * checks that need the private CA's own handshake are skipped, as in the identity and integration tests. CI runs
     * them all (it forbids skips).
     */
    private boolean intercepted;

    private void needsUnchangedTls() {
        org.junit.jupiter.api.Assumptions.assumeFalse(intercepted, "TLS to 127.0.0.1 is intercepted on this machine");
    }

    private static java.security.cert.X509Certificate peerCertificate(int port) throws Exception {
        java.util.concurrent.atomic.AtomicReference<java.security.cert.X509Certificate> seen =
                new java.util.concurrent.atomic.AtomicReference<>();
        javax.net.ssl.SSLContext any = javax.net.ssl.SSLContext.getInstance("TLS");
        any.init(null, new javax.net.ssl.TrustManager[]{new javax.net.ssl.X509TrustManager() {
            public void checkClientTrusted(java.security.cert.X509Certificate[] c, String a) {
            }

            public void checkServerTrusted(java.security.cert.X509Certificate[] c, String a) {
                seen.set(c[0]);
            }

            public java.security.cert.X509Certificate[] getAcceptedIssuers() {
                return new java.security.cert.X509Certificate[0];
            }
        }}, null);
        try (javax.net.ssl.SSLSocket s = (javax.net.ssl.SSLSocket) any.getSocketFactory().createSocket("127.0.0.1",
                port)) {
            s.startHandshake();
        }
        return seen.get();
    }

    private void answer(HttpExchange x, int status, String body) throws IOException {
        requests.add(x.getRequestMethod() + " " + x.getRequestURI().getPath());
        x.getRequestBody().readAllBytes();
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    @Configuration(proxyBeanMethods = false)
    class AppBeans {
        @Bean
        EventCatalogue catalogue() {
            return EventCatalogue.builder(TestApp.IT_ADMIN).register(EventType.builder("service.store-down",
                    "Store down", EventKind.TECHNICAL).schedule(Schedule.immediately()).recipients(TestApp.IT_ADMIN)
                    .build()).build();
        }

        @Bean
        RecipientResolver recipients() {
            return (tenant, entity, role) -> Set.of(TestApp.ADMIN);
        }

        @Bean
        SecretResolver secrets() {
            return ref -> Optional.ofNullable(secrets.get(ref)).map(Secret::of);
        }
    }

    private ApplicationContextRunner app(String... properties) {
        List<String> p = new ArrayList<>(List.of(
                N + "mail.auth=OAUTH2_MICROSOFT", N + "mail.oauth.directory-id=" + DIRECTORY,
                N + "mail.oauth.client-id=" + CLIENT, N + "mail.oauth.credential-type=SECRET",
                N + "mail.oauth.credential-secret=notifications/m365-app-secret",
                N + "mail.oauth.sender-mailbox=notifications@customer.example",
                N + "mail.oauth.authority=https://localhost:" + server.getAddress().getPort(),
                N + "teams.ops-teams=notifications/ops-teams", N + "slack.ops-slack=notifications/ops-slack",
                N + "webhook.connect-timeout=2s", N + "webhook.request-timeout=1s"));
        p.addAll(List.of(properties));
        DataSource ds = db.owner;
        return new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboundAutoConfiguration.class,
                        NotificationsAutoConfiguration.class))
                .withBean(AppBeans.class, () -> new AppBeans())
                .withBean(DataSource.class, () -> ds)
                .withPropertyValues(p.toArray(String[]::new));
    }

    private String[] outbound(String... more) {
        List<String> p = new ArrayList<>(List.of("platform.outbound.installation=ON_PREMISES",
                "platform.outbound.allow-internal-addresses=true", "platform.outbound.private-ca-file=" + caFile,
                N + "mail.via-outbound=true", N + "webhook.via-outbound=true"));
        p.addAll(List.of(more));
        return p.toArray(String[]::new);
    }

    private static MicrosoftTokens.Fetch token(ApplicationContext ctx) {
        MicrosoftOAuth oauth = ctx.getBean(NotificationsConfig.class).mail().orElseThrow().oauth();
        return ctx.getBean(MicrosoftTokens.class).token(oauth, ctx.getBean(SecretResolver.class),
                Duration.ofSeconds(5));
    }

    private static SendResult send(ApplicationContext ctx, ChannelKind kind, DestinationId d) {
        Channel channel = ctx.getBean(NotificationChannels.class).channels().stream()
                .filter(c -> c.kind() == kind).findFirst().orElseThrow();
        return channel.send(new OutgoingMessage(TestApp.TENANT, d, kind, Set.of(), new Message(MessageKind.EVENT,
                "Store down", List.of("Since 10:00"), URI.create("https://app.customer.example/status")),
                "m-0123456789abcdef0123456789abcdef01234567"));
    }

    @Test
    void offByDefaultNothingGoesThroughOutbound() {
        try (MockProxy proxy = MockProxy.start()) {
            app("platform.outbound.installation=ON_PREMISES", "platform.outbound.allow-internal-addresses=true",
                    "platform.outbound.private-ca-file=" + caFile, "platform.outbound.proxy.host=" + proxy.host(),
                    "platform.outbound.proxy.port=" + proxy.port()).run(ctx -> {
                assertNull(ctx.getStartupFailure());
                // the direct clients, as in 1.2.0: they trust the JVM's CAs only, so the private CA's server fails
                assertEquals(FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE, token(ctx).failure());
                assertEquals(FailureCode.WEBHOOK_UNREACHABLE, send(ctx, ChannelKind.TEAMS, TEAMS).failure());
                assertEquals(List.of(), proxy.targets(), "the outbound proxy was not used");
            });
        }
    }

    @Test
    void onTheTokenRequestTeamsAndSlackGoThroughTheOutboundProxyAndTrustThePrivateCa() {
        needsUnchangedTls();
        try (MockProxy proxy = MockProxy.start()) {
            app(outbound("platform.outbound.proxy.host=" + proxy.host(), "platform.outbound.proxy.port="
                    + proxy.port())).run(ctx -> {
                assertNull(ctx.getStartupFailure());
                assertNotNull(ctx.getBean(HttpTransport.class));
                assertTrue(token(ctx).ok());
                assertTrue(send(ctx, ChannelKind.TEAMS, TEAMS).sent());
                assertTrue(send(ctx, ChannelKind.SLACK, SLACK).sent());
                assertEquals(List.of("POST /" + DIRECTORY + "/oauth2/v2.0/token", "POST /hook/teams",
                        "POST /hook/slack"), requests);
                assertFalse(proxy.targets().isEmpty(), "through the proxy");
                assertTrue(proxy.targets().stream().allMatch(t -> t.equals("localhost:" + server.getAddress()
                        .getPort())), proxy.targets().toString());
            });
        }
    }

    @Test
    void withoutThePrivateCaTheCallsAreRefusedClearly() {
        app("platform.outbound.installation=ON_PREMISES", "platform.outbound.allow-internal-addresses=true",
                N + "mail.via-outbound=true", N + "webhook.via-outbound=true").run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(FailureCode.MAIL_OUTBOUND_REFUSED, token(ctx).failure());
            assertEquals(FailureCode.WEBHOOK_OUTBOUND_REFUSED, send(ctx, ChannelKind.SLACK, SLACK).failure());
        });
    }

    @Test
    void theAddressGuardRefusesInternalAndMetadataAddresses() {
        // Teams and Slack only (no mail): the webhook address is read at send time, so the refusal comes then.
        ApplicationContextRunner hooksOnly = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(OutboundAutoConfiguration.class,
                        NotificationsAutoConfiguration.class))
                .withBean(AppBeans.class, () -> new AppBeans())
                .withBean(DataSource.class, () -> db.owner)
                .withPropertyValues(N + "teams.ops-teams=notifications/ops-teams",
                        N + "slack.ops-slack=notifications/ops-slack", N + "webhook.via-outbound=true",
                        "platform.outbound.installation=ON_PREMISES", "platform.outbound.private-ca-file=" + caFile);
        hooksOnly.run(ctx -> {
            assertNull(ctx.getStartupFailure());
            assertEquals(FailureCode.WEBHOOK_OUTBOUND_REFUSED, send(ctx, ChannelKind.TEAMS, TEAMS).failure(),
                    "an internal address while platform.outbound.allow-internal-addresses is false");
        });
        secrets.put(SecretRef.of("notifications/ops-slack"), "https://169.254.169.254/hook/slack");
        hooksOnly.withPropertyValues("platform.outbound.allow-internal-addresses=true").run(ctx ->
                assertEquals(FailureCode.WEBHOOK_OUTBOUND_REFUSED, send(ctx, ChannelKind.SLACK, SLACK).failure(),
                        "cloud metadata is always refused"));
        assertTrue(requests.isEmpty(), "nothing reached the server");
    }

    @Test
    void theWebhookTimeOutsApply() {
        hookDelay = Duration.ofSeconds(3);
        needsUnchangedTls();
        app(outbound()).run(ctx -> assertEquals(FailureCode.WEBHOOK_TIMEOUT,
                send(ctx, ChannelKind.TEAMS, TEAMS).failure()));
    }

    @Test
    void startUpListsWhatIsMissingOrBlocked() {
        app(N + "mail.via-outbound=true", N + "webhook.via-outbound=true").run(ctx -> {
            Throwable failure = ctx.getStartupFailure();
            assertNotNull(failure);
            String text = problems(failure);
            assertTrue(text.contains(N + "mail.via-outbound: true, but platform.outbound.installation is not set"),
                    text);
            assertTrue(text.contains(N + "webhook.via-outbound: true, but platform.outbound.installation is not set"),
                    text);
        });
        app("platform.outbound.installation=ON_PREMISES", N + "mail.via-outbound=true").run(ctx -> {
            String text = problems(ctx.getStartupFailure());
            assertTrue(text.contains(N + "mail.oauth.authority: an internal address, but "
                    + "platform.outbound.allow-internal-addresses is false"), text);
        });
    }

    private static String problems(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause()) {
            if (t instanceof NotificationsConfigException n) {
                return String.join("\n", n.problems());
            }
        }
        fail("no NotificationsConfigException in " + failure);
        return "";
    }
}
