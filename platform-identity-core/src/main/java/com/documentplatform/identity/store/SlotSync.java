package com.documentplatform.identity.store;

import java.util.List;

/**
 * What {@link EntityStore#syncSlots} changed, as slot IDs.
 *
 * @param created        added as NOT_ACTIVATED
 * @param backInLicence  were NOT_IN_LICENCE and are listed again
 * @param notInLicence   newly marked NOT_IN_LICENCE (never deleted)
 * @param unchanged      listed and already stored, or already NOT_IN_LICENCE and still not listed
 */
public record SlotSync(List<String> created, List<String> backInLicence, List<String> notInLicence,
                       List<String> unchanged) {

    public SlotSync {
        created = List.copyOf(created);
        backInLicence = List.copyOf(backInLicence);
        notInLicence = List.copyOf(notInLicence);
        unchanged = List.copyOf(unchanged);
    }

    public boolean changed() {
        return !created.isEmpty() || !backInLicence.isEmpty() || !notInLicence.isEmpty();
    }
}
