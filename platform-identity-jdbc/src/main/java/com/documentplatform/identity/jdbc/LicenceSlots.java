package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.store.LicensedSlots;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceService;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** {@link LicensedSlots} from platform-licensing: the slots of the licence in force. */
public final class LicenceSlots {

    private LicenceSlots() {
    }

    /**
     * The licence in force from the service. When the service refuses the stored licence (missing, bad signature,
     * other customer), there is no licence in force and nothing can be activated.
     */
    public static LicensedSlots of(LicenceService service) {
        Objects.requireNonNull(service, "service");
        return () -> {
            Licence licence;
            try {
                licence = service.status().licence();
            } catch (LicenceException refused) {
                return Optional.empty();
            }
            return Optional.of(new LicensedSlots.Current(licence.tenantId(),
                    licence.entitySlots().stream().map(EntitySlot::slotId).collect(Collectors.toSet())));
        };
    }
}
