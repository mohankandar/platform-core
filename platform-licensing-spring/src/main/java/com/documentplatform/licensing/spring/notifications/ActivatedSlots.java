package com.documentplatform.licensing.spring.notifications;

import java.util.Map;

/**
 * The entity slots the customer's Business Admin has activated, supplied by the app (activation happens in the app).
 * Business licensing notifications are published once per activated slot; a slot that is not activated gets none.
 */
@FunctionalInterface
public interface ActivatedSlots {

    /**
     * @param tenantId the licence's tenant ID
     * @return activated slot ID → the app's entity ID for that slot (the entity whose notification settings apply)
     */
    Map<String, String> activated(String tenantId);
}
