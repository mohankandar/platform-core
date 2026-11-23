package com.documentplatform.licensing;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import static com.documentplatform.licensing.Fixtures.*;
import static org.junit.jupiter.api.Assertions.*;

class LicenceModelTest {

    private static final LocalDate D1 = LocalDate.parse("2026-12-01");
    private static final LocalDate D2 = LocalDate.parse("2027-11-30");
    private static final List<EntitySlot> SLOTS = List.of(new EntitySlot("ENT-0001",
            List.of(new Allowance("PRODUCT_A", AllowanceType.PAGES_PER_MONTH, 1))));

    private static Licence build(LicenceType type, LicenceKind kind, LocalDate expires, LocalDate updates) {
        return new Licence("LIC-0001", 1, "C", "TEN-0001", List.of("PRODUCT_A"), SLOTS, type, kind, D1, expires,
                updates, Deployment.HOSTED, "k1", Instant.EPOCH);
    }

    @Test
    void kindsDecideBillabilityAndTestMarking() {
        assertFalse(LicenceKind.INSTALL.usageBillable());
        assertTrue(LicenceKind.PRODUCTION.usageBillable());
        assertFalse(LicenceKind.NON_PRODUCTION.usageBillable());
        assertFalse(LicenceKind.INSTALL.testEnvironment());
        assertFalse(LicenceKind.PRODUCTION.testEnvironment());
        assertTrue(LicenceKind.NON_PRODUCTION.testEnvironment());
        assertTrue(subscription(LicenceKind.NON_PRODUCTION, D1, D2, KEY_ID_1).testEnvironment());
    }

    @Test
    void typeRules() {
        assertDoesNotThrow(() -> build(LicenceType.SUBSCRIPTION, LicenceKind.PRODUCTION, D2, null));
        assertDoesNotThrow(() -> build(LicenceType.PERPETUAL, LicenceKind.PRODUCTION, null, D2));
        assertThrows(IllegalArgumentException.class, () -> build(LicenceType.SUBSCRIPTION, LicenceKind.PRODUCTION, null, null));
        assertThrows(IllegalArgumentException.class, () -> build(LicenceType.SUBSCRIPTION, LicenceKind.PRODUCTION, D2, D2));
        assertThrows(IllegalArgumentException.class, () -> build(LicenceType.PERPETUAL, LicenceKind.PRODUCTION, D2, D2));
        assertThrows(IllegalArgumentException.class, () -> build(LicenceType.PERPETUAL, LicenceKind.PRODUCTION, null, null));
        assertThrows(IllegalArgumentException.class, () -> build(LicenceType.SUBSCRIPTION, LicenceKind.PRODUCTION, D1.minusDays(1), null));
        assertThrows(IllegalArgumentException.class, () -> build(LicenceType.PERPETUAL, LicenceKind.INSTALL, null, D2));
    }

    @Test
    void slotAndAllowanceRules() {
        assertThrows(IllegalArgumentException.class, () -> new Allowance("PRODUCT_A", AllowanceType.STORAGE, 0));
        assertThrows(IllegalArgumentException.class, () -> new Allowance("product a", AllowanceType.STORAGE, 1));
        assertThrows(IllegalArgumentException.class, () -> new EntitySlot("ENT-1", List.of()));
        assertThrows(IllegalArgumentException.class, () -> new EntitySlot("ENT-1", List.of(
                new Allowance("PRODUCT_A", AllowanceType.STORAGE, 1), new Allowance("PRODUCT_A", AllowanceType.STORAGE, 2))));
        // A product listed twice (licensing-file-format-16).
        IllegalArgumentException twice = assertThrows(IllegalArgumentException.class, () -> new Licence("LIC-0001", 1,
                "C", "TEN-0001", List.of("PRODUCT_A", "PRODUCT_B", "PRODUCT_A"), SLOTS, LicenceType.SUBSCRIPTION,
                LicenceKind.PRODUCTION, D1, D2, null, Deployment.HOSTED, "k1", Instant.EPOCH));
        assertEquals("a product is listed twice", twice.getMessage());
        // An allowance must be for a listed product.
        assertThrows(IllegalArgumentException.class, () -> new Licence("LIC-0001", 1, "C", "TEN-0001",
                List.of("PRODUCT_B"), SLOTS, LicenceType.SUBSCRIPTION, LicenceKind.PRODUCTION, D1, D2, null,
                Deployment.HOSTED, "k1", Instant.EPOCH));
    }

    @Test
    void allFourAllowanceTypesHaveAUnit() {
        assertEquals("pages", AllowanceType.PAGES_PER_MONTH.unit());
        assertEquals("GB", AllowanceType.STORAGE.unit());
        assertEquals("seats", AllowanceType.CONCURRENT_SEATS.unit());
        assertEquals("seats", AllowanceType.NAMED_SEATS.unit());
    }
}
