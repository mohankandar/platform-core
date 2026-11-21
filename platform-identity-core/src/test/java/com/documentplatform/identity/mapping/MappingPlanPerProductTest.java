package com.documentplatform.identity.mapping;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.identity.admission.EntityId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class MappingPlanPerProductTest {

    private static final String S1 = "ENT-TEST-0001";
    private static final String S2 = "ENT-TEST-0002";
    private static final EntityId E1 = EntityId.of(EntityUuid.fromSlotId(S1));
    private static final EntityId E2 = EntityId.of(EntityUuid.fromSlotId(S2));

    private static MappingRow row(String slot, String product, String group, String role) {
        return new MappingRow(2, EntityId.of(EntityUuid.fromSlotId(slot)), null, group, role, slot, product);
    }

    @Test
    void addAndRemoveArePerEntityAndProduct() {
        Map<String, Set<Grant>> now = Map.of(
                S1 + "/PRODUCT_A", Set.of(new Grant("keep", "READER"), new Grant("old", "EDITOR")),
                S1 + "/PRODUCT_B", Set.of(new Grant("other-product", "AUDITOR")));
        MappingPlan plan = MappingPlan.perProduct(
                List.of(row(S1, "PRODUCT_A", "keep", "READER"), row(S1, "PRODUCT_A", "new", "EDITOR"),
                        row(S2, "PRODUCT_A", "g", "READER")),
                (entity, slot, product) -> now.getOrDefault(slot + "/" + product, Set.of()));
        assertEquals(2, plan.entities().size(), "PRODUCT_B is not named in the file, so it is not touched");
        MappingPlan.EntityChange a = plan.entities().get(0);
        assertEquals(E1, a.entity());
        assertEquals(S1, a.slotId());
        assertEquals("PRODUCT_A", a.productCode());
        assertEquals(Set.of(new Grant("new", "EDITOR")), a.toAdd());
        assertEquals(Set.of(new Grant("old", "EDITOR")), a.toRemove());
        assertEquals(1, a.unchanged());
        MappingPlan.EntityChange b = plan.entities().get(1);
        assertEquals(E2, b.entity());
        assertEquals(Set.of(new Grant("g", "READER")), b.toAdd());
    }

    @Test
    void theSameEntityInTwoProductsGivesTwoChanges() {
        MappingPlan plan = MappingPlan.perProduct(
                List.of(row(S1, "PRODUCT_A", "g", "READER"), row(S1, "PRODUCT_B", "g", "AUDITOR")),
                (entity, slot, product) -> Set.of());
        assertEquals(List.of("PRODUCT_A", "PRODUCT_B"),
                plan.entities().stream().map(MappingPlan.EntityChange::productCode).toList());
    }

    @Test
    void loadingTheSameRowsAgainChangesNothing() {
        List<MappingRow> rows = List.of(row(S1, "PRODUCT_A", "g", "READER"));
        MappingPlan plan = MappingPlan.perProduct(rows, (entity, slot, product) -> Set.of(new Grant("g", "READER")));
        assertFalse(plan.hasChanges());
    }
}
