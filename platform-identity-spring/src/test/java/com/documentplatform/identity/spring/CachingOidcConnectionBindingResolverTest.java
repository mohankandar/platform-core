package com.documentplatform.identity.spring;

import com.documentplatform.identity.OidcConnectionBinding;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Pins the 0.2.1 caching resolver: TTL on hits and misses, bounded size, input normalisation. */
class CachingOidcConnectionBindingResolverTest {

    private static final class MutableClock extends Clock {
        Instant now = Instant.parse("2026-11-17T12:00:00Z");
        @Override public ZoneOffset getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }

    private final List<String> calls = new ArrayList<>();
    private final OidcConnectionBinding binding = new OidcConnectionBinding(UUID.randomUUID(), UUID.randomUUID(), "p",
            URI.create("https://idp.test/realms/a"), "aud", 1);
    private Optional<OidcConnectionBinding> answer = Optional.of(binding);
    private final MutableClock clock = new MutableClock();
    private final CachingOidcConnectionBindingResolver resolver = new CachingOidcConnectionBindingResolver(
            (provider, issuer, audience) -> { calls.add(provider + "|" + issuer + "|" + audience); return answer; },
            Duration.ofSeconds(30), 2, clock);

    @Test
    void hitsAreCachedUntilTheTtlExpires() {
        assertEquals(Optional.of(binding), resolver.findActive("p", URI.create("https://idp.test/realms/a"), "aud"));
        assertEquals(Optional.of(binding), resolver.findActive("p", URI.create("https://idp.test/realms/a"), "aud"));
        assertEquals(1, calls.size());
        clock.now = clock.now.plusSeconds(30);
        resolver.findActive("p", URI.create("https://idp.test/realms/a"), "aud");
        assertEquals(2, calls.size());
    }

    @Test
    void missesAreCachedToo() {
        answer = Optional.empty();
        assertTrue(resolver.findActive("p", URI.create("https://idp.test/realms/a"), "aud").isEmpty());
        answer = Optional.of(binding);
        assertTrue(resolver.findActive("p", URI.create("https://idp.test/realms/a"), "aud").isEmpty());
        assertEquals(1, calls.size());
    }

    @Test
    void knownFinding_trailingSlashIsStrippedHereButNotInTheProductsSharedIssuerLookups() {
        // This resolver (and the earlier product lookup it replaces) strip one trailing slash; the earlier shared-issuer
        // candidate, grant and machine lookups compare the raw iss. To be unified in Phase 2.
        resolver.findActive(" p ", URI.create("https://idp.test/realms/a/"), " aud ");
        assertEquals(List.of("p|https://idp.test/realms/a|aud"), calls);
        resolver.findActive("p", URI.create("https://idp.test/realms/a"), "aud");
        assertEquals(1, calls.size(), "with and without the trailing slash share one cache entry");
    }

    @Test
    void theCacheIsBounded() {
        resolver.findActive("p", URI.create("https://idp.test/a"), "aud");
        resolver.findActive("p", URI.create("https://idp.test/b"), "aud");
        resolver.findActive("p", URI.create("https://idp.test/c"), "aud");
        resolver.findActive("p", URI.create("https://idp.test/a"), "aud");
        assertEquals(4, calls.size(), "the least recently used entry was evicted at size 2");
    }

    @Test
    void clearForgetsEverything() {
        resolver.findActive("p", URI.create("https://idp.test/realms/a"), "aud");
        resolver.clear();
        resolver.findActive("p", URI.create("https://idp.test/realms/a"), "aud");
        assertEquals(2, calls.size());
    }

    @Test
    void invalidInputsAndSettingsAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> resolver.findActive(" ", URI.create("https://idp.test"), "aud"));
        assertThrows(IllegalArgumentException.class, () -> resolver.findActive("p", URI.create("relative"), "aud"));
        assertThrows(IllegalArgumentException.class, () -> resolver.findActive("p", null, "aud"));
        assertThrows(IllegalArgumentException.class, () -> new CachingOidcConnectionBindingResolver((a, b, c) -> answer, Duration.ZERO, 1));
        assertThrows(IllegalArgumentException.class, () -> new CachingOidcConnectionBindingResolver((a, b, c) -> answer, Duration.ofSeconds(1), 0));
    }
}
