package com.documentplatform.integration;

import com.documentplatform.outbound.OutboundCode;
import com.documentplatform.outbound.Outbound;
import com.documentplatform.outbound.AddressGuard;
import com.documentplatform.outbound.Installation;
import com.documentplatform.integration.testkit.MockDestination;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * B2, connection pinning: the connection goes to the address the guard checked, never to a fresh lookup. The name
 * {@code rebind.test} is known only to the guard's resolver (no real DNS has it), which answers an allowed address to
 * the first lookup and the cloud metadata address to every later one: a client that looked the name up again would
 * either fail (the system resolver does not know it) or, with a rebinding resolver, reach the metadata address.
 */
class PinningTest {

    static final String NAME = "rebind.test";

    private MockDestination mock;

    @BeforeEach
    void start() {
        mock = MockDestination.start().expectNone();
    }

    @AfterEach
    void stop() {
        mock.close();
    }

    /** First lookup: 127.0.0.1 (allowed on premises with internal addresses); then 169.254.169.254. */
    private static AddressGuard rebinding(AtomicInteger lookups) {
        return new AddressGuard(true, host -> {
            if (!host.equals(NAME)) throw new UnknownHostException();
            int n = lookups.incrementAndGet();
            return new InetAddress[]{InetAddress.getByName(n == 1 ? "127.0.0.1" : "169.254.169.254")};
        });
    }

    private Outbound.Call get() {
        return new Outbound.Call("GET", URI.create("http://" + NAME + ":" + mock.port() + "/api/ping"),
                List.of(Map.entry("Accept", "application/json")), null, Duration.ofSeconds(5));
    }

    @Test
    void theConnectionGoesToTheCheckedAddressNotAFreshLookup() throws Exception {
        AtomicInteger lookups = new AtomicInteger();
        Installation onPrem = Installation.onPremises().withInternalAddresses(true);
        try (Outbound out = new Outbound(onPrem, rebinding(lookups))) {
            Outbound.Answer a = out.send(get(), Duration.ofSeconds(5), 10_000, false);
            assertEquals(201, a.status(), "reached the checked address");
            assertEquals(1, lookups.get(), "one lookup, by the guard; the client connected to its answer");
            assertEquals(1, mock.received().size());
            // Host name and Host header stay the original name.
            assertEquals(NAME + ":" + mock.port(), mock.received().get(0).header("host").orElseThrow());

            // The next call is checked again: the name now answers the metadata address, which is refused before
            // anything is sent.
            Outbound.Failure f = assertThrows(Outbound.Failure.class,
                    () -> out.send(get(), Duration.ofSeconds(5), 10_000, false));
            assertEquals(OutboundCode.OUT_ADDRESS_BLOCKED, f.code());
            assertEquals(IntegrationCode.INT_ADDRESS_BLOCKED, IntegrationCode.of(f.code())); // unchanged
            assertEquals(2, lookups.get());
            assertEquals(1, mock.received().size(), "nothing more was sent");
        }
    }

    @Test
    void aNameOnlyTheSystemResolverKnowsIsNeverLookedUpByTheClient() {
        // The guard knows no name at all: the call stops at the check, the client never resolves anything itself.
        AddressGuard knowsNothing = new AddressGuard(true, host -> {
            throw new UnknownHostException();
        });
        try (Outbound out = new Outbound(Installation.onPremises().withInternalAddresses(true), knowsNothing)) {
            Outbound.Failure f = assertThrows(Outbound.Failure.class, () -> out.send(new Outbound.Call("GET",
                            URI.create("http://localhost:" + mock.port() + "/api/ping"), List.of(), null,
                            Duration.ofSeconds(5)), Duration.ofSeconds(5), 10_000, false));
            assertEquals(OutboundCode.OUT_HOST_UNKNOWN, f.code());
            assertEquals(IntegrationCode.INT_HOST_UNKNOWN, IntegrationCode.of(f.code())); // unchanged
        }
        assertTrue(mock.received().isEmpty());
    }

    @Test
    void headersTheHttpLayerSetsAndLineBreaksAreRefused() {
        try (Outbound out = new Outbound(Installation.onPremises().withInternalAddresses(true))) {
            for (Map.Entry<String, String> h : List.of(Map.entry("Host", "other.example"),
                    Map.entry("Content-Length", "1"), Map.entry("X-Value", "a\r\nInjected: yes"))) {
                Outbound.Failure f = assertThrows(Outbound.Failure.class, () -> out.send(new Outbound.Call("GET",
                        URI.create("http://127.0.0.1:" + mock.port() + "/api/ping"), List.of(h), null,
                        Duration.ofSeconds(5)), Duration.ofSeconds(5), 10_000, false));
                assertEquals(OutboundCode.OUT_VALUE_NOT_ALLOWED, f.code());
                assertEquals(IntegrationCode.INT_VALUE_NOT_ALLOWED, IntegrationCode.of(f.code())); // unchanged
            }
        }
        assertTrue(mock.received().isEmpty());
    }
}
