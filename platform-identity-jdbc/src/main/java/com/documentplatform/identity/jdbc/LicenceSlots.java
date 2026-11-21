package com.documentplatform.identity.jdbc;

import com.documentplatform.identity.store.LicensedSlots;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceService;
import com.documentplatform.licensing.LicenceStatus;

import java.util.Objects;
import java.util.Optional;
import java.util.stream.Collectors;

/** {@link LicensedSlots} from platform-licensing: the slots of the licence in force. */
public final class LicenceSlots {

    private LicenceSlots() {
    }

    /**
     * The licence in force from the service, with its standing (activation follows the intake rule: allowed while
     * valid, expiring or in grace). When the service refuses the stored licence (missing, bad signature, other
     * customer), there is no licence in force and nothing can be activated.
     */
    public static LicensedSlots of(LicenceService service) {
        Objects.requireNonNull(service, "service");
        return () -> {
            LicenceStatus status;
            try {
                status = service.status();
            } catch (LicenceException refused) {
                return Optional.empty();
            }
            Licence licence = status.licence();
            LicensedSlots.Standing standing = switch (status.state()) {
                case NOT_STARTED -> LicensedSlots.Standing.NOT_STARTED;
                case EXPIRED_GRACE_OVER -> LicensedSlots.Standing.GRACE_OVER;
                case VALID, EXPIRING, EXPIRED_IN_GRACE -> LicensedSlots.Standing.IN_FORCE;
            };
            return Optional.of(new LicensedSlots.Current(licence.tenantId(),
                    licence.entitySlots().stream().map(EntitySlot::slotId).collect(Collectors.toSet()), standing));
        };
    }
}
