package com.documentplatform.notifications.spring;

import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.email.MailAuth;
import com.documentplatform.notifications.email.MailSecurity;
import com.documentplatform.notifications.email.MailServer;
import com.documentplatform.notifications.email.MicrosoftOAuth;
import com.documentplatform.notifications.email.MicrosoftTokens;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import javax.sql.DataSource;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** Microsoft 365 sign-in settings: checked at startup with a message per key, never echoing a value; health. */
class MicrosoftMailConfigTest {

    private static final String P = "platform.notifications.mail.";
    private static final String DIRECTORY = "11111111-2222-3333-4444-555555555555";
    private static final String CLIENT = "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee";
    private static final String PASTED_SECRET = "Q8x~Kf9.Secret_Value~pasted-in-the-wrong-place";

    private static final String[] GOOD = {P + "auth=OAUTH2_MICROSOFT", P + "oauth.directory-id=" + DIRECTORY,
            P + "oauth.client-id=" + CLIENT, P + "oauth.credential-type=certificate",
            P + "oauth.credential-secret=notifications/m365-app-certificate",
            P + "oauth.sender-mailbox=notifications@customer.example"};

    private static NotificationsConfig config(String... keyValues) {
        Map<String, String> map = new LinkedHashMap<>();
        for (String kv : keyValues) {
            int eq = kv.indexOf('=');
            map.put(kv.substring(0, eq), kv.substring(eq + 1));
        }
        return NotificationsConfig.from(new Binder(new MapConfigurationPropertySource(map))
                .bindOrCreate(NotificationsProperties.PREFIX, NotificationsProperties.class));
    }

    private static List<String> problems(String... keyValues) {
        return assertThrows(NotificationsConfigException.class, () -> config(keyValues)).problems();
    }

    private static String[] with(String[] base, String... more) {
        List<String> all = new ArrayList<>(List.of(base));
        for (String m : more) {
            String key = m.substring(0, m.indexOf('='));
            all.removeIf(kv -> kv.startsWith(key + "="));
            if (!m.endsWith("=<remove>")) all.add(m);
        }
        return all.toArray(String[]::new);
    }

    private static String[] without(String[] base, String key) {
        return with(base, key + "=<remove>");
    }

    @Test
    void microsoft365NeedsOnlyTheIdsTheSecretReferenceAndTheMailbox() {
        MailServer m = config(GOOD).mail().orElseThrow();
        assertEquals(MailAuth.OAUTH2_MICROSOFT, m.auth());
        assertEquals("smtp.office365.com", m.host());
        assertEquals(587, m.port());
        assertEquals(MailSecurity.STARTTLS, m.security());
        assertEquals("notifications@customer.example", m.username(), "signs in as the mailbox");
        assertEquals("notifications@customer.example", m.fromAddress(), "sends from the mailbox");
        assertNull(m.password());
        MicrosoftOAuth o = m.oauth();
        assertEquals(DIRECTORY, o.directoryId());
        assertEquals(CLIENT, o.clientId());
        assertEquals(MicrosoftOAuth.CredentialType.CERTIFICATE, o.credentialType());
        assertEquals(SecretRef.of("notifications/m365-app-certificate"), o.credential());
        assertEquals(URI.create("https://login.microsoftonline.com/" + DIRECTORY + "/oauth2/v2.0/token"),
                o.tokenEndpoint());

        MailServer secret = config(with(GOOD, P + "oauth.credential-type=SECRET",
                P + "oauth.directory-id=customer.onmicrosoft.com", P + "from-address=alerts@customer.example",
                P + "oauth.authority=https://login.microsoftonline.us")).mail().orElseThrow();
        assertEquals(MicrosoftOAuth.CredentialType.SECRET, secret.oauth().credentialType());
        assertEquals("alerts@customer.example", secret.fromAddress());
        assertEquals("https://login.microsoftonline.us/customer.onmicrosoft.com/oauth2/v2.0/token",
                secret.oauth().tokenEndpoint().toString());
    }

    @Test
    void eachMistakeIsNamedWithItsKey() {
        assertEquals(List.of(P + "oauth.directory-id: missing; give the Microsoft Entra directory (tenant) ID of your "
                + "organisation (Entra admin center, Overview)"), problems(without(GOOD, P + "oauth.directory-id")));
        assertTrue(problems(without(GOOD, P + "oauth.client-id")).get(0).startsWith(P + "oauth.client-id: missing"));
        assertTrue(problems(without(GOOD, P + "oauth.credential-type")).get(0).startsWith(P + "oauth.credential-type: missing"));
        assertTrue(problems(without(GOOD, P + "oauth.credential-secret")).get(0).startsWith(P + "oauth.credential-secret: missing"));
        assertTrue(problems(without(GOOD, P + "oauth.sender-mailbox")).get(0).startsWith(P + "oauth.sender-mailbox: missing"));
        assertTrue(problems(with(GOOD, P + "oauth.directory-id=contoso")).get(0).startsWith(P + "oauth.directory-id: not a directory"));
        assertTrue(problems(with(GOOD, P + "oauth.credential-type=password")).get(0).startsWith(P + "oauth.credential-type: must be"));
        assertTrue(problems(with(GOOD, P + "oauth.sender-mailbox=notifications")).get(0).startsWith(P + "oauth.sender-mailbox: not a valid"));
        assertTrue(problems(with(GOOD, P + "oauth.authority=http://login.example")).get(0).startsWith(P + "oauth.authority: must be"));
        assertTrue(problems(with(GOOD, P + "security=NONE")).get(0).startsWith(P + "security: NONE is not allowed with auth OAUTH2_MICROSOFT"));
        assertEquals(List.of(P + "username: not used with auth OAUTH2_MICROSOFT; remove it (the app signs in as "
                        + P + "oauth.sender-mailbox)", P + "password-secret: not used with auth OAUTH2_MICROSOFT; remove it "
                        + "(the app signs in with " + P + "oauth.credential-secret)"),
                problems(with(GOOD, P + "username=notify", P + "password-secret=notifications/pw")));
        assertTrue(problems(with(GOOD, P + "auth=OAUTH")).get(0).startsWith(P + "auth: must be PASSWORD"));
        assertEquals(List.of(P + "oauth.*: only used with " + P + "auth=OAUTH2_MICROSOFT; remove these settings or set "
                        + P + "auth"), problems(P + "host=exchange.customer.internal", P + "from-address=n@customer.example",
                P + "oauth.client-id=" + CLIENT));
        assertEquals(5, problems(P + "auth=OAUTH2_MICROSOFT").size(), "every missing OAuth value at once");
    }

    @Test
    void aSecretPastedIntoAnyFieldIsNeverRepeated() {
        for (String field : List.of("oauth.client-id", "oauth.directory-id", "oauth.credential-secret",
                "oauth.sender-mailbox", "oauth.authority")) {
            List<String> problems = problems(with(GOOD, P + field + "=" + PASTED_SECRET));
            assertFalse(problems.isEmpty(), field);
            assertTrue(problems.get(0).startsWith(P + field + ":"), problems.toString());
            assertFalse(String.join("\n", problems).contains(PASTED_SECRET), field + ": " + problems);
            assertFalse(String.join("\n", problems).contains("Secret_Value"), field);
        }
    }

    @Test
    void passwordModeIsTheDefaultAndUnchanged() {
        MailServer m = config(P + "host=exchange.customer.internal", P + "username=notify",
                P + "password-secret=notifications/mail-password", P + "from-address=n@customer.example").mail().orElseThrow();
        assertEquals(MailAuth.PASSWORD, m.auth());
        assertNull(m.oauth());
        assertEquals(587, m.port());
        assertEquals(MailAuth.PASSWORD, config(P + "auth=password", P + "host=relay.internal", P + "security=NONE",
                P + "from-address=n@customer.example").mail().orElseThrow().auth());
    }

    @Test
    void healthShowsTheSignInModeAndTokenStateWithoutSecrets() {
        // Wiring: password mode and Microsoft 365 mode both start; health names the mode.
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotificationsAutoConfiguration.class))
                .withUserConfiguration(TestApp.Beans.class)
                .withBean(DataSource.class, () -> Db.migrated().owner)
                .withPropertyValues(GOOD)
                .run(ctx -> {
                    assertNull(ctx.getStartupFailure());
                    Map<?, ?> mail = (Map<?, ?>) ctx.getBean(NotificationsHealthIndicator.class).health().getDetails().get("mail");
                    assertEquals(Map.of("auth", "OAUTH2_MICROSOFT", "signIn", "Not fetched yet"), mail);
                    assertSame(ctx.getBean(MicrosoftTokens.class), ctx.getBean(MicrosoftTokens.class));
                });
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(NotificationsAutoConfiguration.class))
                .withUserConfiguration(TestApp.Beans.class)
                .withBean(DataSource.class, () -> Db.migrated().owner)
                .withPropertyValues(TestApp.mailProperties(2525))
                .run(ctx -> assertEquals(Map.of("auth", "PASSWORD"),
                        ctx.getBean(NotificationsHealthIndicator.class).health().getDetails().get("mail")));

        // A failing sign-in shows its code and fixed reason.
        String secretValue = "Client-Secret-HEALTH-CHECK";
        MicrosoftOAuth unreachable = new MicrosoftOAuth(DIRECTORY, CLIENT, MicrosoftOAuth.CredentialType.SECRET,
                SecretRef.of("notifications/m365-app-secret"), URI.create("http://127.0.0.1:1"));
        MailServer server = MailServer.microsoft365("notifications@customer.example", unreachable);
        MicrosoftTokens tokens = new MicrosoftTokens(Clock.systemUTC(), Duration.ofSeconds(1));
        assertFalse(tokens.token(unreachable, ref -> Optional.of(Secret.of(secretValue)), Duration.ofSeconds(1)).ok());
        Health h = new NotificationsHealthIndicator(new DestinationHealth(Clock.systemUTC()), server, tokens).health();
        Map<?, ?> mail = (Map<?, ?>) h.getDetails().get("mail");
        assertEquals("OAUTH2_MICROSOFT", mail.get("auth"));
        assertEquals("Failing", mail.get("signIn"));
        assertEquals(FailureCode.MAIL_OAUTH_TOKEN_UNREACHABLE.name(), mail.get("code"));
        assertNotNull(mail.get("lastTokenFetch"));
        String all = h.toString();
        assertFalse(all.contains(secretValue) || all.contains(CLIENT) || all.contains("notifications@customer.example")
                || all.contains("m365-app-secret"), all);
    }
}
