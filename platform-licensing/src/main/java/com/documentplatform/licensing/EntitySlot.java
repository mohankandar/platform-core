package com.documentplatform.licensing;

import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

/**
 * An entity slot: an ID issued by us (unique across all customers) and its allowances. The customer's Business Admin
 * activates and names the slot in the app; the name is not part of the licence.
 */
public record EntitySlot(String slotId, List<Allowance> allowances) {

    public EntitySlot {
        Licence.requireId(slotId, "slot ID");
        allowances = List.copyOf(allowances);
        if (allowances.isEmpty()) {
            throw new IllegalArgumentException("entity slot " + slotId + " has no allowance");
        }
        Set<String> seen = new HashSet<>();
        for (Allowance a : allowances) {
            if (!seen.add(a.product() + "/" + a.type())) {
                throw new IllegalArgumentException(
                        "entity slot " + slotId + " lists " + a.product() + " " + a.type() + " twice");
            }
        }
    }

    public Optional<Allowance> allowance(String product, AllowanceType type) {
        return allowances.stream().filter(a -> a.product().equals(product) && a.type() == type).findFirst();
    }
}
