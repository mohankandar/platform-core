package com.documentplatform.integration;

import com.documentplatform.outbound.OutboundCode;
import com.documentplatform.integration.config.ConfigCheck;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.outbound.AddressGuard;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.config.SignIn.OAuth2ClientCredentials;
import com.documentplatform.notifications.inmemory.InMemorySecretResolver;
import com.documentplatform.notifications.secret.SecretRef;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.time.Clock;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/** The cloud-metadata address guard: always on; internal addresses only on premises when allowed. */
class AddressGuardTest {

    private static final AddressGuard HOSTED = new AddressGuard(false);
    private static final AddressGuard ON_PREM_INTERNAL = new AddressGuard(true);

    @Test
    void cloudMetadataIsAlwaysBlocked() {
        for (String host : new String[]{"169.254.169.254", "[fd00:ec2::254]", "168.63.129.16", "100.100.100.200",
                "metadata.google.internal", "METADATA.google.internal.", "169.254.10.1", "[fe80::1]", "0.0.0.0",
                "[::]", "[::ffff:169.254.169.254]", "224.0.0.1"}) {
            assertEquals(Optional.of(OutboundCode.OUT_ADDRESS_BLOCKED), ON_PREM_INTERNAL.check(host, false), host);
            assertEquals(Optional.of(OutboundCode.OUT_ADDRESS_BLOCKED), HOSTED.check(host, false), host);
        }
    }

    @Test
    void internalAddressesOnlyWhereAllowed() {
        for (String host : new String[]{"127.0.0.1", "10.1.2.3", "172.16.0.9", "192.168.1.1", "[::1]", "[fd12::1]",
                "100.64.0.1", "localhost"}) {
            assertEquals(Optional.of(OutboundCode.OUT_ADDRESS_BLOCKED), HOSTED.check(host, false), host);
            assertEquals(Optional.empty(), ON_PREM_INTERNAL.check(host, false), host);
        }
        assertEquals(Optional.empty(), HOSTED.check("8.8.8.8", false));
    }

    @Test
    void aNameIsBlockedIfAnyOfItsAddressesIs() {
        AddressGuard g = new AddressGuard(false, host -> new InetAddress[]{
                InetAddress.getByName("93.184.216.34"), InetAddress.getByName("169.254.169.254")});
        assertEquals(Optional.of(OutboundCode.OUT_ADDRESS_BLOCKED), g.check("rebind.example", false));
        AddressGuard inside = new AddressGuard(false, host -> new InetAddress[]{InetAddress.getByName("10.0.0.5")});
        assertEquals(Optional.of(OutboundCode.OUT_ADDRESS_BLOCKED), inside.check("intranet.example", false));
        AddressGuard unknown = new AddressGuard(false, host -> {
            throw new java.net.UnknownHostException();
        });
        assertEquals(Optional.of(OutboundCode.OUT_HOST_UNKNOWN), unknown.check("nowhere.example", false));
        assertEquals(Optional.empty(), unknown.check("nowhere.example", true), "behind a proxy the proxy resolves it");
    }

    @Test
    void theEngineRefusesBlockedAddressesWithoutConnecting() {
        InMemorySecretResolver secrets = new InMemorySecretResolver().put(SecretRef.of("int/t"), "t");
        // A resolver that maps a public-looking name to the metadata address, as DNS rebinding would.
        AddressGuard rebinding = new AddressGuard(true, host -> host.equals("files.example")
                ? new InetAddress[]{InetAddress.getByName("169.254.169.254")}
                : InetAddress.getAllByName(host));
        Installation inst = Installation.onPremises().withInternalAddresses(true);
        try (CallEngine engine = new CallEngine(new Outbound(inst, rebinding), secrets, Clock.systemUTC())) {
            IntegrationConfig c = Fx.oneStep(URI.create("https://files.example"),
                    new SignIn.BearerFixed(SecretRef.of("int/t")));
            AttemptResult r = engine.attempt(Fx.stored(c), Fx.delivery(1));
            assertEquals(Outcome.CANT_CONNECT, r.outcome());
            assertEquals(IntegrationCode.INT_ADDRESS_BLOCKED, r.code());

            // The token address is guarded too.
            IntegrationConfig o = Fx.oneStep(URI.create("https://api.example"), OAuth2ClientCredentials.secret(
                    URI.create("https://files.example/token"), "c", SecretRef.of("int/t"), null));
            AttemptResult t = engine.attempt(Fx.stored(o), Fx.delivery(1));
            assertEquals(Outcome.CANT_CONNECT, t.outcome());
            assertEquals(IntegrationCode.INT_ADDRESS_BLOCKED, t.code());
        }
    }

    @Test
    void hostedRefusesTheMockOnLoopback() {
        try (Fx fx = new Fx(com.documentplatform.integration.testkit.MockDestination.start(),
                Installation.hostedService())) {
            fx.secrets.put(SecretRef.of("int/t"), "t");
            IntegrationConfig c = Fx.oneStep(URI.create("https://localhost:" + fx.mock.port()),
                    new SignIn.BearerFixed(SecretRef.of("int/t")));
            AttemptResult r = fx.engine.attempt(Fx.stored(c), Fx.delivery(1));
            assertEquals(Outcome.NOT_CONFIGURED, r.outcome(), "the saved configuration is not valid when hosted");
            assertTrue(fx.mock.received().isEmpty());
        }
    }

    @Test
    void savingRefusesLiteralMetadataAddressesAndPlainHttpWhereNotAllowed() {
        IntegrationConfig meta = Fx.oneStep(URI.create("https://169.254.169.254"), new SignIn.BearerFixed(
                SecretRef.of("int/t")));
        assertTrue(ConfigCheck.check(meta, Installation.onPremises().withInternalAddresses(true)).errors().stream()
                .anyMatch(p -> p.code() == IntegrationCode.INT_CONFIG_ADDRESS_INVALID));
        IntegrationConfig http = Fx.oneStep(URI.create("http://api.example"), new SignIn.BearerFixed(
                SecretRef.of("int/t")));
        assertTrue(ConfigCheck.check(http, Installation.hostedService()).errors().stream()
                .anyMatch(p -> p.code() == IntegrationCode.INT_CONFIG_ADDRESS_NOT_SECURE));
        assertTrue(ConfigCheck.check(http, Installation.onPremises().withInternalAddresses(true)).ok());
    }
}
