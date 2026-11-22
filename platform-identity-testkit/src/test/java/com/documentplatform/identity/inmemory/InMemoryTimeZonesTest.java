package com.documentplatform.identity.inmemory;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.store.EntityTimeZones;
import org.junit.jupiter.api.Test;

import java.time.ZoneId;
import java.util.List;
import java.util.Optional;

import static com.documentplatform.identity.store.TimeZoneOutcome.Result.*;
import static org.junit.jupiter.api.Assertions.*;

/** The in-memory store keeps time zones as the Postgres one does. */
class InMemoryTimeZonesTest {

    static final String TENANT = "TEN-TEST-0001";
    static final String S1 = "ENT-TEST-0001";

    @Test
    void ownZoneElseTheCustomersAndInvalidNamesRefused() {
        InMemoryIdentityStore store = new InMemoryIdentityStore();
        EntityTimeZones zones = new EntityTimeZones(store);
        assertEquals(CUSTOMER_UNKNOWN, zones.setCustomerZone(TENANT, "UTC", "a").result());
        store.saveCustomer(TENANT, "Customer");
        store.syncSlots(TENANT, List.of(S1));
        assertEquals(Optional.empty(), zones.effective(EntityUuid.fromSlotId(S1)));
        assertEquals(SET, zones.setCustomerZone(TENANT, "America/New_York", "a").result());
        assertEquals(Optional.of(ZoneId.of("America/New_York")), zones.effective(EntityUuid.fromSlotId(S1)));
        assertEquals(SET, zones.setEntityZone(S1, "America/Denver", "a").result());
        assertEquals(UNCHANGED, zones.setEntityZone(S1, "America/Denver", "a").result());
        assertEquals(Optional.of(ZoneId.of("America/Denver")), zones.effective(EntityUuid.fromSlotId(S1)));
        assertEquals(TIME_ZONE_INVALID, zones.setEntityZone(S1, "+02:00", "a").result());
        assertEquals(CLEARED, zones.setEntityZone(S1, "", "a").result());
        assertEquals(Optional.of(ZoneId.of("America/New_York")), zones.effective(EntityUuid.fromSlotId(S1)));
        assertEquals(SLOT_UNKNOWN, zones.setEntityZone("ENT-TEST-0099", "UTC", "a").result());
    }
}
