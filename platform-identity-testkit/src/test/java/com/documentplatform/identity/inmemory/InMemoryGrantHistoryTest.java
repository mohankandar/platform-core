package com.documentplatform.identity.inmemory;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.mapping.Grant;
import com.documentplatform.identity.store.ChangeSource;
import com.documentplatform.identity.store.GrantAuthor;
import com.documentplatform.identity.store.GrantChange;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;

import static com.documentplatform.identity.store.GrantChange.Kind.*;
import static org.junit.jupiter.api.Assertions.*;

/** P031 B4 on the in-memory store: the same history rows as Postgres. */
class InMemoryGrantHistoryTest {

    static final String TENANT = "TEN-TEST-0001";
    static final String S1 = "ENT-TEST-0001";
    static final URI ISSUER = URI.create("https://login.example.test/oauth2/default");
    static final Instant T = Instant.parse("2026-11-22T10:00:00Z");

    @Test
    void everyChangeIsOneRowWithWhoAndTheRun() {
        InMemoryIdentityStore store = new InMemoryIdentityStore(Clock.fixed(T, ZoneOffset.UTC));
        store.saveCustomer(TENANT, "Test");
        store.syncSlots(TENANT, List.of(S1));
        assertEquals(2, store.add(S1, "PRODUCT_A", ISSUER, List.of(new Grant("a", "READER"), new Grant("b", "EDITOR")),
                GrantAuthor.setup("setup", "run-1")));
        assertEquals(0, store.add(S1, "PRODUCT_A", ISSUER, List.of(new Grant("a", "READER")),
                GrantAuthor.setup("setup", "run-2")), "nothing new, no row");
        assertTrue(store.changeRole(S1, "PRODUCT_A", ISSUER, "a", "READER", "EDITOR", GrantAuthor.app("user-7")));
        assertFalse(store.changeRole(S1, "PRODUCT_A", ISSUER, "a", "READER", "EDITOR", GrantAuthor.app("user-7")));
        assertEquals(1, store.remove(S1, "PRODUCT_A", ISSUER, List.of(new Grant("b", "EDITOR"))));
        assertEquals(Set.of(new Grant("a", "EDITOR")), store.grants(S1, "PRODUCT_A", ISSUER));

        List<GrantChange> h = store.forEntity(EntityUuid.fromSlotId(S1), T, T.plusSeconds(1));
        assertEquals(List.of(ADDED, ADDED, CHANGED, REMOVED), h.stream().map(GrantChange::change).toList());
        assertEquals("run-1", h.get(0).setupRunId());
        assertEquals(ChangeSource.SETUP, h.get(0).source());
        assertEquals(TENANT, h.get(0).tenantId());
        assertEquals("READER", h.get(2).roleBefore());
        assertEquals("EDITOR", h.get(2).roleAfter());
        assertEquals("user-7", h.get(2).changedBy());
        assertEquals(GrantAuthor.UNNAMED_APP_USER, h.get(3).changedBy());
        assertNull(h.get(3).roleAfter());
        assertEquals(List.of(), store.forEntity(EntityUuid.fromSlotId(S1), T.plusSeconds(1), T.plusSeconds(2)));
    }
}
