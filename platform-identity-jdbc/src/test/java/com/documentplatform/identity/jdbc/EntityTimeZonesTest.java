package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.store.EntityStore;
import com.documentplatform.identity.store.EntityTimeZone;
import com.documentplatform.identity.store.EntityTimeZones;
import com.documentplatform.identity.store.TimeZoneOutcome;
import org.junit.jupiter.api.Test;

import javax.sql.DataSource;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.documentplatform.identity.store.TimeZoneOutcome.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/** Per-entity time zones on Postgres, run as the DML-only app user (requirement 20, P020). */
class EntityTimeZonesTest {

    static final String DML = "select, insert, update, delete";
    static final String TENANT = "TEN-TEST-0001";
    static final String S1 = "ENT-TEST-0001";
    static final String S2 = "ENT-TEST-0002";
    static final ZoneId NEW_YORK = ZoneId.of("America/New_York");
    static final ZoneId CHICAGO = ZoneId.of("America/Chicago");

    private final Pg db = Pg.migrated();
    private final DataSource app = db.appUser(DML);
    private final PostgresEntityStore store = new PostgresEntityStore(app);
    private final EntityTimeZones zones = new EntityTimeZones(store);

    EntityTimeZonesTest() {
        store.saveCustomer(TENANT, "Test Customer");
        store.syncSlots(TENANT, List.of(S1, S2));
    }

    @Test
    void anEntityWithoutItsOwnZoneFollowsTheCustomers() {
        assertEquals(Optional.empty(), zones.effective(EntityUuid.fromSlotId(S1)), "nothing set yet");
        assertEquals(SET, zones.setCustomerZone(TENANT, "America/New_York", "admin-1").result());
        EntityTimeZone z = zones.zone(S1).orElseThrow();
        assertNull(z.own());
        assertEquals(NEW_YORK, z.customer());
        assertEquals(Optional.of(NEW_YORK), z.effective());
        assertEquals(Optional.empty(), z.ownZone());
        assertEquals(Optional.of(NEW_YORK), zones.effective(EntityUuid.fromSlotId(S1)));
        assertEquals(Optional.of(NEW_YORK), store.customerTimeZone(TENANT));
        assertEquals(UNCHANGED, zones.setCustomerZone(TENANT, "America/New_York", "admin-1").result());
    }

    @Test
    void theEntitysOwnZoneWins() {
        zones.setCustomerZone(TENANT, "America/New_York", "admin-1");
        TimeZoneOutcome set = zones.setEntityZone(S1, "America/Chicago", "admin-1");
        assertEquals(SET, set.result());
        assertEquals(CHICAGO, set.zone().own());
        assertEquals(Optional.of(CHICAGO), zones.effective(EntityUuid.fromSlotId(S1)));
        assertEquals(Optional.of(NEW_YORK), zones.effective(EntityUuid.fromSlotId(S2)), "other entities unchanged");
        assertEquals(UNCHANGED, zones.setEntityZone(S1, "America/Chicago", "admin-1").result());

        zones.setCustomerZone(TENANT, "Europe/London", "admin-1");
        assertEquals(Optional.of(CHICAGO), zones.effective(EntityUuid.fromSlotId(S1)), "still its own");

        TimeZoneOutcome cleared = zones.setEntityZone(S1, " ", "admin-1");
        assertEquals(CLEARED, cleared.result());
        assertEquals(Optional.of(ZoneId.of("Europe/London")), zones.effective(EntityUuid.fromSlotId(S1)),
                "cleared: it follows the customer's again");
        assertEquals(UNCHANGED, zones.setEntityZone(S1, null, "admin-1").result());
    }

    @Test
    void invalidZoneNamesAreRefusedWithTheirCode() {
        for (String bad : List.of("Mars/Olympus", "america/new_york", "+05:00", "GMT+1", "EST ", "x".repeat(70))) {
            assertEquals(TIME_ZONE_INVALID, zones.setEntityZone(S1, bad, "admin-1").result(), bad);
            assertEquals(TIME_ZONE_INVALID, zones.setCustomerZone(TENANT, bad, "admin-1").result(), bad);
        }
        assertTrue(zones.setCustomerZone(TENANT, null, "admin-1").refused(), "a customer's zone is not cleared");
        assertNull(zones.zone(S1).orElseThrow().own(), "nothing was stored");
        assertEquals(Optional.empty(), store.customerTimeZone(TENANT));
        assertEquals(SLOT_UNKNOWN, zones.setEntityZone("ENT-TEST-0099", "UTC", "admin-1").result());
        assertEquals(CUSTOMER_UNKNOWN, zones.setCustomerZone("TEN-TEST-0099", "UTC", "admin-1").result());
        assertEquals(NOT_AN_ID, zones.setEntityZone("S1", "UTC", "admin-1").result());
        assertThrows(IllegalArgumentException.class, () -> zones.setEntityZone(S1, "UTC", " "));
        assertEquals(SET, zones.setEntityZone(S1, "UTC", "admin-1").result(), "UTC is an IANA name");
    }

    @Test
    void theDatabaseRefusesAZoneThatIsNotAName() {
        assertThrows(RuntimeException.class, () -> db.exec("update idn_entity set time_zone = '+05:00'"));
    }

    @Test
    void storeLevelChangesAreReported() {
        assertEquals(Optional.of(EntityStore.Change.UPDATED), store.setEntityTimeZone(S2, CHICAGO));
        assertEquals(Optional.of(EntityStore.Change.UNCHANGED), store.setEntityTimeZone(S2, CHICAGO));
        assertEquals(Optional.empty(), store.setEntityTimeZone("ENT-TEST-0099", CHICAGO));
        assertEquals(CHICAGO, store.timeZone(EntityUuid.fromSlotId(S2)).orElseThrow().own());
        assertEquals(Optional.empty(), store.timeZone(EntityUuid.fromSlotId("ENT-TEST-0099")));
    }
}
